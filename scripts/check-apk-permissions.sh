#!/usr/bin/env bash
# Fails when the built APK asks for any permission outside the allowlist.
# SPEC.md section 10: no network, microphone, contacts, SMS, location or
# accessibility. Adding a permission here needs a reviewed design change.
set -euo pipefail

apk=${1:?usage: check-apk-permissions.sh path/to/app.apk}
sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:?set ANDROID_HOME}}
aapt2=$(find "$sdk/build-tools" -mindepth 2 -maxdepth 2 -name aapt2 | sort -V | tail -1)
[[ -x $aapt2 ]] || { echo "aapt2 not found under $sdk/build-tools"; exit 1; }

allowed=(
  "android.permission.POST_NOTIFICATIONS"
  # Added by AndroidX core to protect the app's own dynamic receivers.
  "tech.amditis.slackcarbridge.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
)

dump=$("$aapt2" dump permissions "$apk")
# Both uses-permission and uses-permission-sdk-23 grant permissions.
mapfile -t requested < <(sed -n "s/^uses-permission\(-sdk-23\)\{0,1\}: name='\([^']*\)'.*/\2/p" <<< "$dump")

# If the output format changes and nothing parses, fail rather than pass.
if ! printf '%s\n' "${requested[@]}" | grep -qx 'android.permission.POST_NOTIFICATIONS'; then
  echo "Could not find POST_NOTIFICATIONS in aapt2 output; the check cannot be trusted."
  echo "$dump"
  exit 1
fi

bad=0
for perm in "${requested[@]}"; do
  ok=0
  for allow in "${allowed[@]}"; do
    [[ $perm == "$allow" ]] && ok=1
  done
  if (( ! ok )); then
    echo "Permission not allowed: $perm"
    bad=1
  fi
done
(( bad )) && exit 1
echo "APK permissions OK: ${requested[*]}"
