#!/bin/bash
set -euo pipefail

task_destination="/Library/Application Support/proxy4you/AdminBgService/proxy4you.app"
if [[ "$(id -u)" != 0 ]]; then
  echo 'error: run this script with sudo.' >&2
  exit 1
fi
if [[ -x "$task_destination/Contents/MacOS/proxy4you" ]]; then
  "$task_destination/Contents/MacOS/proxy4you" --tunnel uninstall || true
fi
rm -rf "/Library/Application Support/proxy4you/AdminBgService"
rmdir "/Library/Application Support/proxy4you" 2>/dev/null || true
echo 'proxy4you VPN service removed.'
