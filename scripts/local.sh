#!/usr/bin/env bash
# Compatible with the Bash 3.2 shipped by macOS. Keep the caller's directory.
set -euo pipefail
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
python_exe="${ALLOY_PYTHON:-}"
if [[ -z "$python_exe" ]]; then
  for candidate in python3 python; do
    if command -v "$candidate" >/dev/null 2>&1 &&
       "$candidate" -E -s -c 'import sys; sys.exit(sys.version_info < (3, 10))' >/dev/null 2>&1; then
      python_exe="$candidate"
      break
    fi
  done
fi
if [[ -z "$python_exe" ]] ||
   ! "$python_exe" -E -s -c 'import sys; sys.exit(sys.version_info < (3, 10))' >/dev/null 2>&1; then
  printf '%s\n' 'Python 3.10+ is required. Install it or set ALLOY_PYTHON to its executable. See docs/local-setup.md.' >&2
  exit 1
fi
exec "$python_exe" -E -s "$script_dir/local_portal.py" "$@"
