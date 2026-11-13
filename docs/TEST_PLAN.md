# Feasibility and acceptance tests

These tests determine whether to build the companion and what it may claim to support. They have not been run. All car interactions happen while parked; long-duration checks can use a bench setup.

Record results in DEVICE_TEST_RECORD.md. Use invented test content and a consenting test participant. Do not publish actual work conversations or diagnostic dumps.

For a `MULTIPLE_REPLY_ACTIONS` result, copy the test report from the phone. It
includes the last 20 multi-action cases: static action labels, semantic
actions, known result keys, UI behavior and generated-reply flags. Unknown
labels and result keys are omitted because apps can put private content in
these fields. Message bodies, sender names, source keys and action handles are
never included. The report does not establish which destination an action uses.

Android Auto's [notification messaging contract](https://developer.android.com/training/cars/communication/notification-messaging)
requires both reply and mark-as-read actions. A read-only phone copy has no
reply action and can be absent from the car list. Do not treat the phone's copy
count as proof that Android Auto displayed it. Verify the eligible multi-action
channel case on the car, including spoken destination, thread context when
applicable, confirmation, cancel and one dispatch to the same destination as the
phone notification's inline reply. Repeat for DMs, group DMs and threads.
Joe accepted this original-action routing on November 12, 2026 (#23).

Test with Hide Flack copies on the phone on first. Copies use the silent channel
with minimum importance. If the car stays empty, turn the switch off and receive
a new Slack message. Switching clears existing copies and tokens. The report
names each copy's channel and whether its mark-as-read action ran; the host
does not provide a display receipt. In alerting mode, new copies can alert on the
phone. Verify that updates and own replies stay quiet, and that source removal
and car disconnection remove Flack's copies. Android Auto controls its launcher
notice; Flack does not post it.

## G0: Test the native route

1. For each target phone, record the model, Android/One UI, Slack, Google/Gemini, Android Auto, car/head unit, and wired/wireless connection.
2. Update the relevant apps through their normal supported channels.
3. In Slack, enable mobile DMs/mentions and temporarily set mobile timing to immediately, including while desktop Slack is active. Keep Slack in the background. [Slack settings](https://slack.com/help/articles/201355156-Configure-your-Slack-notifications)
4. On the phone, search Settings for Notification access and enable Google. Exact Samsung menu labels vary by version; use the permission instructions in [Google's Device assistance guide](https://support.google.com/gemini/answer/15235441?hl=en#read_messages).
5. Receive a fresh synthetic DM. With the phone unlocked, ask Gemini to read the latest notification, then dictate a unique test response. Check the resulting Slack conversation.
6. Repeat using the car's Android Auto microphone/steering-wheel control. Verify which assistant actually handles the request.
7. If that works, configure and test Google's documented locked-phone settings. These also affect access to sensitive notification content; record what was enabled.
8. Repeat with another fresh message. Verify readback, cancel, and confirmation as well as delivery.

Classify the result as supported on the tested car, works only on phone, setup issue, unavailable feature, or inconclusive. A phone-only success does not satisfy the original car requirement. A failed prompt alone does not establish that all native routes are impossible.

If the native car workflow passes, use it and stop custom implementation/publication unless the maintainer asks to continue for a separate benefit. Keep an inconclusive result pending. Proceed to the custom route only after a repeatable failure remains following the relevant setup checks.

## G1: Inspect and exercise Slack's source actions

Before writing the car wrapper, confirm that normal notification-shade replies work for each candidate message type. Then use a local diagnostic build to inspect capability structure without retaining real message content.

For each notification type, record source package/profile, whether text is available, whether it is a group summary, the number and kind of reply actions, free-form RemoteInput availability, whether the action opens UI, and whether distinct thread actions exist. Keep exact identifiers local; publish only structure and synthetic fixtures.

Offer a reply only with a usable, unambiguous background action and enough destination context. Keep readable text available without a reply otherwise. Do not infer routing from the action's English label. Repeat for public channels (with and without mentions), private channels, group DMs and threads; see the capability table in ROADMAP.md.

## G2: Prove one complete DM

Use one eligible synthetic DM, then publish the bridge notification through the standard Android Auto messaging integration. Hear it, dictate a unique response, review it, and confirm.

The target Slack DM must contain the exact response once. The phone remains locked during the final repeat. A cancel attempt must create no message. Observe the car's wording and any phone-side fallback; a fixture-only pass is insufficient.

## Routing and recovery matrix

| ID | Scenario | Required outcome |
| --- | --- | --- |
| R01 | One supported DM | One confirmed reply in the same DM |
| R02 | User cancels dictation/readback | No invocation of Slack's reply action |
| R03 | User corrects dictated text | Only the final confirmed text is dispatched |
| R04 | Two conversations arrive together | Each selected action stays bound to its source |
| R05 | Another message arrives during dictation | Other conversations cannot steal the route; a changed selected-source snapshot invalidates its old reply token |
| R06 | Identical sender/channel names | Route by validated capability, never display label |
| R07 | Same names in two workspaces | No cross-workspace reply; disable if context is ambiguous |
| R08 | Two threads in one channel | Correct thread or explicit unsupported result |
| R09 | Channel action versus thread action | Preserve the selected action's semantics |
| R10 | Source PendingIntent reused/updated | Detect conflicting bindings; no assumed immutable snapshot |
| R11 | Repeated reply callback/token | At most one source dispatch for that token |
| R12 | Process death around dispatch | No automatic retry, false delivery claim, or reusable old token |
| R13 | Source notification removed | Mirrored reply becomes unavailable; no substitute recipient |
| R14 | Source action cancelled | Clear local failure; no retry against another action |
| R15 | Notification updated with altered route | Invalidate pending old binding; fail closed |
| R16 | Summary plus child notifications | No duplicate readout or ambiguous summary reply |
| R17 | Repeated message history in updates | No repeated new-message alert for already represented content |
| R18 | Long, Unicode, emoji, or multiline text | Preserve meaning/order; indicate unsupported/truncated content |
| R19 | Image, audio, attachment-only notification | No fabricated transcription or invented message body |
| R20 | Empty or oversized reply | Reject with clear status; never silently truncate |
| R21 | Phone locked | Supported flow completes without touching/unlocking phone |
| R22 | Network lost before/after handoff | Never claim server delivery; no ambiguous automatic retry |
| R23 | App listener disconnect/reconnect | Visible health state; no replay of old content as new |
| R24 | Permission revoked while connected | Stop forwarding and clear affected state |
| R25 | Android Auto disconnect/reconnect | New session; no stale actions or whole-shade replay |
| R26 | Bridge paused or data cleared | No new forwarding; previous tokens invalidated |
| R27 | Source Slack restart/update/sign-out | Stale capabilities fail closed |
| R28 | Screen off; Samsung sleep settings | Actual background behavior recorded and recoverable |
| R29 | Navigation/music/call interruption | Host audio flow recovers without duplicate sending |
| R30 | Managed profile or hidden content | Honest unsupported state; no policy bypass |
| R31 | Read/acknowledgement action before reply | Original source remains available for a valid reply |
| R32 | Forged, malformed, or expired action token | No source invocation and no sensitive diagnostic output |
| R33 | More than configured memory limits | Bounded memory; evicted tokens cannot send |
| R34 | Unsupported message structure/app | No forwarding loop or accidental inclusion |
| R35 | Source action cannot accept RemoteInput | Mark unsupported; no empty or incorrectly filled reply dispatch |
| R36 | Explicit mutable wrapper and immutable acknowledgement | Wrapper receives exact input; acknowledgement cannot accept a reply or substitute a token |
| R37 | Public channel, including a non-mention notification | Readout; any offered reply reaches only that channel, once |
| R38 | Private channel | Readout; any offered reply reaches only that private channel, once |
| R39 | Group DM | Readout; any offered reply reaches only that group, once |
| R40 | Mixed channel/thread actions or unknown routing | Readout remains; no reply action or dispatch, including held old actions |

## Meaningful automated tests

Test parsing and deduplication against synthetic structures that represent distinct supported and unsupported cases. Assert routing isolation, token consumption, duplicate suppression, summary handling, and fail-closed behavior. Do not write tests that merely repeat a function's implementation.

Instrument the notification-to-reply contract with a purpose-built synthetic source app or isolated test fixture. The fixture should expose controlled RemoteInput actions, collisions, cancellation, and replacement. It must not impersonate the production Slack package or be included in the release APK.

Use Android Auto's Desktop Head Unit for early integration checks, then validate on both target phones and the actual car. Test both the product's intended connection and any additional mode before claiming it as supported.

## Emulator check in CI

Every pull request runs `scripts/emulator-e2e.sh` on an Android 15 emulator with the fake Slack app (`fixture/`). DMs, public/private channels, group DMs and two threads become separate car copies. Replies in reverse arrival order use the copies' real PendingIntents and reach only their original destination once, even with repeated callbacks. Mixed actions and unknown routing produce copies with no reply action. Turning Flack off removes all copies. This checks a real Android system image, but has no Android Auto or real Slack; it cannot prove host readout of read-only copies.

## Desktop Head Unit checks

The Desktop Head Unit (DHU) runs Android Auto on a computer, connected to a phone over USB. It is faster than the car for early checks, and does not replace them. [developer.android.com/training/cars/testing/dhu](https://developer.android.com/training/cars/testing/dhu)

- Step-by-step setup with the test-only fake Slack app is in [DHU.md](DHU.md).
- Turn off "Only while Android Auto is connected" only for checks without the DHU or a car.
- `mic play <file.wav>` plays a recorded reply as dictation, so a unique phrase can be checked in the Slack DM. `mic begin` uses the computer's microphone.
- `restrict all` simulates driving restrictions. Check that the readout and reply still work.
- Use a synthetic Slack DM. Record DHU and Android Auto versions in the device test record.

## Checks the research added

These come from Android documentation and AOSP behavior that could not be verified without a device. Record each result per phone.

- **Repeated readout:** Flack sends up to the last 10 messages of a conversation on each update. Check whether Android Auto reads earlier messages again when a new one arrives (R17).
- **Reply after idle:** reply with the screen off after a long idle and in Doze. Slack's action relies on a short background-start allowance that holds only while the listener is bound (R22, R28).
- **Rebinding:** after an app update and after a reboot, check that new DMs still reach the car without toggling notification access (R23, R27).
- **Refused reply:** an empty and an over-limit reply. The car should not report success, and the copy should stay usable (G4).
- **Samsung sleeping apps:** with battery set to unrestricted, check Flack is not on Samsung's sleeping or deep sleeping lists after a day.
- **Duplicate readout:** check that Slack itself does not also appear in Android Auto. If a Slack update adds native support, drivers would hear each DM twice.

## Sign-in route checks

These cover the route in [SLACK_SIGN_IN.md](SLACK_SIGN_IN.md). Use test channels and invented message text.

- **Route field probe:** on the S24 Ultra in the NJ PBS workspace, with Flack forwarding, have a test account send a DM, a group DM, three channel messages, a mention and three thread replies. Copy the test report. For each message it lists which notification fields are present and how many values in each have an ID or timestamp shape, with no values. Paste it into DEVICE_TEST_RECORD.md.
- **Sign-in:** sign in while parked, close Flack during the browser step and sign in again. Add a second workspace, then sign out of one. Confirm the phone screen lists each workspace and the test report shows no name, token or workspace value. In a workspace that needs admin approval, record what Slack shows.
- **Coverage:** with the person signed in, every message type above should reach the car with a reply action. Record the test report's route counts and each read-only reason.
- **Wrong channel:** two test channels with similar names, and the same text sent in both within a minute. Each reply must appear in its own channel or stay read-only. Any reply in the other channel blocks the pilot.
- **Wrong workspace:** with two workspaces signed in, a channel with the same name in both. Each reply must land in its own workspace or stay read-only.
- **Thread or channel:** reply to a thread reply and to a channel message. Each reply must land where the message it answers is.
- **Same text, two threads:** one test account posts the same text in two threads of one channel within 10 seconds. Each reply must land in its own thread or stay read-only.
- **Unsigned workspace:** with Slack on the phone signed in to a workspace that Flack is not, the same text from the same test name in both workspaces. A reply must never land in the signed-in workspace.
- **No network:** a reply with mobile data and Wi-Fi off shows "Not sent". A reply cut off after sending shows "Outcome unknown" and is not retried.
- **Expired sign-in:** after removing Flack from the workspace's apps in Slack, a reply is not sent and the phone screen asks to sign in again.
- **Read-back:** on each phone, confirm Android Auto reads the reply back and offers send and cancel. Record whether Android Auto or Gemini has a setting that sends replies without read-back, and its name.

## Privacy checks

Inspect the merged release manifest and dependencies for unplanned permissions or network components. Trigger normal and error paths with a distinctive synthetic phrase; confirm it does not appear in logs, persistent files, backups, diagnostics, or crash artifacts created by the companion. Verify that the release APK excludes the diagnostic source app and test fixtures that enable mock input.

Review screenshot and issue attachments before public upload. Use a synthetic workspace and invented people for public examples. Keep signing material outside the repository.

## Three-hour endurance check

Record connection mode, screen state, power/charging conditions, navigation/audio activity, and versions. Deliver controlled bursts and occasional confirmed replies over three hours. Check for dropped events, listener health, unbounded memory, repeated announcements, duplicate dispatch, crashes, and wakeups.

Measure source-listener-to-publisher latency against the provisional two-second normal-load target. Record external Slack push, assistant, and car delays separately. Compare resource use with an equivalent baseline. Do not report battery drain from a wired charging session as evidence of low overhead.

## Release decision

Any observed wrong destination, duplicate dispatch, cancellation that sends, false companion delivery claim, or content leak blocks a pilot. Host success wording after a rejected or definitely failed handoff also blocks a pilot unless supported behavior corrects it. Generic host wording after an actual handoff may be disclosed as a limitation because server delivery remains unverified. An unsupported message category can be removed from the release scope instead of blocking supported DMs. Every advertised type must pass the real-car tests.
