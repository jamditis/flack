# Project instructions

## Scope

This repository plans an Android notification companion for Slack and Android Auto. Read SPEC.md and the current device test record before implementing or claiming compatibility.

Begin with the feasibility gates. Do not build a complete Slack client, cloud relay, accessibility scraper, modified Slack APK, or custom always-listening assistant as an unnoticed scope expansion.

## Correctness

Preserve the exact original reply capability and validate its source and generation. Never choose a recipient by display name, guessed Slack identifier, or whichever conversation arrived last. A captured PendingIntent is not proof of frozen routing.

Keep car acknowledgement separate from Slack read state. Do not delete the source notification before a reply. Never label local Android invocation as confirmed Slack delivery. Do not automatically retry an ambiguous dispatch.

Any misrouted reply, duplicate dispatch, cancel-that-sends, or content leak blocks release. Exclude unsupported message types explicitly.

## Privacy and public work

Use synthetic messages, invented people, and synthetic destinations in tests, screenshots, logs, issues, and documentation. Do not commit real Slack content, workspace/account identifiers, tokens, notification dumps, device captures, signing keys, or local configuration.

The release companion should have no network, microphone, contacts, SMS, location, or accessibility permission unless a separately reviewed design change establishes a need. Inspect the merged manifest and dependencies. Keep content and action state in memory; keep diagnostics content-free.

Implement original code from documented APIs. Do not copy noncommercial-licensed forwarder code into a permissively licensed repository.

## Development and review

Open an issue for substantive work. Use reviewable pull requests and stack them where appropriate. Mark PRs ready for review; do not open drafts. Never merge without explicit approval.

Use meaningful tests for routing, action consumption, lifecycle failures, and privacy boundaries. A build or emulator pass does not replace the physical phone/car tests on both target phones. Report precisely what was tested, what failed, and what remains unverified.

Use sentence case headings, clear labels, and plain language. Preserve keyboard and accessibility support. Avoid unnecessary motion and decorative UI that delays the core feasibility result.
