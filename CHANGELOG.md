# Changelog

Changes that testers would notice, newest first. Each release on GitHub also lists the merged pull requests.

## Unreleased

First experiment build for one-to-one Slack direct messages. Not yet tested in a car.

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
