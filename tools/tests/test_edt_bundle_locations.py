#!/usr/bin/env python3

from __future__ import annotations

import os
from pathlib import Path, PurePosixPath, PureWindowsPath
import sys
import tempfile
import unittest


TOOLS_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS_DIR))

from edt_bundle_locations import (  # noqa: E402
    BundleLocationError,
    normalize_file_uri,
    resolve_bundle_location,
    validate_self_contained_edt_app,
)


class FileUriNormalizationTest(unittest.TestCase):
    def test_windows_drive_uri_is_absolute_without_windows_host(self) -> None:
        actual = normalize_file_uri(
            "file:///C:/Program%20Files/1C/%D0%AD%D0%94%D0%A2/plugin.jar",
            platform="windows",
        )

        self.assertEqual(
            PureWindowsPath("C:/Program Files/1C/ЭДТ/plugin.jar"),
            actual,
        )
        self.assertTrue(actual.is_absolute())
        self.assertEqual("C:", actual.drive)

    def test_windows_unc_uri_is_absolute_without_windows_host(self) -> None:
        actual = normalize_file_uri(
            "file://build-server/edt-share/plugins/plugin.jar",
            platform="windows",
        )

        self.assertEqual(
            PureWindowsPath("//build-server/edt-share/plugins/plugin.jar"),
            actual,
        )
        self.assertTrue(actual.is_absolute())

    def test_posix_file_uri_decodes_spaces(self) -> None:
        actual = normalize_file_uri("file:///opt/1C%20EDT/plugin.jar", platform="posix")

        self.assertEqual(PurePosixPath("/opt/1C EDT/plugin.jar"), actual)

    def test_authority_is_rejected_on_posix(self) -> None:
        with self.assertRaisesRegex(BundleLocationError, "authority/UNC"):
            normalize_file_uri("file://server/share/plugin.jar", platform="posix")

    def test_malformed_windows_and_unc_uris_are_rejected(self) -> None:
        cases = (
            "file:///Program%20Files/plugin.jar",
            "file://server/plugin.jar",
            "file://user@server/share/plugin.jar",
            "file://server:445/share/plugin.jar",
        )
        for value in cases:
            with self.subTest(value=value), self.assertRaises(BundleLocationError):
                normalize_file_uri(value, platform="windows")

    def test_ambiguous_or_traversing_percent_encoding_is_rejected(self) -> None:
        cases = (
            "file:///opt/%ZZ/plugin.jar",
            "file:///opt/a%2Fb/plugin.jar",
            "file:///opt/%2e%2e/plugin.jar",
            "file:///opt/a%5Cb/plugin.jar",
        )
        for value in cases:
            with self.subTest(value=value), self.assertRaises(BundleLocationError):
                normalize_file_uri(value, platform="posix")


class SelfContainedAppValidationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name).resolve()
        self.app = self.root / "1cedt-test.app"
        self.eclipse = self.app / "Contents" / "Eclipse"
        self.plugins = self.eclipse / "plugins"
        self.bundles = (
            self.eclipse
            / "configuration"
            / "org.eclipse.equinox.simpleconfigurator"
            / "bundles.info"
        )
        self.plugins.mkdir(parents=True)
        self.bundles.parent.mkdir(parents=True)

    def tearDown(self) -> None:
        self.temp_dir.cleanup()

    def write_bundle(self, location: str, *, extra_fields: str = "4,false") -> None:
        self.bundles.write_text(
            "#encoding=UTF-8\n"
            "#version=1\n"
            f"example,1.0.0,{location},{extra_fields}\n",
            encoding="utf-8",
        )

    def make_plugin(self, name: str = "example.jar") -> Path:
        plugin = self.plugins / name
        plugin.write_text("bundle\n", encoding="utf-8")
        return plugin

    def relative_from_eclipse(self, path: Path) -> str:
        return os.path.relpath(path, self.eclipse).replace(os.sep, "/")

    def test_self_contained_equinox_plugins_location_is_accepted(self) -> None:
        plugin = self.make_plugin("example_1.0.0.jar")
        self.write_bundle("plugins/example_1.0.0.jar")

        self.assertEqual([plugin.resolve()], validate_self_contained_edt_app(self.app, "run"))
        self.assertEqual([plugin.resolve()], validate_self_contained_edt_app(self.app, "copy"))

    def test_equinox_file_prefixed_relative_location_is_relocatable(self) -> None:
        plugin = self.make_plugin("example_1.0.0.jar")
        self.write_bundle("file:plugins/example_1.0.0.jar")

        self.assertEqual([plugin.resolve()], validate_self_contained_edt_app(self.app, "run"))
        self.assertEqual([plugin.resolve()], validate_self_contained_edt_app(self.app, "copy"))

    def test_internal_relative_location_with_spaces_is_accepted(self) -> None:
        plugin = self.make_plugin("example space.jar")
        self.write_bundle(self.relative_from_eclipse(plugin))

        self.assertEqual([plugin.resolve()], validate_self_contained_edt_app(self.app, "run"))
        self.assertEqual([plugin.resolve()], validate_self_contained_edt_app(self.app, "copy"))

    def test_percent_encoded_internal_relative_location_is_accepted(self) -> None:
        plugin = self.make_plugin("example space.jar")
        location = self.relative_from_eclipse(plugin).replace(" ", "%20")
        self.write_bundle(location)

        self.assertEqual([plugin.resolve()], validate_self_contained_edt_app(self.app))

    def test_external_relative_traversal_is_rejected(self) -> None:
        external = self.root / "outside.jar"
        external.write_text("outside\n", encoding="utf-8")
        self.write_bundle(self.relative_from_eclipse(external))

        with self.assertRaisesRegex(BundleLocationError, "dot path segments"):
            validate_self_contained_edt_app(self.app)

    def test_absolute_internal_location_is_run_only(self) -> None:
        plugin = self.make_plugin()
        self.write_bundle(plugin.as_uri())

        self.assertEqual([plugin.resolve()], validate_self_contained_edt_app(self.app, "run"))
        with self.assertRaisesRegex(BundleLocationError, "not relocatable"):
            validate_self_contained_edt_app(self.app, "copy")

    def test_absolute_external_location_is_rejected(self) -> None:
        external = self.root / "outside.jar"
        external.write_text("outside\n", encoding="utf-8")
        self.write_bundle(external.as_uri())

        with self.assertRaisesRegex(BundleLocationError, "escapes"):
            validate_self_contained_edt_app(self.app)

    def test_absolute_external_location_is_allowed_for_shared_p2_materialization(self) -> None:
        external = self.root / "shared-p2" / "example.jar"
        external.parent.mkdir()
        external.write_text("shared bundle\n", encoding="utf-8")

        actual = resolve_bundle_location(
            external.as_uri(),
            eclipse=self.eclipse,
            context="shared-p2 fixture",
            allow_external_absolute=True,
            reject_relative_symlinks=True,
        )

        self.assertEqual(external.resolve(), actual)

    def test_network_and_unknown_schemes_are_rejected(self) -> None:
        for location in ("https://example.invalid/plugin.jar", "reference:file:/tmp/plugin.jar"):
            with self.subTest(location=location):
                self.write_bundle(location)
                with self.assertRaisesRegex(BundleLocationError, "scheme"):
                    validate_self_contained_edt_app(self.app)

    def test_missing_artifact_is_rejected(self) -> None:
        self.write_bundle("plugins/missing.jar")

        with self.assertRaisesRegex(BundleLocationError, "does not exist"):
            validate_self_contained_edt_app(self.app)

    def test_malformed_entry_is_rejected(self) -> None:
        self.write_bundle("../../plugins/example.jar", extra_fields="4")

        with self.assertRaisesRegex(BundleLocationError, "five non-empty fields"):
            validate_self_contained_edt_app(self.app)

    def test_symlink_escape_is_rejected(self) -> None:
        external = self.root / "outside.jar"
        external.write_text("outside\n", encoding="utf-8")
        escaped = self.plugins / "escaped.jar"
        escaped.symlink_to(external)
        self.write_bundle(self.relative_from_eclipse(escaped))

        with self.assertRaisesRegex(BundleLocationError, "escapes"):
            validate_self_contained_edt_app(self.app)

    def test_copy_mode_rejects_symlink_even_when_it_resolves_inside_source(self) -> None:
        real = self.make_plugin("real.jar")
        linked = self.plugins / "linked.jar"
        linked.symlink_to(real)
        self.write_bundle("plugins/linked.jar")

        self.assertEqual([real.resolve()], validate_self_contained_edt_app(self.app, "run"))
        with self.assertRaisesRegex(BundleLocationError, "not safely relocatable"):
            validate_self_contained_edt_app(self.app, "copy")

    def test_materialization_mode_rejects_relative_symlink_source(self) -> None:
        real = self.make_plugin("real.jar")
        linked = self.plugins / "linked.jar"
        linked.symlink_to(real)

        with self.assertRaisesRegex(BundleLocationError, "symlink"):
            resolve_bundle_location(
                "plugins/linked.jar",
                eclipse=self.eclipse,
                context="relative symlink fixture",
                allow_external_absolute=True,
                reject_relative_symlinks=True,
            )

    def test_symlink_loop_is_a_controlled_validation_failure(self) -> None:
        loop = self.plugins / "loop.jar"
        loop.symlink_to(loop)
        self.write_bundle("plugins/loop.jar")

        with self.assertRaisesRegex(BundleLocationError, "cannot be resolved"):
            validate_self_contained_edt_app(self.app, "run")


if __name__ == "__main__":
    unittest.main()
