#!/usr/bin/env python3
"""Materialize the exact EDT 2026.2 Maven target selected by bundles.info."""

from __future__ import annotations

import csv
import hashlib
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile
import unicodedata

from edt_bundle_locations import BundleLocationError, resolve_bundle_location


EXPECTED_PRODUCT_VERSION = "2026.2.0"
EXPECTED_BUILD_ID = "2026.2.0.289"
MARKER_NAME = ".codepilot1c-edt-target.json"
MANIFEST_NAME = ".codepilot1c-edt-target-files.json"


class MaterializationError(RuntimeError):
    pass


def fail(message: str) -> None:
    raise MaterializationError(message)


def resolve_eclipse(source: Path) -> Path:
    source = source.resolve(strict=True)
    app_eclipse = source / "Contents" / "Eclipse"
    if app_eclipse.is_dir():
        return app_eclipse.resolve(strict=True)
    if (source / "configuration" / "config.ini").is_file():
        return source
    fail(f"source is neither an EDT app nor its Eclipse directory: {source}")


def read_properties(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeError) as error:
        fail(f"cannot read config.ini: {path}: {error}")
    for number, raw in enumerate(lines, 1):
        line = raw.strip()
        if not line or line.startswith("#") or line.startswith("!"):
            continue
        if "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        if key in ("product.version", "eclipse.buildId"):
            if key in values:
                fail(f"duplicate {key} in {path} at line {number}")
            values[key] = value.strip()
    return values


def validate_baseline(eclipse: Path) -> None:
    config = eclipse / "configuration" / "config.ini"
    values = read_properties(config)
    product_version = values.get("product.version")
    build_id = values.get("eclipse.buildId")
    if product_version != EXPECTED_PRODUCT_VERSION or build_id != EXPECTED_BUILD_ID:
        fail(
            "source EDT baseline mismatch: expected "
            f"product.version={EXPECTED_PRODUCT_VERSION}, eclipse.buildId={EXPECTED_BUILD_ID}; "
            f"got product.version={product_version!r}, eclipse.buildId={build_id!r}"
        )


def read_bundles_info(path: Path, eclipse: Path) -> tuple[list[dict[str, object]], str]:
    try:
        raw = path.read_bytes()
        text = raw.decode("utf-8", errors="strict")
    except (OSError, UnicodeError) as error:
        fail(f"cannot read bundles.info: {path}: {error}")
    lines = text.splitlines()
    if "#encoding=UTF-8" not in lines or "#version=1" not in lines:
        fail(f"bundles.info lacks the required UTF-8/version headers: {path}")

    entries: list[dict[str, object]] = []
    output_names: set[str] = set()
    for number, line in enumerate(lines, 1):
        if not line or line.startswith("#"):
            continue
        try:
            row = next(csv.reader([line], strict=True))
        except csv.Error as error:
            fail(f"malformed bundles.info CSV at line {number}: {error}")
        if len(row) != 5 or any(not field or field != field.strip() for field in row):
            fail(f"bundles.info line {number} must contain exactly five non-empty fields")
        symbolic_name, version, location, start_level, auto_start = row
        try:
            parsed_start_level = int(start_level)
            if parsed_start_level != -1 and parsed_start_level < 1:
                raise ValueError
        except ValueError:
            fail(f"invalid bundle start level at line {number}: {start_level!r}")
        if auto_start not in ("true", "false"):
            fail(f"invalid bundle auto-start flag at line {number}: {auto_start!r}")
        try:
            target = resolve_bundle_location(
                location,
                eclipse=eclipse,
                context=f"bundles.info line {number}",
                allow_external_absolute=True,
                reject_relative_symlinks=True,
            )
        except BundleLocationError as error:
            fail(str(error))
        output_name = target.name
        output_key = unicodedata.normalize("NFC", output_name).casefold()
        if not output_name or output_key in output_names:
            fail(f"duplicate or empty plugin output name at line {number}: {output_name!r}")
        output_names.add(output_key)
        entries.append(
            {
                "symbolic_name": symbolic_name,
                "version": version,
                "source": str(target),
                "output_name": output_name,
            }
        )
    if not entries:
        fail(f"bundles.info contains no bundle entries: {path}")
    return entries, hashlib.sha256(raw).hexdigest()


def marker_payload(eclipse: Path, bundles_info: Path, digest: str, count: int) -> dict[str, object]:
    return {
        "format": 1,
        "baseline": EXPECTED_BUILD_ID,
        "product_version": EXPECTED_PRODUCT_VERSION,
        "source": str(eclipse),
        "source_eclipse": str(eclipse),
        "bundles_info": str(bundles_info),
        "bundles_info_sha256": digest,
        "plugin_count": count,
    }


def verify_existing(output: Path, marker: dict[str, object], entries: list[dict[str, object]]) -> None:
    marker_path = output / MARKER_NAME
    manifest_path = output / MANIFEST_NAME
    plugins = output / "Eclipse" / "plugins"
    if marker_path.is_symlink() or manifest_path.is_symlink():
        fail(f"materialized target metadata must not be symlinks: {output}")
    try:
        current_marker = json.loads(marker_path.read_text(encoding="utf-8"))
        current_manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        fail(f"output exists but is not a valid materialized target: {output}: {error}")
    if current_marker != marker or current_manifest != entries:
        fail(f"output exists with a different or stale materialization marker: {output}")
    if plugins.is_symlink() or not plugins.is_dir():
        fail(f"materialized plugins directory is missing: {plugins}")
    expected_names = {str(entry["output_name"]) for entry in entries}
    actual_names = {path.name for path in plugins.iterdir()}
    if actual_names != expected_names:
        fail(f"materialized plugins directory contents do not match its manifest: {plugins}")
    for entry in entries:
        link = plugins / str(entry["output_name"])
        source = str(entry["source"])
        source_is_directory = Path(source).is_dir()
        if (
            not link.is_symlink()
            or os.readlink(link) != source
            or not link.exists()
            or link.is_dir() != source_is_directory
        ):
            fail(f"materialized plugin link is missing or changed: {link}")


def write_json(path: Path, value: object) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def create_plugin_link(link: Path, source: Path, *, source_is_directory: bool) -> None:
    """Create one typed symlink and preserve privilege errors as controlled failures."""

    try:
        link.symlink_to(source, target_is_directory=source_is_directory)
    except OSError as error:
        fail(f"cannot create plugin symlink {link} -> {source}: {error}")


def materialize(source_arg: str, output_arg: str) -> Path:
    source = Path(source_arg).expanduser().resolve(strict=True)
    output_input = Path(output_arg).expanduser()
    if not output_input.is_absolute():
        fail(f"output path must be absolute: {output_arg}")
    if output_input.is_symlink():
        fail(f"output path must not be a symlink: {output_input}")
    output = output_input.resolve(strict=False)
    if output == Path(output.anchor):
        fail(f"refusing broad output path: {output}")
    eclipse = resolve_eclipse(source)
    if output == eclipse or eclipse in output.parents or output in eclipse.parents:
        fail(f"output must be separate from the source EDT installation: {output}")
    validate_baseline(eclipse)
    bundles_info = (
        eclipse
        / "configuration"
        / "org.eclipse.equinox.simpleconfigurator"
        / "bundles.info"
    )
    entries, digest = read_bundles_info(bundles_info, eclipse)
    marker = marker_payload(eclipse, bundles_info, digest, len(entries))

    if output.exists():
        if not output.is_dir():
            fail(f"output exists and is not a directory: {output}")
        verify_existing(output, marker, entries)
        return output / "Eclipse"

    parent = output.parent
    if not parent.is_dir():
        fail(f"output parent directory must already exist: {parent}")
    staging = Path(tempfile.mkdtemp(prefix=f".{output.name}.tmp.", dir=parent))
    try:
        plugins = staging / "Eclipse" / "plugins"
        plugins.mkdir(parents=True)
        for entry in entries:
            source = Path(str(entry["source"]))
            source_is_directory = source.is_dir()
            if not source_is_directory and not source.is_file():
                fail(f"bundle source changed or disappeared before link creation: {source}")
            create_plugin_link(
                plugins / str(entry["output_name"]),
                source,
                source_is_directory=source_is_directory,
            )
        write_json(staging / MARKER_NAME, marker)
        write_json(staging / MANIFEST_NAME, entries)
        staging.rename(output)
    except Exception:
        shutil.rmtree(staging, ignore_errors=True)
        raise
    return output / "Eclipse"


def main(argv: list[str]) -> int:
    if len(argv) != 3 or argv[1] in ("-h", "--help"):
        print(f"Usage: {Path(argv[0]).name} <source-1cedt.app-or-Eclipse> <absolute-output>", file=sys.stderr)
        return 0 if len(argv) == 2 else 2
    try:
        edt_home = materialize(argv[1], argv[2])
    except (MaterializationError, OSError) as error:
        print(f"materialize-edt-target: ERROR: {error}", file=sys.stderr)
        return 1
    print(f"edt.home={edt_home}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
