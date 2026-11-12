# Repository setup and implementation handoff

## Intended destination

The public repository jamditis/flack was created on November 12, 2026, after the maintainer reported that the native route fails: Gemini reads Slack notifications in the car but offers no voice reply. That report has no recorded versions yet; see the device test record.

The custom route is selected on that basis. If a recorded repeat of the native test passes on both target phones, stop this implementation path unless the maintainer requests a separate benefit.

## Initial repository contents

Publish this original planning material as the initial documentation. Add the standard Apache-2.0 license if that proposed license is retained, an appropriate copyright notice, and contribution/security reporting instructions. Do not add third-party app source or imply endorsement by Slack, Google, or Samsung.

Use the issue briefs in ROADMAP.md to create the initial backlog. The planning labels P01–P08 are not existing GitHub issue numbers. Start with the native test record, source capability experiment, and one-DM Android Auto round trip.

## Coding-agent instructions

Read README.md, SPEC.md, AGENTS.md, TEST_PLAN.md, and the source limits before changing the design. Confirm the actual phone's Android/One UI version and personal/work profile from the test record; do not infer them from a model name or from "kept up to date".

Choose and pin compatible stable Kotlin, Android Gradle Plugin, AndroidX, and SDK versions using current primary documentation. Start with a minimal Android project and a synthetic test source, keeping fixture-only capabilities out of the release build.

Before building a settings-heavy product, deliver a small experiment that proves the installed Slack app's original reply action can be invoked through the Android Auto flow. Publish only synthetic evidence. If a message type fails, keep it disabled and state why.

Use separate issues and ready-for-review pull requests. Never merge without explicit approval. A failed real-device test is a legitimate project result; document it instead of concealing it behind a successful build.

## Questions answered by tests

- Does the native Gemini path satisfy the car workflow?
- Which exact Slack notification types expose safe background replies?
- Can the driver distinguish destinations and cancel or correct dictation?
- Does the original action retain its intended route during updates?
- What does the host announce when handoff fails or the network is unavailable?
- Does the companion survive each target phone's normal screen-off and reconnect behavior?

These are validation tasks, not reasons to invent missing capabilities or collect private notification logs.
