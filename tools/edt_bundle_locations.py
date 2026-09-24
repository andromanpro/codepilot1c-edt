#!/usr/bin/env python3
"""Strict parsing and containment checks for EDT bundles.info locations."""

from __future__ import annotations

import csv
import os
from pathlib import Path, PurePath, PurePosixPath, PureWindowsPath
import re
import sys
from urllib.parse import SplitResult, unquote_to_bytes, urlsplit


class BundleLocationError(RuntimeError):
    pass


_PERCENT_ESCAPE = re.compile(r"%[0-9A-Fa-f]{2}")
_WINDOWS_DRIVE_PATH = re.compile(r"^/[A-Za-z]:/")
_WINDOWS_AUTHORITY = re.compile(r"^[A-Za-z0-9._-]+$")


def _fail(message: str) -> None:
    raise BundleLocationError(message)


def _split_uri(value: str, context: str) -> SplitResult:
    if not value or value != value.strip():
        _fail(f"empty or whitespace-padded bundle location ({context}): {value!r}")
    try:
        parsed = urlsplit(value)
    except ValueError as error:
        _fail(f"malformed bundle location ({context}): {error}")
    if parsed.query or parsed.fragment:
        _fail(f"bundle location must not contain a query or fragment ({context}): {value}")
    return parsed


def _decode_uri_path(encoded: str, context: str) -> str:
    remainder = _PERCENT_ESCAPE.sub("", encoded)
    if "%" in remainder:
        _fail(f"invalid percent escape in bundle location ({context}): {encoded}")
    if re.search(r"%(?:2[fF]|5[cC])", encoded):
        _fail(f"encoded path separator is not allowed ({context}): {encoded}")
    try:
        decoded = unquote_to_bytes(encoded).decode("utf-8", errors="strict")
    except UnicodeError as error:
        _fail(f"invalid UTF-8 bundle location ({context}): {error}")
    if "\x00" in decoded:
        _fail(f"NUL byte is not allowed in bundle location ({context})")
    if "\\" in decoded:
        _fail(f"backslash path separator is not allowed in a file URI ({context}): {encoded}")
    return decoded


def _reject_dot_segments(path: str, context: str) -> None:
    if any(segment in (".", "..") for segment in path.split("/")):
        _fail(f"dot path segments are not allowed ({context}): {path}")


def normalize_file_uri(
    value: str,
    *,
    platform: str | None = None,
    context: str = "bundle location",
    reject_dot_segments: bool = True,
) -> PurePath:
    """Convert an absolute file URI to a native-flavoured pure path.

    ``platform`` may be set to ``windows`` in host-independent tests. A Windows
    drive/UNC URI is intentionally rejected on POSIX because it cannot identify
    a local artifact there.
    """

    platform = platform or ("windows" if os.name == "nt" else "posix")
    if platform not in ("posix", "windows"):
        raise ValueError(f"unsupported path platform: {platform}")

    parsed = _split_uri(value, context)
    if parsed.scheme.lower() != "file":
        _fail(f"only file: bundle locations are allowed ({context}): {value}")
    decoded = _decode_uri_path(parsed.path, context)
    if reject_dot_segments:
        _reject_dot_segments(decoded, context)

    if platform == "posix":
        if parsed.netloc:
            _fail(f"file URI authority/UNC location is not supported on POSIX ({context}): {value}")
        path = PurePosixPath(decoded)
        if not path.is_absolute():
            _fail(f"file URI must contain an absolute POSIX path ({context}): {value}")
        return path

    if parsed.netloc:
        if not _WINDOWS_AUTHORITY.fullmatch(parsed.netloc):
            _fail(f"invalid Windows UNC authority ({context}): {parsed.netloc!r}")
        if not decoded.startswith("/") or decoded.startswith("//"):
            _fail(f"Windows UNC URI must contain one absolute share path ({context}): {value}")
        path = PureWindowsPath(f"//{parsed.netloc}{decoded}")
        if (
            not path.is_absolute()
            or not path.drive.startswith("\\\\")
            or len(PurePosixPath(decoded).parts) < 3
            or ":" in decoded
        ):
            _fail(f"Windows UNC URI must name a server, share, and artifact ({context}): {value}")
        return path

    if not _WINDOWS_DRIVE_PATH.match(decoded):
        _fail(f"Windows file URI must use file:///C:/... form ({context}): {value}")
    path = PureWindowsPath(decoded[1:])
    if not path.is_absolute():
        _fail(f"Windows file URI does not contain an absolute drive path ({context}): {value}")
    if ":" in decoded[3:]:
        _fail(f"Windows alternate data stream syntax is not allowed ({context}): {value}")
    return path


def native_file_uri_path(value: str, *, context: str) -> Path:
    """Resolve an absolute file URI on the current host to a canonical artifact."""

    pure = normalize_file_uri(value, context=context)
    target = Path(os.fspath(pure))
    if target.is_symlink():
        _fail(f"bundle file URI must not name a symlink ({context}): {target}")
    try:
        resolved = target.resolve(strict=True)
    except (OSError, RuntimeError) as error:
        _fail(f"bundle target does not exist or cannot be resolved ({context}): {target}: {error}")
    if not (resolved.is_file() or resolved.is_dir()):
        _fail(f"bundle target is not a file or directory ({context}): {resolved}")
    return resolved


def _relative_location_path(
    value: str,
    *,
    context: str,
    allow_relative_file_scheme: bool = False,
) -> Path:
    parsed = _split_uri(value, context)
    if parsed.scheme and not (
        allow_relative_file_scheme
        and parsed.scheme.lower() == "file"
        and not parsed.netloc
        and not parsed.path.startswith("/")
    ):
        _fail(f"network or unknown bundle location scheme is not allowed ({context}): {parsed.scheme}")
    if parsed.netloc:
        _fail(f"authority is not allowed in a relative bundle location ({context}): {value}")
    decoded = _decode_uri_path(parsed.path, context)
    if not decoded:
        _fail(f"empty bundle location ({context})")
    _reject_dot_segments(decoded, context)
    if decoded.startswith("/"):
        _fail(f"relative bundle location must not be absolute ({context}): {value}")
    if ":" in decoded.split("/", 1)[0]:
        _fail(f"drive-like or opaque relative bundle location is not allowed ({context}): {value}")
    return Path(decoded)


def _is_within(path: Path, directory: Path) -> bool:
    try:
        path.relative_to(directory)
    except ValueError:
        return False
    return True


def _first_symlink(path: Path, directory: Path) -> Path | None:
    """Return the first symlink from a canonical directory to a lexical child path."""

    try:
        relative = path.relative_to(directory)
    except ValueError:
        return path
    current = directory
    for part in relative.parts:
        current /= part
        if current.is_symlink():
            return current
    return None


def resolve_bundle_location(
    value: str,
    *,
    eclipse: Path,
    context: str = "bundle location",
    allow_external_absolute: bool = False,
    reject_relative_symlinks: bool = False,
) -> Path:
    """Resolve one strict Equinox bundle location to a canonical local artifact.

    Plain relative and ``file:``-prefixed relative locations are based at the
    Eclipse install directory and must remain inside it. Absolute local file
    URIs may point outside Eclipse only for shared-p2 materialization. Network,
    unknown, traversing, missing, non-artifact, and unsafe symlink locations
    fail closed.
    """

    try:
        eclipse = Path(eclipse).resolve(strict=True)
    except (OSError, RuntimeError) as error:
        _fail(f"EDT Eclipse directory cannot be resolved ({context}): {eclipse}: {error}")
    if not eclipse.is_dir():
        _fail(f"EDT Eclipse path is not a directory ({context}): {eclipse}")

    parsed = _split_uri(value, context)
    is_relative_file = (
        parsed.scheme.lower() == "file"
        and not parsed.netloc
        and not parsed.path.startswith("/")
    )
    if parsed.scheme and not is_relative_file:
        if parsed.scheme.lower() != "file":
            _fail(
                f"network or unknown bundle location scheme is not allowed ({context}): "
                f"{parsed.scheme}"
            )
        target = native_file_uri_path(value, context=context)
        if not allow_external_absolute and not _is_within(target, eclipse):
            _fail(f"bundle target escapes the EDT Eclipse directory ({context}): {target}")
        return target

    relative = _relative_location_path(
        value,
        context=context,
        allow_relative_file_scheme=is_relative_file,
    )
    candidate = eclipse / relative
    if reject_relative_symlinks:
        symlink = _first_symlink(candidate, eclipse)
        if symlink is not None:
            _fail(
                f"bundle path contains a symlink that is not safely relocatable "
                f"({context}): {symlink}"
            )
    try:
        target = candidate.resolve(strict=True)
    except (OSError, RuntimeError) as error:
        _fail(f"bundle target does not exist or cannot be resolved ({context}): {candidate}: {error}")
    if not (target.is_file() or target.is_dir()):
        _fail(f"bundle target is not a file or directory ({context}): {target}")
    if not _is_within(target, eclipse):
        _fail(f"bundle target escapes the EDT Eclipse directory ({context}): {target}")
    return target


def validate_self_contained_edt_app(app_arg: str | Path, mode: str = "run") -> list[Path]:
    """Validate every bundle location and return canonical artifact paths.

    Equinox SimpleConfigurator passes the install location (the Eclipse
    directory) as the base URI for relative entries. Run mode also accepts
    absolute file URIs that resolve inside the app. Copy mode accepts only
    relative entries so relocation cannot retain an absolute source reference.
    """

    if mode not in ("run", "copy"):
        _fail(f"validation mode must be 'run' or 'copy': {mode!r}")
    try:
        app = Path(app_arg).expanduser().resolve(strict=True)
        eclipse = (app / "Contents" / "Eclipse").resolve(strict=True)
    except (OSError, RuntimeError) as error:
        _fail(f"EDT app or Eclipse directory cannot be resolved: {app_arg}: {error}")
    if not eclipse.is_dir() or not _is_within(eclipse, app):
        _fail(f"EDT Eclipse directory is not physically inside the app: {eclipse}")

    bundles_info_input = (
        eclipse
        / "configuration"
        / "org.eclipse.equinox.simpleconfigurator"
        / "bundles.info"
    )
    try:
        bundles_info = bundles_info_input.resolve(strict=True)
    except (OSError, RuntimeError) as error:
        _fail(f"bundles.info cannot be resolved: {bundles_info_input}: {error}")
    if not bundles_info.is_file() or not _is_within(bundles_info, eclipse):
        _fail(f"bundles.info is not a regular file physically inside Eclipse: {bundles_info}")
    try:
        lines = bundles_info.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeError) as error:
        _fail(f"cannot read bundles.info: {bundles_info}: {error}")
    if "#encoding=UTF-8" not in lines or "#version=1" not in lines:
        _fail(f"bundles.info lacks the required UTF-8/version headers: {bundles_info}")

    targets: list[Path] = []
    for number, raw in enumerate(lines, 1):
        if not raw or raw.startswith("#"):
            continue
        context = f"bundles.info line {number}"
        try:
            row = next(csv.reader([raw], strict=True))
        except csv.Error as error:
            _fail(f"malformed bundles.info CSV ({context}): {error}")
        if len(row) != 5 or any(not field or field != field.strip() for field in row):
            _fail(f"bundles.info entry must contain exactly five non-empty fields ({context})")
        _, _, location, start_level, auto_start = row
        try:
            parsed_start_level = int(start_level)
            if parsed_start_level != -1 and parsed_start_level < 1:
                raise ValueError
        except ValueError:
            _fail(f"invalid bundle start level ({context}): {start_level!r}")
        if auto_start not in ("true", "false"):
            _fail(f"invalid bundle auto-start flag ({context}): {auto_start!r}")

        parsed = _split_uri(location, context)
        is_relative_file = (
            parsed.scheme.lower() == "file"
            and not parsed.netloc
            and not parsed.path.startswith("/")
        )
        if mode == "copy" and parsed.scheme.lower() == "file" and not is_relative_file:
            _fail(f"absolute file URI is not relocatable in copy mode ({context}): {location}")
        target = resolve_bundle_location(
            location,
            eclipse=eclipse,
            context=context,
            reject_relative_symlinks=mode == "copy",
        )
        targets.append(target)

    if not targets:
        _fail(f"bundles.info contains no bundle entries: {bundles_info}")
    return targets


def main(argv: list[str]) -> int:
    if len(argv) not in (2, 3) or (len(argv) == 2 and argv[1] in ("-h", "--help")):
        print(f"Usage: {Path(argv[0]).name} <EDT-app> [run|copy]", file=sys.stderr)
        return 0 if len(argv) == 2 else 2
    mode = argv[2] if len(argv) == 3 else "run"
    try:
        targets = validate_self_contained_edt_app(argv[1], mode)
    except BundleLocationError as error:
        print(f"edt-bundle-locations: ERROR: {error}", file=sys.stderr)
        return 1
    print(f"validated {len(targets)} bundle location(s)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
