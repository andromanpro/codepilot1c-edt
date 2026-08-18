#!/usr/bin/env python3
"""Mechanical checks for the archive contract and cross-platform launchers."""

import contextlib
import os
import shutil
import stat
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
PACKAGING = ROOT / "packaging"
EXPECTED_ARCHIVE = {
    "bin/codepilot",
    "bin/codepilot.cmd",
    "bin/codepilot.ps1",
    "lib/codepilot-cli.jar",
    "LICENSE",
    "README.md",
    "SHA256SUMS",
}


class DistributionContractTest(unittest.TestCase):
    @contextlib.contextmanager
    def staged_distribution(self):
        cli_jar = ROOT / "cli/codepilot-cli/target/codepilot-cli-1.0.0-SNAPSHOT-all.jar"
        if not cli_jar.is_file():
            self.skipTest("build the shaded CLI jar first")
        with tempfile.TemporaryDirectory(prefix="codepilot launcher smoke ") as temp:
            root = Path(temp) / "installed distribution"
            bin_dir = root / "bin"
            lib_dir = root / "lib"
            bin_dir.mkdir(parents=True)
            lib_dir.mkdir()
            for name in ("codepilot", "codepilot.cmd", "codepilot.ps1"):
                shutil.copy2(PACKAGING / "launchers" / name, bin_dir / name)
            os.chmod(bin_dir / "codepilot", 0o755)
            shutil.copy2(cli_jar, lib_dir / "codepilot-cli.jar")
            yield root

    def test_launcher_line_endings_and_modes(self):
        posix = (PACKAGING / "launchers/codepilot").read_bytes()
        windows = (PACKAGING / "launchers/codepilot.cmd").read_bytes()
        powershell = (PACKAGING / "launchers/codepilot.ps1").read_bytes()
        self.assertNotIn(b"\r\n", posix)
        self.assertNotIn(b"\neval ", posix)
        self.assertNotRegex(posix.decode("ascii"), r"(?m)^\s*eval\b")
        self.assertIn(b"\r\n", windows)
        self.assertNotIn(b"\n", windows.replace(b"\r\n", b""))
        self.assertIn(b"\r\n", powershell)
        self.assertNotIn(b"\n", powershell.replace(b"\r\n", b""))
        powershell_text = powershell.decode("ascii")
        self.assertIn("@CliArguments", powershell_text)
        self.assertNotRegex(powershell_text, r"(?i)invoke-expression|\biex\b")
        self.assertTrue(stat.S_IMODE((PACKAGING / "launchers/codepilot").stat().st_mode) & 0o111)

    def test_assembly_descriptor_declares_stable_inventory(self):
        descriptor = (PACKAGING / "src/assembly/distribution.xml").read_text()
        for entry in EXPECTED_ARCHIVE:
            self.assertIn(entry.split("/")[-1], descriptor if entry.startswith("bin/") is False else descriptor)
        self.assertIn("<format>zip</format>", descriptor)
        self.assertIn("<format>tar.gz</format>", descriptor)

    def test_archive_inventory_when_built(self):
        archive = os.environ.get("CODEPILOT_DISTRIBUTION_ARCHIVE")
        if archive:
            archive_path = Path(archive)
        else:
            candidates = sorted((PACKAGING / "target").glob("codepilot-cli-*.zip"))
            if not candidates:
                self.skipTest("build packaging first or set CODEPILOT_DISTRIBUTION_ARCHIVE")
            archive_path = candidates[-1]
        with zipfile.ZipFile(archive_path) as package:
            files = {name for name in package.namelist() if not name.endswith("/")}
            self.assertEqual(files, EXPECTED_ARCHIVE)
            self.assertTrue(package.read("SHA256SUMS").decode("ascii").endswith("  lib/codepilot-cli.jar\n"))

    def test_posix_launcher_handles_spaces_and_relative_symlink(self):
        with tempfile.TemporaryDirectory(prefix="codepilot distribution ") as temp:
            root = Path(temp) / "installed version 1"
            real = root / "real" / "bin"
            real.mkdir(parents=True)
            (root / "real/lib").mkdir()
            jar = root / "real/lib/codepilot-cli.jar"
            jar.write_bytes(b"not a real jar; launcher only needs the path")
            launcher = PACKAGING / "launchers/codepilot"
            shutil.copy2(launcher, real / "codepilot")
            os.chmod(real / "codepilot", 0o755)
            link = root / "bin" / "codepilot"
            link.parent.mkdir()
            link.symlink_to(Path("../real/bin/codepilot"))

            fake_java = root / "fake java"
            args_file = root / "captured args"
            marker = root / "shell injection marker"
            fake_java.write_text("#!/bin/sh\nprintf '%s\\n' \"$@\" > \"$CODEPILOT_TEST_ARGS\"\n")
            os.chmod(fake_java, 0o755)
            env = os.environ.copy()
            env["CODEPILOT_JAVA"] = str(fake_java)
            env["CODEPILOT_TEST_ARGS"] = str(args_file)
            result = subprocess.run(
                [str(link), "version", "--label", "value with spaces", "$(touch 'shell injection marker');"],
                env=env,
                cwd=root,
                text=True,
                capture_output=True,
                check=False,
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            args = args_file.read_text().splitlines()
            self.assertEqual(args[0], "-jar")
            self.assertEqual(Path(args[1]).resolve(), jar.resolve())
            self.assertEqual(args[2:], ["version", "--label", "value with spaces", "$(touch 'shell injection marker');"])
            self.assertFalse(marker.exists(), "launcher evaluated an argument as shell code")

    def test_packaging_validation_follows_cli_jar_symlink(self):
        cli_jar = ROOT / "cli/codepilot-cli/target/codepilot-cli-1.0.0-SNAPSHOT-all.jar"
        if not cli_jar.is_file():
            self.skipTest("build the shaded CLI jar first")
        with tempfile.TemporaryDirectory(prefix="codepilot cli jar ") as temp:
            link = Path(temp) / "input jar link.jar"
            link.symlink_to(cli_jar)
            result = subprocess.run(
                ["mvn", "-q", "-f", str(PACKAGING / "pom.xml"), "validate", f"-Dcli.jar={link}"],
                text=True,
                capture_output=True,
                check=False,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    @unittest.skipIf(os.name == "nt", "POSIX launcher smoke runs on macOS/Linux hosts")
    def test_posix_launcher_smokes_built_cli(self):
        with self.staged_distribution() as root:
            result = subprocess.run(
                [str(root / "bin/codepilot"), "version"],
                text=True,
                capture_output=True,
                check=False,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertTrue(result.stdout.startswith("codepilot "), result.stdout)

    def test_powershell_launcher_smokes_built_cli_when_runner_is_available(self):
        runner = shutil.which("pwsh") or shutil.which("powershell")
        if runner is None:
            self.skipTest("PowerShell runner unavailable; use the documented Windows manual smoke")
        with self.staged_distribution() as root:
            environment = os.environ.copy()
            if os.name != "nt":
                environment["CODEPILOT_JAVA"] = shutil.which("java") or "java"
            result = subprocess.run(
                [runner, "-NoLogo", "-NoProfile", "-File", str(root / "bin/codepilot.ps1"), "version"],
                env=environment,
                text=True,
                capture_output=True,
                check=False,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertTrue(result.stdout.startswith("codepilot "), result.stdout)

    def test_cmd_launcher_smokes_built_cli_when_runner_is_available(self):
        runner = (shutil.which("cmd.exe") or shutil.which("cmd")) if os.name == "nt" else None
        if runner is None:
            self.skipTest("cmd.exe runner unavailable; use the documented Windows manual smoke")
        with self.staged_distribution() as root:
            invocation = subprocess.list2cmdline([str(root / "bin/codepilot.cmd"), "version"])
            result = subprocess.run(
                [runner, "/d", "/s", "/c", invocation],
                text=True,
                capture_output=True,
                check=False,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertTrue(result.stdout.startswith("codepilot "), result.stdout)


if __name__ == "__main__":
    unittest.main()
