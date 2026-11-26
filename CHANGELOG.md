# Changelog

Changes that testers would notice, newest first. Each release on GitHub also lists the merged pull requests.

## 0.3.10

- A long test report keeps its route field probe and reply action sections.
  Before, once the other sections were full, the report left them out, and
  the 0.3.8 report had no route field probe. The two sections now share the
  room the rest of the report leaves.
- 0.3.10 also contains every 0.3.9 change. One parked test in a real car
  covers both.

## 0.3.9

- The car shows the Slack message first again. In 0.3.8 the **Reply to
  conversation** cue came first, so the one-line preview on the car screen hid
  the message. The cue now comes after the message.
- Car replies stay available when Slack also posts a summary of the same
  message. In 0.3.8 Flack saw the summary's copy of the reply action as a
  second conversation and turned the reply off ("Replies disabled as
  ambiguous").
- The test report shows the thread keys on each Slack notification and how
  their timestamps compare with the message time, without values. This
  explains "Reply unavailable" for possible thread messages.
- When Slack cannot confirm where a reply sent through Slack sign-in went,
  the car copy no longer shows your reply as sent.
- Tests and an Android emulator checked these changes. They still need a
  parked test in a real car.

## 0.3.8

- Flack makes a fresh car notification for each new Slack message it forwards,
  including new messages in the same conversation. Repeats of the same message
  stay quiet.
- Car notifications include **Reply** and **Mark as read** again. When Flack
  offers **Reply to conversation**, it gives Android Auto a cue to read before
  the message so you know your reply will go to the main conversation. Flack
  uses Slack's thread reply when it can verify the thread.
- A **Slack reply status** notice explains what happened if Flack can't tell
  where to send your reply or can't send it. If the reply option has expired,
  open the latest message to try again.
- After an update to Slack, Android Auto, or Android, Flack asks you to check
  setup and try a fresh message while parked. Use **Copy test report** if the
  car misses a message or reply.
- Tests and an Android emulator checked the notifications Flack prepares for
  the car, reply placement, and failure feedback. All three still need a
  parked test in a real car.

## 0.3.7

- **Quiet Flack copies on the phone** silences Flack's sound, vibration and
  pop-ups and keeps its reply button off the phone copy. Android Auto uses that
  copy, so you can still see it in the phone shade or status bar. It clears when
  Slack removes the original notification or you disconnect the car.
- When Slack offers both **Reply** and **Reply in thread** without designating
  one, Flack keeps the message read-only. The copy explains that Flack cannot
  tell where the reply belongs. Some cars may hide read-only copies. Flack uses
  a validated original reply action when available. Optional Slack sign-in can
  offer a reply to the main view of an eligible one-to-one DM.
- Flack can keep more messages readable when Slack changes how it formats a
  notification or puts different messages in the same notification group.
- After updating, park and receive a fresh test DM with quiet mode off. Turn
  quiet mode on and receive another. Compare the phone shade and alerts with
  the car notification list and readout. Try a channel or thread message with
  both Slack reply choices and check that it stays read-only. If a message is
  missing or a reply goes somewhere unexpected, use **Copy test report** in Flack.

## 0.3.6

- Flack keeps Android Auto setup help available after you check Unknown sources.
  When your Android Auto version changes, Flack asks you to check Unknown sources
  and Customize launcher again. The test report records your check for the
  installed version.

## 0.3.5

- Slack's exact Reply action with a free-form input can use the native route
  when its reply semantic is unset and its UI hint is true. An actual activity
  launch is still blocked. Creator, mutability, single-input and one-use checks
  remain. Reply in thread without a reply semantic stays unselected until a
  structured thread marker is verified.
- The content-free report separates the UI hint from actual activity launches
  and lists known thread-marker string keys by presence only. Native Slack
  handoff still needs a car retest; the confirmed 0.3.4 reply used the API.

## 0.3.4

- Slack's plain Reply action can be used when Slack omits Android's reply
  semantic, including beside Reply in thread. The original action and its input
  key are preserved; unsafe or ambiguous actions remain read-only.
- The phone names failed car notification posts. The test report keeps the last
  20 posting outcomes with notification/channel blocks and exception classes,
  without message content or exception messages. A physical car retest is needed
  to identify the zero-copy failure reported on 0.3.3.
- The phone shows when no Slack workspace is signed in, beside the existing
  Add a Slack workspace action. The cue follows your current sign-in state;
  an unreadable sign-in store shows an error instead.

## 0.3.3

- Slack messages marked as a group summary can now reach the car when no
  child notification is active. Flack selects by notification features,
  keeps one car copy when a child arrives, and still drops empty summaries.
  Unfamiliar readable formats stay read-only. The test report names each
  filter rule and shows the last 20 feature sets, without message content.
- Car copies use high-importance channels in both phone modes. Quiet mode uses
  a new channel so upgrades do not keep the old minimum importance. Phone
  quieting is now off by default so the car can alert; a saved switch choice is
  kept. The switch is called Quiet Flack copies on the phone and warns that
  Android Auto may miss quiet copies or alerts. Android still shows copies in
  the phone shade. A physical car retest is needed for quiet mode.

## 0.3.2

- Car replies can use Slack sign-in when the phone's notification has no usable
  inline reply action. Flack uses the notification's conversation ID and checks
  that its workspace matches the signed-in token before offering the reply and
  again before posting. Replies go to the conversation's top level. Missing or
  conflicting IDs and failed workspace checks stay read-only. A refused post
  records Slack's reason and is never retried. The test report counts API reply
  offers and reasons they were unavailable. A physical car test is still needed.

## 0.3.1

- The test report now fits in one paste of 20,000 characters. The route field
  probe starts with a summary of each field over all cases. When the full
  report is too long, Slack notifications with the same route are shortened to
  one line each, identical reply action shapes are listed once with their case
  numbers, and a last line says how many cases did not fit.
- The route field probe in the test report now shows the first letter of each
  ID it finds (for example D for a DM), the name of an extras key that holds an
  ID when the name is on a fixed list of common ID key names, and whether a workspace ID
  agrees within one conversation. The first probe report (#29) did not show
  which field names the workspace or a thread, so the Slack API route still
  offers no replies. docs/TEST_PLAN.md has the steps for the next probe.
- The reply route through Slack sign-in is built, but no message type uses it
  yet. Until the route field probe results (#29) show which notification fields
  name the workspace, conversation and thread, every message without Slack's
  own reply action stays read-only. The test report now counts why the Slack
  API route was not offered, and the last reply can show "Posted to Slack
  (Slack confirmed)", "Not sent" or "Outcome unknown".

## 0.3.0

- Optional Slack sign-in on the phone, for one or more workspaces. Flack keeps
  each Slack token encrypted with an Android Keystore key, and Sign out asks
  Slack to end the sign-in. This version only signs in; replies through
  sign-in come in a later version. Flack now has the internet permission for
  this, and makes no network requests unless you sign in.
- The Android Auto setup steps say to turn off any setting that sends replies
  without reading them back.

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
