# Slack car bridge

A proposed Android companion for listening to incoming Slack messages and replying by voice through Android Auto.

The first targets are two phones connected to a car's Android Auto display: a Samsung Galaxy S24 Ultra (primary) and a Samsung Galaxy S21 (second tester). The two phones can run different Android and One UI versions, so each one needs its own test record. The companion would turn eligible Slack notifications into Android Auto message notifications, then return a confirmed voice reply through Slack's existing notification action.

**Status: specification and feasibility plan. There is no application or validated compatibility claim yet.** The native Gemini route remains a device test, and the custom route depends on proving that Slack's reply actions can be forwarded reliably.

## Start here

- [Product and technical specification](SPEC.md)
- [Feasibility tests and acceptance criteria](docs/TEST_PLAN.md)
- [Implementation milestones and issue briefs](docs/ROADMAP.md)
- [Device test record](docs/DEVICE_TEST_RECORD.md)
- [Evidence and source limits](docs/SOURCES.md)
- [Repository setup and coding-agent handoff](docs/HANDOFF.md)

## First decision

Run the short native test in the test plan. If Gemini already reads and replies to Slack through the actual car display with the phone locked, use the native route and stop custom work unless the maintainer requests an additional benefit. An inconclusive test remains pending.

If the native route fails after its permissions and notification settings are checked, start with a small Kotlin experiment that delivers one reply to one synthetic Slack DM. Add message types only after each passes its routing tests.

## Proposed boundaries

- Incoming notifications and their replies; no complete Slack inbox or channel browser.
- Android Auto provides the car interface and voice interaction.
- Slack's installed app provides account access and transmission.
- The companion keeps message content in memory and has no network permission, analytics, or Slack token.
- Original implementation using documented Android interfaces.

The local companion design does not make Slack or Google's voice services offline.

## Project setup

Repository: **jamditis/slack-car-bridge**, public.

Proposed application ID: **tech.amditis.slackcarbridge**.

License: **Apache-2.0** for original project work (see LICENSE and NOTICE). The repository does not include third-party application code.

Distribution: a sideloaded APK attached to GitHub Releases, installed by each tester. There is no Google Play listing. Android Auto's "Unknown sources" developer setting must be on for its notifications to appear in the car.

This is an independent project and is not affiliated with Slack, Google, or Samsung.
