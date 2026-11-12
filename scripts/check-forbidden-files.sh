#!/usr/bin/env bash
# Fails when tracked files include signing keys, local config, device captures
# or anything that looks like a Slack token. This is a public repository.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

bad=0
name_pattern='(\.(jks|keystore|p12|pfx|pem|key|hprof)$|(^|/)local\.properties$|(^|/)keystore\.properties$|(^|/)\.env(\.|$)|(^|/)(notification-dumps|device-captures|private-fixtures)/|(^|/)google-services\.json$)'
if git ls-files | grep -E "$name_pattern" | grep -v '\.example$'; then
  echo "These tracked files must not be in a public repository."
  bad=1
fi

# Slack tokens (xoxb-, xoxp-, xoxs-, xapp-) and private key blocks.
token_pattern='xox[abposr]-[0-9A-Za-z-]{10,}|xapp-[0-9]-[0-9A-Za-z-]{10,}|-----BEGIN [A-Z ]*PRIVATE KEY-----'
if git grep -I -n -E "$token_pattern" -- . ':!scripts/check-forbidden-files.sh'; then
  echo "Possible secret found in a tracked file."
  bad=1
fi

(( bad )) && exit 1
echo "No forbidden files or secrets found."
