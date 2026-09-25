#!/usr/bin/env bash
set -euo pipefail
ENGINE_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd -- "$ENGINE_ROOT/.." && pwd)"
ACGN_ROOT="${ACGN_ROOT:-$PROJECT_ROOT/vendor/acgn}"
OUTPUT_DIR="${1:-$ENGINE_ROOT/build/classes}"
mkdir -p -- "$OUTPUT_DIR"
javac -encoding UTF-8 --release 17 -Xprefer:source -cp "$ACGN_ROOT/lib/*" \
  -sourcepath "$ENGINE_ROOT/src:$ACGN_ROOT/src" -d "$OUTPUT_DIR" \
  "$ENGINE_ROOT/src/live/LiveFeedback.java" "$ENGINE_ROOT/src/live/EngineSelfTest.java"
printf 'Built engine classes in %s\n' "$OUTPUT_DIR"
