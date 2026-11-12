# Install and test the experiment build

This build is for the G2 test in [TEST_PLAN.md](TEST_PLAN.md). It is not device tested yet. Do these steps on each test phone (the Galaxy S24 Ultra and the Galaxy S21) while parked.

## Get the APK

1. On the phone, open this link. It always downloads the newest release:
   https://github.com/jamditis/flack/releases/latest/download/flack.apk
2. Optional: check the file against `flack.apk.sha256` on the same release.

Releases are signed with one key, so a new release installs over the old one. If you installed an earlier debug build, uninstall it once first.

## Install it on the phone

1. Open the APK from the Downloads app.
2. If Android asks, allow your browser or file app to install unknown apps, then go back and install.
3. Samsung may show a security scan prompt. Read it, then install.

## Set it up

1. Open **Flack**.
2. Tap **Allow this app to post notifications** and allow it.
3. Tap **Open settings** under notification access, choose **Flack** and turn it on. If the switch is greyed out, Android is protecting it because the app came from outside Google Play: open App info for Flack, tap the menu (three dots), tap **Allow restricted settings**, then try again. The **Switch greyed out?** button in the app walks through this. Android warns that the app can read notifications. That is how it sees Slack messages. The app has no internet permission, so it cannot send them anywhere.
4. Turn on **Forward Slack direct messages to Android Auto**.
5. In Settings → Apps → Flack → Battery, choose **Unrestricted**, so Samsung does not put the app to sleep.

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
