# Security policy

## Reporting a problem

Report security or privacy problems privately through GitHub's
[private vulnerability reporting](https://github.com/jamditis/flack/security/advisories/new).
Do not open a public issue for them.

Problems that count:

- A reply that could reach the wrong Slack conversation, or be sent twice.
- A cancel in the car that still sends a message.
- Message content written to disk, logs, backups or diagnostics.
- A permission or network access the app should not have.

Use invented names and messages in any report. Do not send real Slack content, workspace IDs, tokens or notification dumps.

## Supported versions

Only the newest release gets fixes. This project is an experiment and has no stable release yet.

## What the app does not have

The app has no network permission, no Slack login or token, and no analytics. CI fails a build that asks for a permission outside the allowlist in `scripts/check-apk-permissions.sh`.
