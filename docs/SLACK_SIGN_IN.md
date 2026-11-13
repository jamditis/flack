# Slack sign-in reply route

Design, November 12, 2026. Status: approved by the maintainer; not yet built or device tested. Two questions are open (see [Open questions](#open-questions)).

## Goal

A person signs in to Slack once on the phone. After that, Slack notifications that person's account gets can be read and answered in the car: DMs, group DMs, public and private channels, threads and mentions. Read-only should be rare, and the test report counts each case.

The v0.2.1 car test showed why this is needed. Two channel messages arrived without an inline reply action in Slack's notification (`NO_REPLY_ACTION`), so Flack had nothing to send the reply through, and Android Auto did not show the copies (#23). SPEC.md section 3 requires its own authentication, permission, distribution and delivery design for a Slack API client. This document is that design.

## What does not change

- Slack's own notifications decide which messages reach the car. Each person's Slack notification settings still apply. Flack polls nothing and does not read conversations it was not notified about.
- When the phone notification has a usable reply action, Flack uses it, as before.
- A reply goes only to a destination identified with certainty. If that is not possible, the copy stays read-only. A post to the wrong workspace, conversation or thread is the worst failure this route can cause.
- The token rules in SPEC.md sections 6 to 8 still apply: one token per generation, consumed once, revalidated before dispatch, never retried.
- The car reply uses Android Auto's standard messaging reply only (see [Car reply flow](#car-reply-flow)). There is no custom car interface.

## What the evidence must show first

Slack's notification is the only source that says which message the person is answering. A Slack search can find a message with the same sender, text and time, but it cannot prove that it is the same message: search merges nearby matches, follows each person's search filters ([search.messages](https://docs.slack.dev/reference/methods/search.messages)), and display names are not unique ([Slack, 2017](https://docs.slack.dev/changelog/2017-09-the-one-about-usernames/)). So every API reply needs structured evidence in the notification for:

- the **workspace**,
- the **conversation** for DMs and group DMs (a channel can also be confirmed by its name, which is unique in a workspace), and
- the **place**: the channel itself, or which thread.

Slack does not document what its Android notifications contain. The route field probe (build step 1) finds out on the first test drive. Where the evidence is missing, that message type stays read-only, and the test report counts it.

## Slack app

One Slack app, owned by the maintainer, with public distribution turned on and not listed in the Slack Marketplace. Any person in any workspace can sign in with it. The manifest and setup steps are in [SLACK_APP_SETUP.md](SLACK_APP_SETUP.md).

Flack asks for user token scopes only, and has no bot user.

| Scope | Why |
| --- | --- |
| `chat:write` | Post the reply as the signed-in person with `chat.postMessage` |
| `search:read` | Find the message a notification announced, with `search.messages`, when the notification gives its place but not its channel ID |
| `users:read` | Turn the user ID in a search result, and user mentions in its text, into the names the notification shows, with `users.info`, so the sender and text can be compared |

`search:read` lets Flack search every message the person can see. Flack uses it only for the queries described under [Message match](#message-match), built from one notification's text, and keeps results in memory only until the match is decided. The other ways to find a message are ruled out:

- `conversations.history` and `conversations.replies` are limited to one request per minute and 15 messages per request for distributed apps that are not in the Marketplace ([changelog, May 29, 2025](https://docs.slack.dev/changelog/2025/05/29/rate-limit-changes-for-non-marketplace-apps)). `search.messages` is not affected and allows about 20 requests per minute.
- Slack's newer real-time search API (`assistant.search.context`) has narrower scopes, but it is intended for AI apps that a person uses inside the Slack client. This design does not use it.

## Sign-in

### PKCE, without a client secret

Slack supports PKCE (RFC 7636) for public clients such as desktop and mobile apps. It became generally available on March 30, 2026. With PKCE, the code exchange sends a `code_verifier` instead of a `client_secret`. Slack documents a secretless refresh for desktop redirects, which are custom URI schemes and, for PKCE apps, localhost. So Flack ships no secret and needs no server. Sources, read November 12, 2026:

- [Using PKCE](https://docs.slack.dev/authentication/using-pkce)
- [PKCE general availability, March 30, 2026](https://docs.slack.dev/changelog/2026/03/30/pkce)
- [Using token rotation](https://docs.slack.dev/authentication/using-token-rotation)
- [App distribution](https://docs.slack.dev/app-management/distribution/)

### Redirect

The redirect URL is the custom scheme `tech.amditis.flack://oauth`. Slack treats it as a desktop redirect: PKCE is required, bot scopes are refused, tokens always rotate, and refresh needs no secret. An HTTPS redirect would be a server redirect, whose refresh Slack documents with a client secret, so this design does not use one.

Slack's distribution page says a distributed app must support SSL for its OAuth redirect URLs. Whether that rule applies to a custom scheme is an [open question](#open-questions). The maintainer finds out when turning on distribution.

Another app can register the same scheme and receive the redirect. PKCE covers this: the code is useless without the verifier, which never leaves Flack's memory.

### Flow

1. The person taps **Add a Slack workspace** on the phone.
2. Flack makes a random 32-byte `code_verifier` and a random `state`, in memory only. The `code_challenge` is the URL-safe Base64 SHA-256 of the verifier, method `S256`, the only method Slack supports.
3. Flack opens `https://slack.com/oauth/v2/authorize` in the default browser with `client_id`, an empty `scope`, `user_scope=chat:write,search:read,users:read`, `redirect_uri`, `state`, `code_challenge` and `code_challenge_method=S256`. The browser is used, never a WebView, so Flack never sees the Slack password (RFC 8252). The person picks the workspace on Slack's page.
4. Slack redirects to `tech.amditis.flack://oauth`, which opens Flack.
5. Flack accepts the result only when `state` matches the one pending attempt, and uses that attempt once. A result with the wrong state is refused and leaves the real attempt pending. An `error`, a missing code or a second result ends the attempt.
6. Flack posts `client_id`, `code`, `code_verifier` and `redirect_uri` to `oauth.v2.access` over HTTPS and keeps the user token from `authed_user` for the workspace in `team`.
7. Flack calls `auth.test` and lists the workspace on the phone: "Paper Kites: signed in as alex". Names are shown on the phone only, never in the test report.

If Android stops Flack while the browser is open, the verifier is gone and the person starts again. Flack does not save the verifier.

### Several workspaces

Each sign-in adds one workspace. Flack keeps one token per person per workspace, keyed by the workspace ID that Slack returns. Signing in to a workspace that is already listed replaces its token. Each workspace has its own **Sign out**.

### Workspace admin approval

Some workspaces let only admins approve apps, and some let people request an app for admin approval ([App distribution](https://docs.slack.dev/app-management/distribution/)). In those workspaces, Slack's sign-in page shows that approval is needed and, where the workspace allows it, a way to send a request instead of **Allow**. Flack cannot change this. When the person returns without a code, Flack shows: "Slack did not finish signing in. If your workspace needs an admin to approve Flack, ask them to approve it, then tap Add a Slack workspace again." The exact Slack page is recorded during the first test in each workspace.

### Token lifetime

Access tokens expire after 12 hours. Each refresh returns a new refresh token, and the old one stops working after a short grace period. Refresh tokens for PKCE apps expire after 30 days. Refresh sends `client_id`, `grant_type=refresh_token` and `refresh_token`, with no secret.

Flack refreshes a workspace's token only when it needs it: before a lookup or a reply, and at sign-out. It does no background refreshing. One refresh runs at a time, because Slack revokes tokens beyond two active ones. If Slack refuses the refresh token, that workspace shows "sign in again" on the phone, and its messages fall back to the original action or read-only. A person who does not use Flack for 30 days signs in again.

### Costs and risks

- Enabling PKCE marks the Slack app as a public client. Slack support must be contacted to undo it. The app is used only for Flack.
- The client ID is public by design. The build reads it from the Gradle property `slackClientId`, set by the release workflow from the repository variable `FLACK_SLACK_CLIENT_ID`. A build without it hides sign-in and says "Slack sign-in is not set up in this build."
- The person must be able to sign in to the workspace in the phone's browser.
- An unlisted app can be used by anyone who has the sign-in link. Slack recommends Marketplace review for commercial distribution. Flack is not commercial; a listing would be a separate decision.

## Token storage

- Each workspace's record (access token, refresh token, expiry, Slack user ID, workspace ID, workspace and person names for the phone screen) is encrypted with AES-256-GCM. The key is created in Android Keystore and cannot be exported. Each write uses a random IV. The ciphertext is one file in the app's no-backup directory. Backups are already off.
- The key does not require an unlocked screen, because car replies happen with the phone locked. Keystore keeps the tokens unreadable to other apps and to someone who copies the app's storage. It does not protect a rooted phone.
- Tokens never appear in logs, exceptions, intents, notifications or the test report. Errors are reduced to fixed categories before they are recorded.
- **Sign out** revokes both tokens. With token rotation, `auth.revoke` revokes only the token it is given ([Using token rotation](https://docs.slack.dev/authentication/using-token-rotation)), so Flack calls it with the access token, the refresh token, and any earlier access token that a refresh replaced and Slack did not confirm revoking. It reports the sign-out as confirmed only when Slack confirms all of them. Then it deletes the record. When the last workspace signs out, Flack deletes the file and the key. If Slack cannot be reached or does not confirm, Flack deletes them anyway and says that Slack may keep the sign-in until it expires or the person removes Flack from the workspace's apps.
- Uninstalling Flack deletes the file and the key.

## Routing

### Order

For each Slack notification Flack forwards:

1. **Original action.** The existing check (SPEC.md section 6) finds one usable reply action. Flack uses it and makes no API call for this message.
2. **Notification identifiers.** Structured fields give the workspace, the conversation and the place.
3. **Message match.** Structured fields give the workspace and the place, and a search confirms the channel.
4. **Read-only.** Otherwise.

Steps 2 and 3 apply to every notification that the existing check makes read-only, except an unknown conversation type. Notifications it rejects (group summaries, empty or context-free notifications, non-message notifications) are still not forwarded.

### Workspace

If a structured field carries a workspace ID that the probe evidence shows names the notification's workspace, that ID decides: when it is not a signed-in workspace, the message stays read-only, whatever the setting below says. Otherwise Flack uses a workspace only when one of these holds:

- a workspace ID in a structured notification field, that the probe evidence shows names the notification's workspace, matches a signed-in workspace; or
- the person turned on **Slack on this phone uses only my signed-in workspaces**, and either exactly one workspace is signed in, or the notification's subtext equals the name of exactly one of them.

A subtext name alone never selects a workspace, because a workspace Flack cannot see may have the same name. The setting is off by default. Its text says: "Turn this on only if every workspace in the Slack app on this phone is signed in here. If you add a workspace to Slack later, sign in to it here too." Otherwise the message stays read-only (`WORKSPACE_UNKNOWN`).

### Notification identifiers

Flack uses only structured fields: the notification's shortcut ID, its `LocusId`, its tag, string values in its extras, and the newest sender's Person key and URI. It never uses the conversation title, message text, sender names or action labels to choose a destination.

Shape counts alone cannot show what a field means: a message timestamp and its thread parent's timestamp look the same. The probe therefore reports relations, compared in memory and exported as yes or no:

- whether a field's channel ID agrees across notifications of the same conversation and differs between conversations;
- whether a field's user ID agrees across messages from the same sender;
- whether a field's workspace ID is the same in every notification;
- whether a field's timestamp equals this message's time, or the time of an earlier notified message (a thread parent).

After sign-in, a second probe stage compares field values with IDs from Slack itself. For a channel notification, a search in the test channel by the tester's exact test phrase gives the channel ID (channel names are unique in a workspace) and the message and thread timestamps. The stage exports only per-field results such as "tag equals the channel ID: yes". Several cases that agree establish a mapping; this stage never routes a reply.

A field mapping is used only when the evidence shows it for that message type, and it is written into this document with the evidence. Otherwise this step is skipped for that type.

### Message match

Message match fills in a channel when the notification gives the workspace and the place (channel or thread parent timestamp) but not the channel ID. DMs and group DMs are never matched this way: they need a structured conversation or sender ID (step 2).

1. Flack takes the newest incoming message in the notification: its sender name, text and timestamp.
2. It runs `search.messages` in the pinned workspace with up to eight plain words of the text, quoted, sorted by timestamp, newest first, 20 results.
3. It keeps a result only when its timestamp is within 10 seconds of the notification message's, its author's display name or real name (`users.info`) equals the notification's sender name, its text equals the notification text after both are normalized (mentions replaced by names, links by their labels, HTML entities decoded, whitespace collapsed), it is in a channel whose name equals the notification's title without a leading `#`, and it is not a thread reply also sent to the channel (`thread_broadcast`).
4. Exactly one result must remain. Its channel ID is the destination. The place still comes from the notification: the channel itself, or the thread parent timestamp from the structured field. If the result's own place disagrees with the notification's (for example, a thread reply when the notification shows a channel message), the message stays read-only.

Slack may need a few seconds to index a new message, so Flack searches at most three times per notification: at once, after 2 seconds and after 6 seconds. It stops at the first unique match or the first ambiguous result. This is a lookup; a reply is never retried. Search results and user names are kept in memory only. User lookups are cached in memory and cleared when the session ends.

### Before the car copy

Flack resolves the route when the notification arrives, before it posts the car copy, so Android Auto offers a reply only for a certain route and the driver never waits on a search. A copy that needs a lookup can appear up to about seven seconds after the Slack notification. A message that stays read-only is posted read-only after the last attempt, and Android Auto may not show it, as today.

### Counting read-only cases

The test report counts how each forwarded message was routed: original action, identifiers, message match, or read-only. Read-only cases are counted by reason: workspace unknown, not signed in to that workspace, no structured conversation ID, no structured place, no match, more than one match, place disagrees, lookup failed, sign-in expired. A rise in one reason shows what to fix.

## Dispatch

1. The car reply arrives with its token, as now. The registry consumes the token once and checks that the generation is current.
2. Flack refreshes the workspace's access token if it expires within five minutes.
3. Flack calls `chat.postMessage` once with `channel`, `text` and, for a thread, `thread_ts`. Connect timeout 5 seconds, read timeout 10 seconds, on a background thread. The broadcast is not held open with `goAsync`, because a refresh and its revokes can take longer than the broadcast deadline; the bound notification listener keeps the process running. The car copy is reposted on the main thread, with the listener's own changes.
4. Results:

| Result | Status shown |
| --- | --- |
| `ok: true` and the response `channel` equals the requested one | Posted to Slack (Slack confirmed) |
| The refresh or the connection failed before the request was sent | Not sent |
| `ok: false` with an error that Slack documents as a refusal, such as `channel_not_found`, `not_in_channel`, `is_archived`, `msg_too_long`, `restricted_action`, `invalid_auth`, `token_expired` or `ratelimited` | Not sent, with that error |
| Any other `ok: false`, including `internal_error` and `fatal_error`, which Slack says can follow a partial success ([chat.postMessage](https://docs.slack.dev/reference/methods/chat.postMessage)) | Outcome unknown |
| Timeout or connection lost after the request was sent | Outcome unknown |

Flack never retries, including after an unknown outcome. The reply text is held in memory for the request only. Slack's `ok: true` is a server confirmation, unlike "Handed to Slack" on the original-action route.

## Car reply flow

Flack's car copy uses Android Auto's standard messaging reply: one `RemoteInput`, `SEMANTIC_ACTION_REPLY`, and `setShowsUserInterface(false)` ([Extend messaging notifications to Android Auto](https://developer.android.com/training/cars/communication/notification-messaging), read November 12, 2026). The copy has this reply action only when the route is certain. The driver taps **Reply**, speaks, hears the reply read back, then sends or cancels. Google's help page says: "After you say your message, Gemini or Assistant will repeat it and confirm if you are ready to send it. You can ask to send, cancel, or refine your message" ([Send and receive messages](https://support.google.com/androidauto/answer/6348317), read November 12, 2026, no date shown).

That help page does not describe a setting that sends replies without the read-back. The setup screen and INSTALL.md tell people: "If Android Auto or Gemini offers a setting to send replies automatically, turn it off, so you hear every reply before it is sent." The first car test records whether such a setting exists on each phone and what it is called.

## Permissions and public claims

This route adds the `INTERNET` permission, the first beyond notifications. SPEC.md section 10 and the permission check change with it. Flack connects only to `https://slack.com`, with cleartext traffic off. It makes no network request until the person signs in. No analytics, crash upload or other network use is added.

The sign-in build updates every public "no internet permission" and "no Slack login or token" claim in the same pull request: README.md, SECURITY.md, docs/INSTALL.md, site/index.html and site/privacy.html.

## Build plan

Small stacked pull requests, each with focused unit tests:

1. Route field probe (v0.2.2): shapes and yes/no relations in the content-free test report. No sign-in and no network. It ships first, because every API reply depends on its evidence.
2. Sign-in: PKCE with the custom-scheme redirect, Keystore token storage for several workspaces, sign-out that revokes both tokens, the `INTERNET` permission, the phone screen and the public text changes.
3. Probe stage two: per-field comparison with known IDs after sign-in.
4. API reply: the field mappings the evidence supports, the workspace rule, message match where it applies, dispatch, the route order and the test report counts.

## Step 4 before the probe evidence

Built on November 13, 2026, before the #29 report. The route order, certainty rules, dispatch and status text are in place, and the field mapping table (`FieldMappings.ALL` in `core/ApiRoute.kt`) is empty. So every message without a usable original action stays read-only, counted as `NO_FIELD_MAPPING`. A later pull request adds mappings from the evidence.

Rules in this build:

- A mapping covers one kind: one-to-one (`isGroup` false) or group (`isGroup` true). An unknown kind has no mapping. Two mappings for one kind count as none.
- A mapping names a field and a pattern for each of the workspace ID, the conversation ID and the thread timestamp. Each pattern must match exactly once. A one-to-one message needs a `D` conversation ID, a group needs a `C` or `G` ID.
- The workspace ID decides. A workspace that is not signed in, or must sign in again, stays read-only, even when another workspace is signed in. The **Slack on this phone uses only my signed-in workspaces** setting and the subtext rule are not built: a mapping without a workspace field is not possible in this build.
- Only an absent thread field means the conversation itself. A thread field without exactly one timestamp leaves the place unknown (`NO_PLACE`).
- A group without a title stays read-only (`UNTITLED_GROUP`), so the driver always hears which conversation a reply goes to.
- Message match (search) is not built.
- The listener reads the signed-in workspaces from the last copy Flack loaded or saved, without waiting for a sign-in, refresh or sign-out in progress. Dispatch still asks for that workspace's token, so a sign-in that ended meanwhile is "Not sent".
- Just before `chat.postMessage`, after any refresh, Flack checks again that the car copy is still the current one. A copy withdrawn or replaced meanwhile is "Not sent".
- `chat.postMessage` escapes `&`, `<` and `>`. Slack's answer must name the requested channel and thread. Otherwise the status is "Posted to Slack, but Slack's answer did not confirm the same conversation and thread".
- A failure to connect is "Not sent". After the first byte of the request is written, a lost or unreadable answer is "Outcome unknown". An HTTP 429 is read as Slack's `ratelimited` refusal.

## Probe evidence, November 13, 2026

The maintainer's report is on #29: Flack 0.3.0, Slack 26.11.20.0, Samsung S24 Ultra, Android 17. It has seven route field probe cases and twelve multiple reply action cases. The maintainer is signed in to about ten workspaces in Slack on that phone, so a field that differs between messages may still name each message's own workspace.

What the report shows:

- **Conversation.** `shortcutId` and one string extra (extra key 2) each hold one channel-shaped ID. In cases 1 to 4, four one-to-one notifications from one conversation, both agree within the conversation and differ from every other case. Cases 5 to 7 each come from a different conversation, so agreement within a group conversation is not shown. The report does not say whether an ID starts with `C`, `G` or `D`.
- **Sender.** `senderKey` holds one user-shaped ID that agrees for one sender and differs between senders. A sender ID does not name a conversation (see [Step 4](#step-4-before-the-probe-evidence)), so it is not used.
- **Workspace.** No named field holds a workspace-shaped ID. Extra keys 3, 4 and 5 each hold one, and their values are not the same in every case. The report gives these keys local numbers, not their names, so a mapping cannot refer to them. It also does not say whether they agree within one conversation, so it does not show which of them, if any, is the message's own workspace.
- **Place.** Only case 6 has timestamp-shaped values (extra keys 4 and 7). Neither equals case 6's message time or an earlier case's message time. The report does not show a thread parent, and it does not show that a missing timestamp means the message is not in a thread.

So `FieldMappings.ALL` stays empty: no message type has the workspace, conversation and place that a mapping needs.

| Message type | Mapped | Why |
| --- | --- | --- |
| DM (one-to-one) | No | The conversation ID agrees within one DM (cases 1 to 4), but the workspace field is unknown, the `D` prefix is not shown, and a thread is not ruled out. |
| Group DM | No | The report cannot tell a group DM from a channel (both have `isGroup` true, and prefixes are not shown). No group conversation appears twice. The workspace field is unknown. |
| Channel | No | Same as group DM. |
| Thread | No | No timestamp is shown to be a thread parent. The one timestamp-bearing case (6) matched no notified message. |

### Why Slack's reply actions are not used

Every multiple reply action case has three actions: one with `semanticAction=2` (mark as read) and no input, then **Reply in thread** and **Reply**, each with one text input. Both reply actions have `semanticAction=0` (none) and `showsUserInterface=true`.

The capability check (SPEC.md section 6) counts an action as a reply action only when it takes free-form text and declares `SEMANTIC_ACTION_REPLY` (1). Slack's two actions declare 0, so the check finds no reply action and reports `NO_REPLY_ACTION`. It stops before the `MULTIPLE_REPLY_ACTIONS` check. If the check ignored the semantic action, both would still fail as `REPLY_OPENS_APP`, because they show an interface. So the routed cases are `NO_REPLY_ACTION` even though Slack offers Reply buttons on the phone.

The two lists are recorded at different points. Flack records the action shapes for every Slack notification with more than one text input, before any check, and the route field probe records only notifications that the check does not reject. The two lists have different case numbers and cannot be matched one to one.

### Next probe

The probe now also shows, for each field:

- the prefix letters of the IDs it holds (`ID prefixes: D T`), so a DM can be told from a channel;
- for an extras key whose value holds an ID or timestamp, its name when it is on a fixed list of common ID key names (such as `teamid`, `channelid` or `threadts`, compared without case, `_` or `-`, and after the last dot), shown in the list's spelling; any other key shows "not on the list", so no key name from Slack is copied into the report. Slack does not document its extras, so the list is a guess. If the keys are not on it, probe stage two (comparison with IDs from Slack after sign-in) is still needed;
- whether a workspace ID agrees within one conversation.

The maintainer runs the second probe in [TEST_PLAN.md](TEST_PLAN.md). A later pull request adds a mapping for a message type only when that report shows all three of the workspace, the conversation and the place for it.

### Rules from an existing Slack reply bridge

The maintainer runs a separate Slack bridge that posts replies in two workspaces. Its reply rules were compared with Flack's:

| Bridge rule | In Flack | Notes |
| --- | --- | --- |
| Check the signed-in user and workspace before any post | Yes, at sign-in and refresh | Flack keeps the user and workspace IDs from Slack's sign-in answer and refuses a refresh that names another workspace. It looks up the token by the destination's workspace ID. It does not call `auth.test` before each post, because each token comes from that workspace's own sign-in. |
| Check the channel, sender, timestamp and thread against the incoming message | Partly | The bridge reads the message back with `conversations.history` or `conversations.replies`. Flack does not: those methods allow one request per minute for an app that is not in the Marketplace, and Flack does not ask for their scopes. Flack instead needs probe evidence for each field, and checks that Slack's answer to `chat.postMessage` names the requested channel and thread. |
| Never take the workspace from a display name or message text | Yes | The workspace ID decides. The subtext rule is not built. |
| Keep state for each workspace | Yes | One token record for each workspace ID, with its own sign-out. Flack keeps no conversation state. |
| Hold an uncertain delivery and never retry it | Yes | Flack has no queue. An uncertain post shows "Outcome unknown", its reply token is used up, and it is not retried. |

## Open questions

1. Does Slack accept the custom-scheme redirect `tech.amditis.flack://oauth` for an app with public distribution turned on, given its "SSL for OAuth redirect URLs" rule? If not, the serverless design needs a different redirect, and the secretless refresh must be checked again. (#28)
2. Do Slack's Android notifications carry a workspace ID, a conversation or sender ID, and a thread or channel signal in structured fields? The first probe report found conversation and sender IDs, unnamed extras with workspace IDs, and no proven thread signal (see [Probe evidence](#probe-evidence-november-13-2026)). The second probe answers the rest. (#29)

## What the maintainer does

1. Create the Slack app and install it, following [SLACK_APP_SETUP.md](SLACK_APP_SETUP.md). Report on #28 if Slack refuses the custom-scheme redirect.
2. Run the route field probe test in [TEST_PLAN.md](TEST_PLAN.md) with v0.2.2 and paste the report on #29.
