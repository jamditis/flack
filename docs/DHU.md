# Testing with the Desktop Head Unit

The Desktop Head Unit (DHU) shows Android Auto on a computer screen, driven by a phone over USB. With the test-only fake Slack app, you can try the whole car flow at a desk without real Slack messages. It does not replace the car test: G2 and G4 in [TEST_PLAN.md](TEST_PLAN.md) still need the real car.

Google's guide: [developer.android.com/training/cars/testing/dhu](https://developer.android.com/training/cars/testing/dhu)

## What you need

- A computer with Android Studio, or the Android SDK command-line tools, and `adb`.
- The phone with USB debugging on (Settings → About phone → Software information → tap Build number seven times, then Developer options → USB debugging).
- Debug builds of Flack and the fake Slack. Build them with `./gradlew :app:assembleDebug :fixture:assembleDebug`, or download both from a CI run's artifacts.

Only debug builds of Flack accept the fake Slack. The released `flack.apk` ignores it.

## Steps

1. Install the DHU: in Android Studio, open the SDK Manager, then SDK Tools, and tick **Android Auto Desktop Head Unit Emulator**.
2. Install both apps on the phone:

   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

   ```bash
   adb install -r fixture/build/outputs/apk/debug/fixture-debug.apk
   ```

3. Open Flack on the phone and finish its setup steps.
4. On the phone, open **Settings → Connected devices → Android Auto**. If Samsung opens **App info**, tap **Configure in Android Auto**. Scroll down and tap the **Version** row 10 times, until it asks to allow development settings, then accept. This is Android Auto's own developer mode, separate from the phone's developer options. In the three-dot menu choose **Start head unit server**. Turn on **Unknown sources** in **Developer settings** if you have not already.
5. On the computer:

   ```bash
   adb forward tcp:5277 tcp:5277
   ```

   Then start `desktop-head-unit` from the SDK's `extras/google/auto` folder.

6. Open **Flack test source** on the phone and tap **Post a DM from Riley Example**. The message should appear in the DHU.
7. Reply by voice. In the DHU console, `mic begin` uses the computer's microphone, or `mic play reply.wav` plays a recording. Confirm the reply.
8. Back in Flack test source, the screen shows the last reply it received. It should match what you said, once.

Repeat with the group DM, public/private channel, and both thread buttons. Each reply must reach only the selected destination. The unknown-routing and two-action buttons should produce read-only copies; verify that the DHU reads them without offering a reply. Record a host that hides those copies as a failure.

## Useful DHU commands

- `restrict all`: simulate driving restrictions. The readout and reply should still work.
- `restrict none`: lift them.
- `day` and `night`: switch the head unit's theme.

## What to record

Write the DHU, Android Auto and phone versions, what the DHU said, and any failure in the [device test record](DEVICE_TEST_RECORD.md). Do not record message text from real conversations.
