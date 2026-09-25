#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
case "$(uname -s)" in
  MSYS*|MINGW*|CYGWIN*)
    exec ./scripts/build-windows.sh portal "${1:-build/engine/classes}"
    ;;
esac
./engine/build.sh "${1:-build/engine/classes}"
python3 -c 'import ast; from pathlib import Path; ast.parse(Path("server.py").read_text())'
node --check web/app.js
