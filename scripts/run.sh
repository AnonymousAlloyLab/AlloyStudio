#!/usr/bin/env bash
set -euo pipefail
case "$(uname -s)" in
  MSYS*|MINGW*|CYGWIN*)
    cd "$(dirname "$0")/.."
    ./scripts/build.sh
    exec python3 server.py "$@"
    ;;
esac
exec "$(dirname "${BASH_SOURCE[0]}")/local.sh" run "$@"
