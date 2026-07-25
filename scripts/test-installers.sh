#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
test_root=$(mktemp -d)
trap 'rm -rf "$test_root"' EXIT

mock_bin="$test_root/bin"
mkdir -p "$mock_bin"

cat > "$mock_bin/curl" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail

output=""
url=""
while (($# > 0)); do
  case "$1" in
    -o)
      output="$2"
      shift 2
      ;;
    http://*|https://*)
      url="$1"
      shift
      ;;
    *)
      shift
      ;;
  esac
done

printf '%s\n' "$url" >> "$MOCK_CURL_LOG"
case "$url" in
  */releases/latest)
    printf 'https://github.com/Su-cyber-art/flux-panel/releases/tag/%s' \
      "${MOCK_LATEST_TAG:-9.8.7}"
    ;;
  *ipinfo.io/country)
    printf 'US\n'
    ;;
  */gost-amd64|*/gost-arm64)
    {
      printf '%s\n' '#!/usr/bin/env bash'
      printf 'printf '"'"'%%s\\n'"'"' '"'"'gost %s (test)'"'"'\n' \
        "${MOCK_BINARY_VERSION:-9.8.7}"
    } > "$output"
    ;;
  *)
    echo "unexpected curl URL: $url" >&2
    exit 1
    ;;
esac
MOCK

cat > "$mock_bin/systemctl" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail

case "${1:-}" in
  cat|stop|disable|enable|daemon-reload)
    exit 0
    ;;
  start|is-active)
    if [[ "${MOCK_FAIL_NEW_START:-0}" == "1" ]] \
        && "$GOST_INSTALL_DIR/gost" -V | grep -q "gost ${MOCK_BINARY_VERSION} "; then
      exit 1
    fi
    exit 0
    ;;
  *)
    echo "unexpected systemctl command: $*" >&2
    exit 1
    ;;
esac
MOCK

cat > "$mock_bin/tcpkill" <<'MOCK'
#!/usr/bin/env bash
exit 0
MOCK

cat > "$mock_bin/sleep" <<'MOCK'
#!/usr/bin/env bash
exit 0
MOCK

chmod +x "$mock_bin"/*

write_gost() {
  local path="$1"
  local version="$2"

  {
    printf '%s\n' '#!/usr/bin/env bash'
    printf 'printf '"'"'%%s\\n'"'"' '"'"'gost %s (test)'"'"'\n' "$version"
  } > "$path"
  chmod +x "$path"
}

run_env=(
  "PATH=$mock_bin:$PATH"
  "MOCK_CURL_LOG=$test_root/curl.log"
)

resolve_script="$test_root/install-resolve.sh"
cp "$repo_root/install.sh" "$resolve_script"
: > "$test_root/curl.log"
resolve_output=$(env "${run_env[@]}" MOCK_LATEST_TAG=9.8.7 \
  bash "$resolve_script" --help)
grep -q '9.8.7' <<< "$resolve_output"
grep -q '/releases/latest' "$test_root/curl.log"

injected_script="$test_root/install-injected.sh"
sed 's/__FLUX_VERSION__/7.6.5/g' "$repo_root/install.sh" > "$injected_script"
: > "$test_root/curl.log"
env "${run_env[@]}" bash "$injected_script" --help >/dev/null
if grep -q '/releases/latest' "$test_root/curl.log"; then
  echo "release-injected installer unexpectedly queried the latest release" >&2
  exit 1
fi

panel_script="$test_root/panel-resolve.sh"
cp "$repo_root/panel_install.sh" "$panel_script"
: > "$test_root/curl.log"
panel_output=$(printf '5\n' | env "${run_env[@]}" MOCK_LATEST_TAG=9.8.7 \
  bash "$panel_script")
grep -q '9.8.7' <<< "$panel_output"
[[ ! -e "$panel_script" ]]

success_dir="$test_root/gost-success"
mkdir -p "$success_dir"
write_gost "$success_dir/gost" 1.0.0
success_script="$test_root/install-update-success.sh"
cp "$repo_root/install.sh" "$success_script"
: > "$test_root/curl.log"
env "${run_env[@]}" \
  FLUX_VERSION=9.8.7 \
  GOST_INSTALL_DIR="$success_dir" \
  MOCK_BINARY_VERSION=9.8.7 \
  bash "$success_script" --update >/dev/null
[[ ! -e "$success_script" ]]
grep -q 'gost 9.8.7 ' < <("$success_dir/gost" -V)
[[ ! -e "$success_dir/gost.backup" ]]

rollback_dir="$test_root/gost-rollback"
mkdir -p "$rollback_dir"
write_gost "$rollback_dir/gost" 1.0.0
rollback_script="$test_root/install-update-rollback.sh"
cp "$repo_root/install.sh" "$rollback_script"
: > "$test_root/curl.log"
if env "${run_env[@]}" \
    FLUX_VERSION=9.8.7 \
    GOST_INSTALL_DIR="$rollback_dir" \
    MOCK_BINARY_VERSION=9.8.7 \
    MOCK_FAIL_NEW_START=1 \
    bash "$rollback_script" --update >/dev/null 2>&1; then
  echo "installer unexpectedly succeeded after the new service failed" >&2
  exit 1
fi
[[ -e "$rollback_script" ]]
grep -q 'gost 1.0.0 ' < <("$rollback_dir/gost" -V)
[[ ! -e "$rollback_dir/gost.backup" ]]

bash -n "$repo_root/install.sh"
bash -n "$repo_root/panel_install.sh"

echo "installer tests passed"
