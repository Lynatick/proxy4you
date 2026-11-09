#!/bin/bash
set -euo pipefail

task_source="${1:-}"
task_owner="${2:-${SUDO_USER:-}}"
task_destination="/Library/Application Support/proxy4you/AdminBgService/proxy4you.app"

if [[ "$(id -u)" != 0 ]]; then
  echo 'error: run this script with sudo.' >&2
  exit 1
fi
if [[ ! -x "$task_source/Contents/MacOS/proxy4you" ]]; then
  echo 'error: pass the path of proxy4you.app.' >&2
  exit 1
fi

if [[ -x "$task_destination/Contents/MacOS/proxy4you" ]]; then
  "$task_destination/Contents/MacOS/proxy4you" --tunnel uninstall || true
fi
rm -rf "$task_destination"
mkdir -p "$(dirname "$task_destination")"
ditto "$task_source" "$task_destination"
xattr -cr "$task_destination"
chown -R root:wheel "$task_destination"
chmod -R go-w "$task_destination"
export HIDDIFY_TUNNEL_OWNER="$task_owner"
export HIDDIFY_ADMIN_BG_OWNER="$task_owner"
"$task_destination/Contents/MacOS/proxy4you" --tunnel install
echo 'proxy4you VPN service installed.'
