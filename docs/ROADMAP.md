# Milestones and issue briefs

## Notification capabilities

The implementation supports all text notification types below. Invented fixtures do not establish real Slack compatibility. Source/profile validation, creator package/UID checks, one free-form result key, a mutable background action, generation validation and one-dispatch tokens apply to every reply.

| Notification | Readout copy | Voice reply rule | Evidence |
| --- | --- | --- | --- |
| One-to-one DM | Yes | Explicit non-group MessagingStyle with a title or incoming sender; one original action | Synthetic parser/routing tests; device not yet tested |
| Public channel, mention or other subscribed message | Yes | Explicit group MessagingStyle with a destination title; one original action | Synthetic fixtures/emulator check; device not yet tested |
| Private channel | Yes | Same titled-group rule; never derive identifiers from the title | Synthetic fixtures/emulator check; device not yet tested |
| Group DM | Yes | Same titled-group rule; preserve the original action | Synthetic fixtures/emulator check; device not yet tested |
| Thread reply | Yes | One original thread action, isolated from other source keys/actions; never substitute a channel action | Two synthetic threads/emulator check; device not yet tested |
| One-to-one DM with several text actions and exactly one background Reply action carrying the REPLY semantic | Yes | Uses that original action after creator, mutability and input-key checks | Synthetic selection and dispatch tests; real Slack action shapes and car test pending |
| Ambiguous text actions, multiple keys, missing group title, unknown flag, plain message-category title/text | Phone copy, with text and context; Android Auto can hide it because reply is missing | No reply action; reason counted in diagnostics | Synthetic read-only/lifecycle tests; first car test reported no car entry |
| Shared action across notifications, including suppressed sources | Yes for readable children | Disable every conflicting reply; old tokens expire | Synthetic collision/held-action tests |
| Group summary, empty or context-free notification, other app/profile, plain non-message or ongoing/service notification | No | No reply action | Source, status-filter and duplicate-suppression checks |

The emulator check runs on GitHub-hosted CI. It exercises Android PendingIntents but has neither real Slack nor Android Auto. Phone/car results remain in [DEVICE_TEST_RECORD.md](DEVICE_TEST_RECORD.md).

The identifiers below are planning labels, not published GitHub issue numbers. Work is ordered to test feasibility before investing in a complete application.

## M0: Choose the route

### P01 — Record native behavior on both target phones

Run G0 from TEST_PLAN.md and record exact versions, connection mode, fresh-message behavior, confirmation/cancel, and locked-phone behavior. Separate phone-only success from Android Auto success.

**Done:** A reproducible pass, a specific failure after setup checks, or a named remaining unknown. If the native route meets the need, stop custom work unless the maintainer requests a concrete additional benefit. An unknown result remains pending.

## M1: Prove a direct-message reply

### P02 — Inspect Slack notification capabilities

Build a small local diagnostic instrument using the documented notification listener. Identify supported structures and genuine inline actions. Keep private text out of output. Exercise notification-shade replies first.

**Implementation:** Synthetic fixtures and per-type rules cover DMs, group DMs, public/private channels, threads, and read-only ambiguous notifications (#19). The table above describes the rules. Real source inspection is pending.

### P03 — Complete one Android Auto DM round trip

Create the minimum message wrapper and reply receiver. Use Android Auto's native interaction, validate the original action, and return confirmed text through it.

**Depends on:** P02.

**Done:** G2 and G4 pass on each real phone/car combination, including cancellation, lock screen, accurate local status, and R35–R36 input/capability checks. Document the host's own success/error wording.

## M2: Make routing reliable

### P04 — Bind reply tokens and handle concurrent updates

Implement the session registry, source-generation validation, PendingIntent collision checks, one-dispatch token consumption, group-summary suppression, and deterministic message updates.

**Depends on:** P03.

**Done:** Routing tests R04–R17 and R32–R34 pass, with no misroutes or duplicate dispatches. Prove supported thread behavior separately. Do not claim exactly-once server delivery.

### P05 — Handle car acknowledgement and stale state

Keep local acknowledgement separate from Slack read state. Add source removal, permission, process, pause, and disconnect behavior. Prevent old notifications and old callbacks from becoming new sends after recovery.

**Depends on:** P04.

**Done:** R12–R15, R23–R27, and R31 pass. No automatic retry after an uncertain handoff.

## M3: Package a usable pilot

### P06 — Add phone setup and compatibility status

Build a concise, accessible setup/status interface with permission explanations, pause, clear-session, supported categories, and actionable failure text. Select car-session detection based on a documented API and measured behavior.

**Done:** A new tester can install, grant access, run a synthetic test, see whether forwarding is active, and revoke/pause it without developer tools after setup.

### P07 — Validate privacy and endurance

Inspect the merged manifest/dependencies, persistent storage, logs, backups, and diagnostic export. Run the three-hour test under recorded conditions on each target phone. Test Samsung lifecycle behavior and normal recovery.

**Done:** The privacy checks and all relevant lifecycle cases pass. Resource measurements are reported with their baseline and power conditions.

### P08 — Publish reproducible pilot instructions and release evidence

Document build versions, official Android Auto development installation, supported combinations, limitations, known failure behavior, and original-code licensing. Add CI for meaningful static/unit/instrumentation checks that the environment can actually execute.

**Done:** Another developer can reproduce the build and the test procedure. A release APK is signed through a controlled process; its checksum and test status are documented. Public Play distribution remains a separate decision.

## Pull request practice

Keep one reviewable change per issue where practical. Stack dependent PRs when useful and state the dependency. Mark PRs ready for review; do not open drafts. Never merge without explicit approval.

Begin with P01–P03. A polished settings interface is not evidence that reply routing works, so it must not delay the end-to-end feasibility result.
