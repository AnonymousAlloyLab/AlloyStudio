#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
case "$(uname -s)" in
  MSYS*|MINGW*|CYGWIN*)
    exec ./scripts/build-windows.sh portal "${1:-build/engine/classes}"
    ;;
esac
if [[ -f exercises/catalogue.json && ! -f exercises/correct-pools.json ]] ||
   [[ ! -f exercises/catalogue.json && -f exercises/correct-pools.json ]]; then
  printf '%s\n' 'The catalogue and correct pools must both exist. Restore the matching bundled exercises/ files from Git. For a custom corpus, restore your matching pair or rebuild with ACGN_ROOT after backing up and moving both files.' >&2
  exit 1
fi
if [[ ! -f exercises/catalogue.json ]]; then
  python3 -E -s scripts/prepare_private_data.py --root "$PWD"
fi
# ACGN_ROOT identifies the original corpus for the portal import. Compilation
# always uses the dependency-closed framework snapshot shipped in this checkout.
ACGN_ROOT="$PWD/vendor/acgn" ./engine/build.sh "${1:-build/engine/classes}"
python3 -c 'import ast; from pathlib import Path; ast.parse(Path("server.py").read_text())'
node --check web/app.js
