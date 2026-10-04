set -euo pipefail
test "$(cat lean-toolchain)" = 'leanprover/lean4:v4.34.1'
test "$(cat formal/lean-toolchain)" = 'leanprover/lean4:v4.34.1'
alloy_lean_archive="$RUNNER_TEMP/alloy-lean-4.34.1-linux.tar.zst"
alloy_lean_root="$RUNNER_TEMP/alloy-elan"
alloy_lean_dir="$alloy_lean_root/toolchains/leanprover--lean4---v4.34.1"
curl --fail --location --retry 3 --proto '=https' --tlsv1.2 \
  'https://github.com/leanprover/lean4/releases/download/v4.34.1/lean-4.34.1-linux.tar.zst' \
  --output "$alloy_lean_archive"
printf '%s  %s\n' '47bf4bbd78f70c2e9670598ab7124d92b6efb7330ff33e5fbb4030f6fd72e4e4' "$alloy_lean_archive" | sha256sum --check -
mkdir -p "$alloy_lean_dir"
tar --zstd --extract --file "$alloy_lean_archive" --strip-components=1 --directory "$alloy_lean_dir"
printf 'ELAN_HOME=%s\n' "$alloy_lean_root" >> "$GITHUB_ENV"
ELAN_HOME="$alloy_lean_root" python scripts/lean_offline.py --cwd . -- --version
