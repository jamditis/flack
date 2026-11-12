# CLAUDE.md

Read [AGENTS.md](AGENTS.md) first. Its rules on routing, privacy and scope are hard constraints. This is a public repository: invented people and messages only.

## Build and test

```bash
bash scripts/check-forbidden-files.sh
./gradlew testDebugUnitTest lintDebug assembleDebug
bash scripts/check-apk-permissions.sh app/build/outputs/apk/debug/app-debug.apk
```

Needs JDK 21 and the Android SDK (`ANDROID_HOME`). Lint treats warnings as errors.

## Files

```
SPEC.md, AGENTS.md          # Design and project rules
docs/TEST_PLAN.md           # Feasibility gates G0-G5 and test matrix
docs/DEVICE_TEST_RECORD.md  # Real phone/car results, one column per phone
docs/INSTALL.md             # Sideload and test steps for testers
docs/DESIGN_RULES.md        # Design dos and don'ts for every visual surface (check before review)
docs/WRITING_RULES.md       # Words, phrases and tics to avoid in all text, commits and PRs
docs/ROADMAP.md             # Milestones P01-P08 (GitHub issues #1-#3 so far)
docs/SOURCES.md, HANDOFF.md # Evidence limits and handoff notes
app/src/main/java/tech/amditis/flack/
  core/SourceFacts.kt       # Plain data the rules read (no Android imports)
  core/CapabilityCheck.kt   # Can this Slack notification take a car reply? Fails closed
  core/SessionRegistry.kt   # In-memory single-use reply tokens per content generation
  SlackNotificationParser.kt # StatusBarNotification -> SourceFacts and PendingIntents
  SlackListenerService.kt   # Notification listener; keeps car copies in step
  CarNotificationPublisher.kt # Posts the Android Auto MessagingStyle notification
  CarActionReceiver.kt      # Car reply and mark-as-read; calls Slack's action once
  BridgeState.kt            # Process state, on/off switch, content-free counts
  StatusActivity.kt         # Phone setup and status screen
app/src/test/.../core/      # JVM unit tests for the core rules
app/src/test/.../flack/     # Robolectric tests: parser, car path end to end (SyntheticSlack
                            # builds invented Slack-shaped notifications), screenshots
scripts/                    # Forbidden-file and APK permission checks (run in CI)
.github/workflows/          # ci (required check), codeql, release (tag -> APK prerelease)
.github/rulesets/main.json  # Branch ruleset to import in repository settings
```

## Versions

AGP 9.5.1 (built-in Kotlin), Gradle 9.9.1, compileSdk 37 (AndroidX core 1.19 needs it), targetSdk 36, minSdk 31. Raise targetSdk only after reading that Android version's behavior changes and retesting on both phones.
