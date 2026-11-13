# Changelog

Changes that testers would notice, newest first. Each release on GitHub also lists the merged pull requests.

## Unreleased

## 0.2.2

- The test report has a route field probe for the last 20 Slack notifications.
  For each one it lists which notification fields Slack fills, how many values
  in each look like a Slack ID or timestamp, and yes/no relations between
  notifications: whether an ID agrees within one conversation or one sender and
  differs between them, and whether a timestamp matches this message or an
  earlier one. It records no values, names or message text. The results show
  which fields a Slack sign-in reply route could use (docs/SLACK_SIGN_IN.md,
  #29). Nothing else changes: Flack still has no network access.

## 0.2.1

- The test report includes content-free facts for the last 20 multi-action
  notifications, including cases where a reply can be selected. Unknown labels
  and result keys are omitted to protect private content.
- DMs, group DMs, channels and threads use the same inline reply action as the
  phone notification: The single free-form action with the REPLY semantic,
  validated for background execution. Zero or multiple semantic reply actions
  stay read-only. A selected thread action keeps its thread context.
- Flack follows your Slack mobile notification settings. Whatever Slack notifies
  you about on your phone can reach the car. Slack sends channel notifications
  only when your channel settings permit them, such as mentions or All new messages.
- Hide Flack copies on the phone is on by default. It uses a silent channel
  with minimum importance. Android cannot fully hide the copies, and Android
  Auto may hide quiet copies too. If the car stays empty, turn the switch off
  and receive a new Slack message. Switching clears current copies.
- The report names each recent copy's channel and whether its car open action
  ran. Android Auto does not report whether a copy was displayed.
- Alerting mode groups copies and lets new incoming messages alert. Reposts of
  existing content and own replies stay quiet with only-alert-once. Duplicate
  phone alerts remain possible in this mode.
  Slack's original notifications are unchanged. Source removal and car
  disconnection remove Flack's copies and expire their reply tokens.
- Android Auto requires both reply and mark-as-read actions. Read-only copies
  may appear only on the phone. A phone copy count does not prove a car display.
- Android Auto owns the app list and the Flack available notice. This release
  does not change that notice. A physical car test is still required.

## Earlier experiment

First experiment build for one-to-one Slack direct messages.

### What Flack does

- Copies a new Slack direct message into Android Auto's messaging format while Android Auto is connected, so the car can read it aloud.
- Hands a confirmed voice reply to the Slack app's own reply action, once. A reply that cannot be routed safely is not offered.
- Leaves group DMs, channels, threads and messages with more than one reply action alone until each is tested on a real phone.

### Setup and status

- A five-step setup guide on the phone, including help when Android greys out notification access for sideloaded apps.
- A status screen with content-free counts, and a "Copy test report" button for device test results.
- Night-drive theme in light and dark modes, the interchange icon and a themed icon for Android 13 and later.

### Privacy

- No internet permission, no Slack login and no stored message text. Posting notifications is the only permission.

### Releases

- Signed releases from a single link that always gets the newest build, with a checksum, a list of the libraries inside and a GitHub attestation of where it was built.
