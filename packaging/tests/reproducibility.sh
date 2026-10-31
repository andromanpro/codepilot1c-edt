#!/usr/bin/env sh
# Prove that clean CLI builds and the resulting archives are byte-identical.
set -eu

repo_dir=$(CDPATH= cd "$(dirname "$0")/../.." && pwd -P)
tmpdir=$(mktemp -d "${TMPDIR:-/tmp}/codepilot-repro.XXXXXX")
trap 'rm -rf "$tmpdir"' EXIT HUP INT TERM

cd "$repo_dir"
mvn -q -pl cli/codepilot-cli -am clean verify
cp cli/codepilot-cli/target/codepilot-cli-1.0.0-SNAPSHOT-all.jar "$tmpdir/cli-a.jar"

mvn -q -pl cli/codepilot-cli -am clean verify
cp cli/codepilot-cli/target/codepilot-cli-1.0.0-SNAPSHOT-all.jar "$tmpdir/cli-b.jar"
cmp "$tmpdir/cli-a.jar" "$tmpdir/cli-b.jar"

mvn -q -f packaging/pom.xml clean verify -Dcli.jar="$tmpdir/cli-a.jar"
cp packaging/target/codepilot-cli-1.0.0-SNAPSHOT.zip "$tmpdir/dist-a.zip"
cp packaging/target/codepilot-cli-1.0.0-SNAPSHOT.tar.gz "$tmpdir/dist-a.tar.gz"

mvn -q -f packaging/pom.xml clean verify -Dcli.jar="$tmpdir/cli-b.jar"
cmp "$tmpdir/dist-a.zip" packaging/target/codepilot-cli-1.0.0-SNAPSHOT.zip
cmp "$tmpdir/dist-a.tar.gz" packaging/target/codepilot-cli-1.0.0-SNAPSHOT.tar.gz

echo "reproducibility: CLI jars and ZIP/TAR archives are byte-identical"
