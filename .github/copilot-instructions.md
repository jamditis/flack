# Copilot review context for Flack

## What this is

An Android app (Kotlin, sideloaded APK) that copies supported Slack direct-message notifications into Android Auto's messaging format, then passes a confirmed voice reply back through Slack's own notification reply action. It has no network permission and no Slack login. See SPEC.md and AGENTS.md.

## Architecture

- `core/` is plain Kotlin with no Android imports, so the rules run as JVM unit tests.
  - `CapabilityCheck` decides if a Slack notification can take a car reply. Unknown shapes fail closed.
  - `SessionRegistry` holds in-memory reply bindings: one single-use token per content generation.
- `SlackNotificationParser` turns a `StatusBarNotification` into core facts. It runs only after the package check.
- `SlackListenerService` keeps car copies in step with Slack notifications.
- `CarNotificationPublisher` posts the MessagingStyle car notification.
- `CarActionReceiver` consumes a token and calls Slack's original PendingIntent once.

## Key rules to check in review

- **Routing:** a reply goes only to the PendingIntent bound to its token. Never pick a destination by name, by "latest conversation" or by guessing.
- **Shared actions:** the registry records the actions of every live Slack notification, forwarded or not. A DM whose action appears on any other notification is withheld until a source goes away.
- **Once only:** a token dispatches at most once. Never retry an uncertain dispatch.
- **Honest status:** a successful `send()` means "handed to Slack", never "delivered".
- **Fail closed:** new or unknown notification shapes are rejected, not guessed.
- **Privacy:** no message text in logs, disk, backups or diagnostics. No new permissions. `scripts/check-apk-permissions.sh` holds the allowlist.
- **Public repo:** tests and docs use invented people and messages only.

## Common issues

- `NotificationCompat` reads `showsUserInterface` as true when the extra is missing; the parser uses `PendingIntent.isActivity` instead.
- MessagingStyle marks the phone user's own messages with a null sender.
- A PendingIntent is not a frozen snapshot; equal handles on two conversations make both ambiguous.
