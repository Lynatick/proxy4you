#!/bin/sh
set -eu
helper_dir="${TARGET_BUILD_DIR}/${CONTENTS_FOLDER_PATH}/MacOS"
mkdir -p "$helper_dir"
set --
for helper_arch in ${ARCHS}; do
  helper_binary="${DERIVED_FILE_DIR}/Proxy4YouProxyHelper-${helper_arch}"
  /usr/bin/xcrun swiftc -O -target "${helper_arch}-apple-macos12.0" \
    -module-cache-path "${DERIVED_FILE_DIR}/ProxyHelperModules" \
    "${PROJECT_DIR}/ProxyHelper/main.swift" -o "$helper_binary"
  set -- "$@" "$helper_binary"
done
/usr/bin/lipo -create "$@" -output "$helper_dir/Proxy4YouProxyHelper"
if [ "${CODE_SIGNING_ALLOWED:-NO}" = YES ]; then
  /usr/bin/codesign --force --sign "${EXPANDED_CODE_SIGN_IDENTITY:--}" "$helper_dir/Proxy4YouProxyHelper"
fi
