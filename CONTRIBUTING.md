# Contributing

Read [AGENTS.md](AGENTS.md) and [SPEC.md](SPEC.md) before you change the design. They hold the rules for routing, privacy and scope.

## Steps for a change

1. Open an issue that says what you want to change and why.
2. Make a branch and keep the change small.
3. Run the checks below. All must pass.
4. Open a pull request that is ready for review, not a draft. Fill in the template.
5. Only the maintainer merges.

## Checks

```bash
bash scripts/check-forbidden-files.sh
./gradlew testDebugUnitTest lintDebug assembleDebug
bash scripts/check-apk-permissions.sh app/build/outputs/apk/debug/app-debug.apk
```

The Gradle build needs JDK 21 and the Android SDK, with `ANDROID_HOME` set or `sdk.dir` in `local.properties`. Never commit `local.properties`.

## This is a public repository

Use invented people, messages and conversation keys in tests, screenshots, logs and issues. Never commit real Slack content, workspace or account IDs, tokens, notification dumps, device captures, signing keys or local configuration. `scripts/check-forbidden-files.sh` catches some of this. It does not catch everything.

## Device results

A build, unit test or emulator pass does not prove the app works in a car. Record real results in [docs/DEVICE_TEST_RECORD.md](docs/DEVICE_TEST_RECORD.md), with exact versions and no private content.
