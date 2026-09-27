#!/usr/bin/env bash
set -euo pipefail
ENGINE_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd -- "$ENGINE_ROOT/.." && pwd)"
OUTPUT_DIR="${1:-$PROJECT_ROOT/build/engine/classes}"
case "$(uname -s)" in
  MSYS*|MINGW*|CYGWIN*)
    exec "$PROJECT_ROOT/scripts/build-windows.sh" engine "$OUTPUT_DIR"
    ;;
esac
# Keep explicit relative output paths relative to the caller, as before.
case "$OUTPUT_DIR" in
  /*) ;;
  *) OUTPUT_DIR="$PWD/$OUTPUT_DIR" ;;
esac
python3 -E -s "$PROJECT_ROOT/scripts/build_engine.py" --root "$PROJECT_ROOT" --output "$OUTPUT_DIR"
