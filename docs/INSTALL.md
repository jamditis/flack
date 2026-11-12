# Install and test the experiment build

This build is for the G2 test in [TEST_PLAN.md](TEST_PLAN.md). It is not device tested yet. Do these steps on each test phone (the Galaxy S24 Ultra and the Galaxy S21) while parked.

## Get the APK

1. Open the repository's Releases page and download `slack-car-bridge-<version>-debug.apk`. Each pull request's CI run also has the APK under "Artifacts".
2. Optional: check the file against the `.sha256` file on the same release.

Debug builds are signed with a different key each time. Uninstall the old version before you install a new one.

## Install it on the phone

1. Open the APK from the Downloads app.
2. If Android asks, allow your browser or file app to install unknown apps, then go back and install.
3. Samsung may show a security scan prompt. Read it, then install.

## Set it up

1. Open **Slack car bridge**.
2. Tap **Allow this app to post notifications** and allow it.
3. Tap **Open notification access settings**, choose **Slack car bridge** and turn it on. Android warns that the app can read notifications. That is how it sees Slack messages. The app has no internet permission, so it cannot send them anywhere.
4. Turn on **Forward Slack direct messages to Android Auto**.
5. In Settings → Apps → Slack car bridge → Battery, choose **Unrestricted**, so Samsung does not put the app to sleep.

## Set up Android Auto

1. Open Android Auto settings on the phone (Settings → Connected devices → Android Auto).
2. Scroll down and tap **Version** about 10 times, then accept, to turn on developer mode.
3. Open the menu (three dots) → **Developer settings** and turn on **Unknown sources**.

Without this, Android Auto hides notifications from apps not installed from Google Play.

## Test one DM

Follow G2 in [TEST_PLAN.md](TEST_PLAN.md). In short:

1. Connect to the car. Ask someone to send a test DM with made-up text.
2. Listen to it, reply by voice with a unique phrase, review it and confirm.
3. Check the Slack DM has that phrase exactly once.
4. Repeat with the phone locked.
5. Start a reply and cancel it. Check nothing was sent.

If no DM reaches the car and the status screen counts every DM under `NOT_A_DIRECT_MESSAGE`, Slack does not mark its DMs as one-to-one conversations. The app refuses them on purpose rather than guess. Record that in the device test record; it is a G1 result.

Open the app afterward. The status screen shows counts only, such as how many notifications were forwarded and why others were not. Copy those counts and the exact versions into [DEVICE_TEST_RECORD.md](DEVICE_TEST_RECORD.md). Do not copy message text.

## Turn it off

Turn the switch off in the app, or remove its notification access. Both remove every car copy at once.
