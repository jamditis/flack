# Changelog

Changes that testers would notice, newest first. Each release on GitHub also lists the merged pull requests.

## Unreleased

## 0.2.1

- The test report includes content-free facts for the last 20 multi-action
  notifications, including cases where a reply can be selected. Unknown labels
  and result keys are omitted to protect private content.
- When Slack provides several text actions, Flack uses the sole background
  action labeled Reply with the REPLY semantic action. Thread, smart-reply and
  unknown labels do not qualify. Duplicate direct choices stay read-only.
- Flack's phone copies are silent, grouped and set to alert only once. Slack's
  original notifications are unchanged.
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
