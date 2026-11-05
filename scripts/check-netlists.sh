#!/usr/bin/env bash
# Runs every *.cir in a directory through desktop ngspice in batch mode and
# fails if any of them does not parse or simulate.
#
#   scripts/check-netlists.sh <dir>
#
# Files are named NAME.<ngbehavior>.cir (e.g. rc.ltpsa.cir); "native" means no
# compatibility mode, matching how the app configures ngspice per dialect.
set -euo pipefail

DIR="${1:?usage: check-netlists.sh <dir>}"
command -v ngspice >/dev/null || { echo "ngspice is not installed" >&2; exit 1; }

HOME_DIR="$(mktemp -d)"
trap 'rm -rf "$HOME_DIR"' EXIT

shopt -s nullglob
files=("$DIR"/*.cir)
[ ${#files[@]} -gt 0 ] || { echo "No netlists in $DIR" >&2; exit 1; }

failed=0
for file in "${files[@]}"; do
    log="$HOME_DIR/$(basename "$file").log"
    behavior="$(basename "$file" .cir)"
    behavior="${behavior##*.}"
    if [ "$behavior" = native ]; then : > "$HOME_DIR/.spiceinit"; else
        printf 'set ngbehavior=%s\n' "$behavior" > "$HOME_DIR/.spiceinit"; fi
    if (cd "$DIR" && HOME="$HOME_DIR" timeout 120 ngspice -b -r "$HOME_DIR/out.raw" "$(basename "$file")") > "$log" 2>&1 \
        && ! grep -qiE '^(error|.*fatal)|circuit not parsed|singular matrix|timestep too small|no such vector' "$log"; then
        echo "ok    $(basename "$file")"
    else
        echo "FAIL  $(basename "$file")"
        sed 's/^/      /' "$log"
        failed=1
    fi
done
exit $failed
