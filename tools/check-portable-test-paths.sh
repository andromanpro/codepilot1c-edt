#!/usr/bin/env bash
# Check executable tests and build inputs for developer-machine home paths.
# Documentation is intentionally outside this scan; examples may contain paths.
set -euo pipefail

script_dir=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repository_root=$(CDPATH= cd -- "$script_dir/.." && pwd)

unix_boundary='(^|[^A-Za-z0-9_])'
unix_home_segment='(Users|home)'
unix_home_pattern="${unix_boundary}/${unix_home_segment}/"
windows_users_pattern='[A-Za-z]:[\\/]Users[\\/]'

scan_paths=(
    "$repository_root/bundles"
    "$repository_root/features"
    "$repository_root/repositories"
    "$repository_root/runtime"
    "$repository_root/targets"
    "$repository_root/tools"
    "$repository_root/.github"
    "$repository_root/packaging"
    "$repository_root/pom.xml"
    "$repository_root/bom"
)

rg_args=(
    --hidden
    --line-number
    --no-heading
    --glob '*.java'
    --glob '*.sse'
    --glob '*.xml'
    --glob '*.pom'
    --glob '*.properties'
    --glob '*.sh'
    --glob '*.cmd'
    --glob '*.bat'
    --glob '*.yml'
    --glob '*.yaml'
    --glob '!target/**'
    --glob '!**/.tycho-consumer-pom.xml'
    --glob '!tools/check-portable-test-paths.sh'
)

violations=()
for pattern in "$unix_home_pattern" "$windows_users_pattern"; do
    if [[ "$pattern" == *'(^|'* ]]; then
        matches=$(rg --pcre2 "${rg_args[@]}" -e "$pattern" "${scan_paths[@]}" || true)
    else
        matches=$(rg "${rg_args[@]}" -e "$pattern" "${scan_paths[@]}" || true)
    fi
    if [[ -n "$matches" ]]; then
        violations+=("$matches")
    fi
done

unix_launcher="$repository_root/packaging/launchers/codepilot-edt-headless"
windows_launcher="$repository_root/packaging/launchers/codepilot-edt-headless.cmd"
if [[ ! -x "$unix_launcher" ]]; then
    echo "Portable launcher check failed: $unix_launcher is not executable." >&2
    exit 1
fi
if [[ ! -f "$windows_launcher" ]]; then
    echo "Portable launcher check failed: $windows_launcher is missing." >&2
    exit 1
fi
if ! rg -Fq '../MacOS/1cedt' "$unix_launcher"; then
    echo "Portable launcher check failed: macOS app sibling launcher candidate is missing." >&2
    exit 1
fi
if ! rg -Fq 'com.codepilot1c.core.headless' "$unix_launcher" "$windows_launcher"; then
    echo "Portable launcher check failed: headless application id is missing." >&2
    exit 1
fi

if ((${#violations[@]} > 0)); then
    printf '%s\n' "${violations[@]}"
    echo "Portable path guard failed: use repository-relative or classpath resources." >&2
    exit 1
fi

echo "Portable path guard passed."
