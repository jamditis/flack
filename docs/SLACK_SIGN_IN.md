# Slack sign-in reply route

Design updated November 13, 2026. Sign-in is merged. The first API reply experiment sends a top-level reply to the notification's conversation. Physical phone/car delivery remains unverified.

## Goal

A person signs in to Slack once on the phone. After that, Slack notifications that person's account gets can be read and answered in the car: DMs, group DMs, public and private channels, threads and mentions. Read-only should be rare, and the test report counts each case.

The v0.2.1 car test showed why this is needed. Two channel messages arrived without an inline reply action in Slack's notification (`NO_REPLY_ACTION`), so Flack had nothing to send the reply through, and Android Auto did not show the copies (#23). SPEC.md section 3 requires its own authentication, permission, distribution and delivery design for a Slack API client. This document is that design.

## What does not change

- Slack's own notifications decide which messages reach the car. Each person's Slack notification settings still apply. Flack polls nothing and does not read conversations it was not notified about.
- When the phone notification has a usable reply action, Flack uses it, as before.
- A reply goes only to a workspace and conversation identified with certainty. If that is not possible, the copy stays read-only. This API experiment always posts at the top level, including when the incoming notification describes a thread reply.
- The token rules in SPEC.md sections 6 to 8 still apply: one token per generation, consumed once, revalidated before dispatch, never retried.
- The car reply uses Android Auto's standard messaging reply only (see [Car reply flow](#car-reply-flow)). There is no custom car interface.

## Evidence for the first reply

The November 13 field report on [#29](https://github.com/jamditis/flack/issues/29#issuecomment-6865459623) shows a conversation ID in `shortcutId`: D for DMs and G for groups, present in all 11 cases, agreeing within a conversation and differing between conversations. Workspace IDs appear as whole string extras values and agree within a conversation. Their key names remain unknown. No observed timestamp identifies this message or its thread parent.

The maintainer approved top-level replies for this experiment on November 13: "We don't necessarily need to be able to reply in a specific thread. I'm more interested in confirming that we can send replies to begin with at all." No observed field reliably distinguishes thread messages. API replies to those notifications also go to the conversation's top level, never into a specific thread. The coordinator approved checking `auth.test` against an exact workspace ID in the notification extras, with no new scopes. A refusal from `chat.postMessage` ends that attempt; Flack never tries another conversation or workspace.

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

For each forwarded notification:

1. Use Slack's original reply action when the existing capability check accepts it. No API lookup runs.
2. Otherwise, use `shortcutId` only when its entire value is a valid conversation ID: D for a one-to-one notification, C or G for a group notification. The field report supports D/G; the coordinator also permits C-shaped group destinations. Unknown types, malformed IDs and untitled groups remain read-only.
3. Match whole extras values of the form `T[A-Z0-9]{8,}` to saved workspace IDs. Extra order, key numbers, titles, subtext, sender IDs and timestamps never select a destination. A workspace ID embedded in another string is insufficient.
4. The notification must carry exactly one distinct workspace ID, and exactly one saved workspace must match. Conflicting extra values, no matches or expired sign-in mean read-only.
5. Fetch that workspace's current token, refreshing if needed, and call `auth.test`. Its `team_id` must equal the workspace ID from the notification. Failure or mismatch means read-only.
6. Offer the API reply on the car copy, bound to that workspace and the exact shortcut conversation ID.

Flack publishes a read-only copy immediately, then runs the lookup on a background thread. A successful check adds the reply action quietly; a slow or failed check never delays readout. The lookup reads no history and makes no search. Source removal, pause, disconnect or a changed source generation discards a pending lookup. Shortcut and workspace-ID changes invalidate held replies. Duplicate callbacks keep the same reply token, including after it is consumed.

The content-free test report shows **Reply offered through the Slack API** and counts each unavailable reason: no field mapping for an unknown type, no conversation ID, untitled group, workspace unknown, workspace not signed in, expired sign-in, unreadable accounts, workspace unconfirmed or workspace ambiguous. It exports no identifiers or token values.

## Dispatch

1. The registry validates and consumes the car reply token once.
2. Flack obtains the pinned workspace's current token, refreshing when needed.
3. `auth.test` must still return that workspace's ID under the exact token used to post. Failure or mismatch sends nothing.
4. Flack checks that the car copy is still current, then calls `chat.postMessage` once with only `channel` and escaped `text`. It never sends `thread_ts`.
5. A confirmed response names the requested conversation and has no thread timestamp. The status is **Posted to Slack (Slack confirmed)**. An answer naming another place is reported as unconfirmed.
6. A definite refusal such as `channel_not_found`, `not_in_channel` or `is_archived` is **Not sent**, with Slack's content-free error code in the test report. The copy is reposted without a reply action. No other destination is tried.
7. An interruption after posting may have begun, an unreadable response, or an error that can follow partial success is **Outcome unknown**. Nothing is retried.

Network work runs off the main thread, with a 5-second connect timeout and 10-second read timeout. The broadcast is not held open; the bound notification listener keeps the process running. Reply text lives only in memory. Source removal or replacement while waiting for a token or workspace check prevents the post. Sign-in scopes stay unchanged.

## Car reply flow

Flack's car copy uses Android Auto's standard messaging reply: one `RemoteInput`, `SEMANTIC_ACTION_REPLY`, and `setShowsUserInterface(false)` ([Extend messaging notifications to Android Auto](https://developer.android.com/training/cars/communication/notification-messaging), read November 12, 2026). The copy has this reply action only when the route is certain. The driver taps **Reply**, speaks, hears the reply read back, then sends or cancels. Google's help page says: "After you say your message, Gemini or Assistant will repeat it and confirm if you are ready to send it. You can ask to send, cancel, or refine your message" ([Send and receive messages](https://support.google.com/androidauto/answer/6348317), read November 12, 2026, no date shown).

That help page does not describe a setting that sends replies without the read-back. The setup screen and INSTALL.md tell people: "If Android Auto or Gemini offers a setting to send replies automatically, turn it off, so you hear every reply before it is sent." The first car test records whether such a setting exists on each phone and what it is called.

## Permissions and public claims

This route adds the `INTERNET` permission, the first beyond notifications. SPEC.md section 10 and the permission check change with it. Flack connects only to `https://slack.com`, with cleartext traffic off. It makes no network request until the person signs in. No analytics, crash upload or other network use is added.

The sign-in build updates every public "no internet permission" and "no Slack login or token" claim in the same pull request: README.md, SECURITY.md, docs/INSTALL.md, site/index.html and site/privacy.html.

## First car test

Use synthetic conversations and invented text. The coordinator releases the build; this change creates no release tag.

1. Park, sign in to the test workspace, enable forwarding and connect Android Auto.
2. Receive a fresh Slack DM or titled group notification without a usable original reply action.
3. Copy the test report. **Reply offered through the Slack API** should increase; otherwise record the read-only reason.
4. Hear the intended conversation, dictate a reply, listen to readback, and confirm once.
5. Verify exactly one top-level message in the intended Slack conversation. Record the phone, Slack, Android Auto and head-unit versions, exact status and destination result without message content.
   Include a notification for a thread reply: its API response must also land at the top level of that conversation. Original-action replies still follow the original action's thread destination.
6. Cancel a second reply and confirm nothing was posted. Remove or update a notification during dictation and confirm the stale reply sends nothing.

A wrong destination, duplicate or cancel-that-sends blocks the pilot. A passing hosted suite does not establish car compatibility.

## Open questions

- [#28](https://github.com/jamditis/flack/issues/28): Slack accepted public distribution with the custom-scheme redirect on November 13; sign-in from a second workspace remains untested.
- [#29](https://github.com/jamditis/flack/issues/29): exact workspace extra key names and message/thread timestamps remain unknown. Targeting a specific thread through the API is outside this experiment; thread notifications receive top-level API replies.
