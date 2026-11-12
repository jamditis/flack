# Milestones and issue briefs

The identifiers below are planning labels, not published GitHub issue numbers. Work is ordered to test feasibility before investing in a complete application.

## M0: Choose the route

### P01 — Record native behavior on both target phones

Run G0 from TEST_PLAN.md and record exact versions, connection mode, fresh-message behavior, confirmation/cancel, and locked-phone behavior. Separate phone-only success from Android Auto success.

**Done:** A reproducible pass, a specific failure after setup checks, or a named remaining unknown. If the native route meets the need, stop custom work unless the maintainer requests a concrete additional benefit. An unknown result remains pending.

## M1: Prove a direct-message reply

### P02 — Inspect Slack notification capabilities

Build a small local diagnostic instrument using the documented notification listener. Identify supported structures and genuine inline actions. Keep private text out of output. Exercise notification-shade replies first.

**Done:** Synthetic fixtures and a capability table for DM, group DM, mention, and thread notifications. Unsupported cases are explicit.

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
