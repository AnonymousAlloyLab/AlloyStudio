#!/usr/bin/env bash
# Native Windows Java needs Windows paths and semicolon-separated classpaths.
# Translate only the script/output paths here; PowerShell builds the javac argv.
set -euo pipefail
PROJECT_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_MODE="${1:?Expected portal or engine build mode}"
OUTPUT_DIR="${2:?Expected a build output directory}"
case "$BUILD_MODE" in
  portal|engine) ;;
  *) printf '%s\n' 'Expected portal or engine build mode.' >&2; exit 1 ;;
esac
if ! command -v cygpath >/dev/null 2>&1; then
  printf '%s\n' 'Windows Bash builds require cygpath (included in Git Bash/Cygwin). Alternatively run scripts/build.ps1 in PowerShell.' >&2
  exit 1
fi
if command -v powershell.exe >/dev/null 2>&1; then
  WINDOWS_POWERSHELL="$(command -v powershell.exe)"
elif command -v pwsh.exe >/dev/null 2>&1; then
  WINDOWS_POWERSHELL="$(command -v pwsh.exe)"
else
  printf '%s\n' 'Windows PowerShell was not found. Run scripts/build.ps1 from a Windows PowerShell window.' >&2
  exit 1
fi
WINDOWS_BUILD_SCRIPT="$(cygpath -aw "$PROJECT_ROOT/scripts/build.ps1")"
WINDOWS_OUTPUT_DIR="$(cygpath -aw "$OUTPUT_DIR")"
BUILD_ARGUMENTS=(-NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass
  -File "$WINDOWS_BUILD_SCRIPT" -OutputDirectory "$WINDOWS_OUTPUT_DIR")
if [[ -n "${ACGN_ROOT:-}" ]]; then
  WINDOWS_ACGN_ROOT="$(cygpath -aw "$ACGN_ROOT")"
  BUILD_ARGUMENTS+=(-ACGNRoot "$WINDOWS_ACGN_ROOT")
fi
if [[ "$BUILD_MODE" == portal ]]; then
  BUILD_ARGUMENTS+=(-RequireNode)
else
  BUILD_ARGUMENTS+=(-EngineOnly)
fi
printf '%s\n' 'Windows Bash detected: building through scripts/build.ps1 with native Windows dependency paths.'
# These arguments are already Windows paths. Prevent MSYS from converting them
# again while starting the native PowerShell process. Cygwin ignores these vars.
export MSYS2_ARG_CONV_EXCL='*' MSYS_NO_PATHCONV=1
exec "$WINDOWS_POWERSHELL" "${BUILD_ARGUMENTS[@]}"
