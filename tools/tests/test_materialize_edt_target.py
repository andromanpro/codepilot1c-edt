#!/usr/bin/env python3

from __future__ import annotations

import importlib.util
from pathlib import Path
from unittest import mock
import sys
import unittest


TOOLS_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS_DIR))
SPEC = importlib.util.spec_from_file_location(
    "materialize_edt_target",
    TOOLS_DIR / "materialize-edt-target.py",
)
assert SPEC is not None and SPEC.loader is not None
materializer = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(materializer)


class PluginSymlinkTest(unittest.TestCase):
    def test_directory_source_sets_target_is_directory(self) -> None:
        link = Path("target/plugins/directory.bundle")
        source = Path("source/plugins/directory.bundle")

        with mock.patch.object(Path, "symlink_to", autospec=True) as symlink_to:
            materializer.create_plugin_link(link, source, source_is_directory=True)

        symlink_to.assert_called_once_with(link, source, target_is_directory=True)

    def test_symlink_privilege_error_is_controlled_without_retry(self) -> None:
        link = Path("target/plugins/file.bundle.jar")
        source = Path("source/plugins/file.bundle.jar")
        privilege_error = OSError(1314, "A required privilege is not held by the client")

        with mock.patch.object(
            Path,
            "symlink_to",
            autospec=True,
            side_effect=privilege_error,
        ) as symlink_to:
            with self.assertRaisesRegex(materializer.MaterializationError, "cannot create plugin symlink"):
                materializer.create_plugin_link(link, source, source_is_directory=False)

        symlink_to.assert_called_once_with(link, source, target_is_directory=False)


if __name__ == "__main__":
    unittest.main()
