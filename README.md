<picture>
  <source media="(prefers-color-scheme: dark)" srcset="brand/readme-banner.svg">
  <img src="brand/readme-banner-light.svg" alt="Flack. Your messages, riding shotgun. Independent app, not affiliated with Slack." width="800">
</picture>

[![CI](https://github.com/jamditis/flack/actions/workflows/ci.yml/badge.svg)](https://github.com/jamditis/flack/actions/workflows/ci.yml)
[![Latest release](https://img.shields.io/github/v/release/jamditis/flack?label=release)](https://github.com/jamditis/flack/releases/latest)
[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)

Flack copies new Slack messages into the format Android Auto reads aloud: DMs, group DMs, public and private channels, and thread replies. When a notification provides one safe reply action, you can dictate and confirm a reply. Flack hands your words to that original Slack action, once.

**Status:** experimental support for these notification types, with synthetic routing tests. Real Slack and car behavior, including readout without a reply action, still need device tests. See the [device test record](docs/DEVICE_TEST_RECORD.md).

## Download

On your Android phone, open **[flack.apk](https://github.com/jamditis/flack/releases/latest/download/flack.apk)**. This link always gets the newest release. Then follow the [install steps](docs/INSTALL.md), or use the [download page](https://jamditis.github.io/flack/).

Flack is not on Google Play. You install it yourself, and you turn on Android Auto's "Unknown sources" developer setting so it shows Flack's messages.

<p>
  <img src="docs/screenshots/setup-dark.png" alt="Flack setup screen in dark mode, with five setup steps" width="260">
  <img src="docs/screenshots/setup-light.png" alt="Flack setup screen in light mode, with five setup steps" width="260">
</p>

## How it works

1. Flack reads notifications from the official Slack app only. It ignores every other app before reading anything.
2. While Android Auto is connected, it posts a readable copy of each eligible text notification. Group summaries are suppressed to avoid duplicate announcements.
3. When you confirm a reply in the car, Flack checks a one-time token and passes your text to Slack's reply action. Slack sends the message.

If Flack cannot tell where a reply would go, it keeps a read-only phone copy.
Android Auto requires both reply and mark-as-read actions, so it may hide these
read-only copies. For a one-to-one DM with several text actions, Flack accepts only one background
action labeled Reply with the REPLY semantic action. Ambiguous direct choices,
unknown conversation shape, missing group context, or a shared source action
disable replying. Flack never chooses a destination from a sender or channel
name. The [capability table](docs/ROADMAP.md#notification-capabilities) records
the rules and evidence limits.

## Privacy

- No internet permission. Flack cannot send anything anywhere.
- No Slack login or token. The Slack app does the sending.
- Message text stays in memory and is never saved, backed up or logged.
- The only permission is posting notifications. CI fails any build that asks for more.

The full [privacy page](https://jamditis.github.io/flack/privacy.html) covers what Flack reads, keeps and sends.

## Supported phones

The intended test phones are a Samsung Galaxy S24 Ultra and a Samsung Galaxy S21. Neither has been tested yet; results and exact versions go in the [device test record](docs/DEVICE_TEST_RECORD.md). Flack needs Android 12 or later.

## Documentation

- [Install and test steps](docs/INSTALL.md)
- [Specification](SPEC.md) and [project rules](AGENTS.md)
- [Test plan](docs/TEST_PLAN.md) and [device test record](docs/DEVICE_TEST_RECORD.md)
- [Roadmap](docs/ROADMAP.md)
- [Brand guide](docs/BRAND.md), [design rules](docs/DESIGN_RULES.md) and [writing rules](docs/WRITING_RULES.md)
- [Releasing](docs/RELEASING.md) and [changelog](CHANGELOG.md)
- [Contributing](CONTRIBUTING.md) and [security policy](SECURITY.md)

## Build

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Needs JDK 21 and the Android SDK. See [CONTRIBUTING.md](CONTRIBUTING.md) for the full checks.

## License

Apache-2.0 for original work. See [LICENSE](LICENSE) and [NOTICE](NOTICE). The Manrope font in `site/fonts/` is under the SIL Open Font License.

Flack is an independent project. It is not made by, affiliated with or endorsed by Slack, Google or Samsung.
