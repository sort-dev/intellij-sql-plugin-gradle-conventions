#!/usr/bin/env bash
set -euo pipefail
export GIT_OPTIONAL_LOCKS=0

here=$(cd "$(dirname "$0")" && pwd)
root=/home/jayson/DEV/sortdev
output_root="$root/plugin-builds"
dry_run=false
usage() {
    echo "Usage: $0 [--dry-run] [--output-root DIR]"
    echo "Build and stage clean Git checkouts only; never commit, sync, publish or edit their sources."
}
while (($#)); do
    case "$1" in
        --dry-run) dry_run=true; shift ;;
        --output-root) output_root=${2:?--output-root needs a directory}; shift 2 ;;
        --help|-h) usage; exit 0 ;;
        *) usage >&2; exit 2 ;;
    esac
done
command -v git >/dev/null
command -v python3 >/dev/null
stamp=$(date -u +%Y%m%d-%H%M)
destination="$output_root/$stamp"
echo "Staging destination: $destination"
if ! $dry_run; then
    mkdir -p "$output_root"
    mkdir "$destination" || { echo "Destination already exists; retry in the next minute (nothing overwritten)." >&2; exit 1; }
    cat > "$destination/MANIFEST.md" <<EOF
# SQL plugin dev builds

Staging started: $(date -u +%Y-%m-%dT%H:%M:%SZ)

| Plugin | Version | Branch + SHA | Source checkout | IDE since/until | ZIP SHA-256 | Built-at UTC |
| --- | --- | --- | --- | --- | --- | --- |
EOF
fi

failed=0
notices=()
for plugin in doris duckdb trino sql-transpiler clickhouse; do
    name="$plugin-intellij-plugin"
    if [[ $plugin == clickhouse ]]; then checkout="$root/$name"; else checkout="$root/$name-worktrees/grand-unification"; fi
    if [[ ! -f "$checkout/gradlew" ]] || ! git -C "$checkout" rev-parse --verify HEAD >/dev/null 2>&1; then
        echo "SKIP $plugin: missing checkout/Gradle wrapper at $checkout"
        notices+=("$plugin: missing checkout/Gradle wrapper at $checkout"); failed=1; continue
    fi
    if [[ -n $(git -C "$checkout" status --porcelain=v1 --untracked-files=all) ]]; then
        echo "SKIP $plugin: dirty tree at $checkout"
        notices+=("$plugin: dirty tree at $checkout"); failed=1; continue
    fi
    sha=$(git -C "$checkout" rev-parse HEAD)
    short=$(git -C "$checkout" rev-parse --short=12 HEAD)
    branch=$(git -C "$checkout" symbolic-ref --short -q HEAD || echo detached)
    epoch=$(git -C "$checkout" show -s --format=%ct HEAD)
    identity=$(python3 - "$epoch" "$short" <<'PY'
import datetime, sys
print(datetime.datetime.fromtimestamp(int(sys.argv[1]), datetime.timezone.utc).strftime('%Y%m%d%H%M') + '+' + sys.argv[2])
PY
)
    args=(buildPlugin -Prelease=false "-PstageDevIdentity=$identity" --init-script "$here/stage-plugin-versions.init.gradle")
    if $dry_run; then
        echo "WOULD BUILD $plugin: $checkout ($branch $sha), version <base>-dev.$identity"
        printf '  ./gradlew'; printf ' %q' "${args[@]}"; echo
        continue
    fi
    echo "Building $plugin ($branch $short)"
    log="$destination/$name-build.log"
    if ! (
        cd "$checkout"
        if command -v mise >/dev/null; then mise exec -- ./gradlew "${args[@]}"; else ./gradlew "${args[@]}"; fi
    ) > "$log" 2>&1; then
        echo "FAILED $plugin: see $log"
        notices+=("$plugin: build failed; see $name-build.log"); failed=1; continue
    fi
    if [[ $(git -C "$checkout" rev-parse HEAD) != "$sha" ]] || [[ -n $(git -C "$checkout" status --porcelain=v1 --untracked-files=all) ]]; then
        echo "SKIP $plugin: checkout changed while building; no ZIP copied"
        notices+=("$plugin: checkout changed while building; no ZIP copied"); failed=1; continue
    fi
    if ! python3 - "$checkout" "$destination" "$name" "$identity" "$branch" "$sha" <<'PY'
import datetime, hashlib, io, pathlib, shutil, sys, xml.etree.ElementTree as ET, zipfile
checkout, destination = map(pathlib.Path, sys.argv[1:3])
name, identity, branch, sha = sys.argv[3:]
candidates = list((checkout / 'build/distributions').glob(f'{name}-*-dev.{identity}.zip'))
if not candidates:
    raise SystemExit('Build produced no matching clean dev ZIP; no release-looking artifact staged')
source = max(candidates, key=lambda path: (path.stat().st_mtime_ns, path.name))
descriptor = None
with zipfile.ZipFile(source) as archive:
    for entry in archive.namelist():
        if not entry.endswith('.jar'): continue
        with zipfile.ZipFile(io.BytesIO(archive.read(entry))) as jar:
            if 'META-INF/plugin.xml' not in jar.namelist(): continue
            candidate = ET.fromstring(jar.read('META-INF/plugin.xml'))
            if candidate.findtext('id') == f'dev.sort.{name}':
                if descriptor is not None: raise SystemExit('Multiple matching plugin descriptors')
                descriptor = candidate
if descriptor is None: raise SystemExit('No matching plugin.xml found')
version = descriptor.findtext('version', '')
if source.name != f'{name}-{version}.zip' or not version.endswith(f'-dev.{identity}'):
    raise SystemExit(f'Filename/IDE version mismatch: {source.name}, {version}')
target = destination / source.name
if target.exists(): raise SystemExit(f'Refusing to overwrite {target}')
shutil.copy2(source, target)
ide = descriptor.find('idea-version')
ide_range = f"{ide.get('since-build', '')}/{ide.get('until-build', '')}" if ide is not None else 'unspecified'
built_at = datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')
cells = [descriptor.findtext('id'), version, f'{branch} {sha}', str(checkout), ide_range,
         hashlib.sha256(target.read_bytes()).hexdigest(), built_at]
with (destination / 'MANIFEST.md').open('a') as manifest:
    manifest.write('| ' + ' | '.join(str(cell).replace('|', r'\|').replace('\n', ' ') for cell in cells) + ' |\n')
print(f'Staged {target}')
PY
    then
        echo "FAILED $plugin: artifact validation/staging failed"
        notices+=("$plugin: artifact validation/staging failed; see terminal output"); failed=1
    fi
done
if ! $dry_run && ((${#notices[@]})); then
    printf '\n## Skipped or failed\n\n' >> "$destination/MANIFEST.md"
    printf -- '- %s\n' "${notices[@]}" >> "$destination/MANIFEST.md"
fi
if $dry_run; then exit 0; fi
echo "Manifest: $destination/MANIFEST.md"
exit "$failed"
