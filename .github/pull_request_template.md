## What and why

<!-- What this changes, and the reason. Link the issue: "Closes #N". -->

## How it was tested

- [ ] `bash scripts/check-forbidden-files.sh`
- [ ] `./gradlew testDebugUnitTest lintDebug assembleDebug`
- [ ] `bash scripts/check-apk-permissions.sh app/build/outputs/apk/debug/app-debug.apk`
- [ ] On a real phone and car (say which phone, versions and connection), or "not device tested"

## Safety checklist

- [ ] No real Slack content, names, IDs, tokens, captures or keys in code, tests, logs or this PR.
- [ ] No new permission, network access or persistent storage of message content.
- [ ] A reply can still go only to the conversation it was bound to, once.
- [ ] Docs and the device test record match the change.
