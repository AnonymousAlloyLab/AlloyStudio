#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
case "$(uname -s)" in
  MSYS*|MINGW*|CYGWIN*)
    exec ./scripts/build-windows.sh portal "${1:-build/engine/classes}"
    ;;
esac
python3 -E -s scripts/prepare_private_data.py --root "$PWD"
python3 -c 'import ast; from pathlib import Path; ast.parse(Path("server.py").read_text())'
node --check web/app.js
node --check web/instance-graph.js
node --check web/admin/app.js
# Packaging compiles the vendored engine into a clean directory first. A failed
# validation or compilation stops instead of reusing an older IIS archive.
python3 -E -s scripts/package_iis.py --source "$PWD" --classes-output "${1:-build/engine/classes}"
