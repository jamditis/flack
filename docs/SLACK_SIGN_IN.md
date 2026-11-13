# Slack sign-in reply route

Design, November 12, 2026. Status: approved for build by the maintainer; not yet built or device tested.

## Goal

A person signs in to Slack once on the phone. After that, every Slack notification that person's account gets can be read and answered in the car: DMs, group DMs, public and private channels, threads and mentions. Read-only should be rare, and the test report counts each case.

The v0.2.1 car test showed why this is needed. Two channel messages arrived without an inline reply action in Slack's notification (`NO_REPLY_ACTION`), so Flack had nothing to send the reply through, and Android Auto did not show the copies (#23). SPEC.md section 3 requires its own authentication, permission, distribution and delivery design for a Slack API client. This document is that design.

## What does not change

- Slack's own notifications decide which messages reach the car. Each person's Slack notification settings still apply. Flack polls nothing and does not read conversations it was not notified about.
- When the phone notification has a usable reply action, Flack uses it, as before.
- A reply goes only to a destination identified with certainty. If that is not possible, the copy stays read-only.
- The token rules in SPEC.md sections 6 to 8 still apply: one token per generation, consumed once, revalidated before dispatch, never retried.
- The car reply uses Android Auto's standard messaging reply only (see [Car reply flow](#car-reply-flow)). There is no custom car interface.

## Slack app

One Slack app, owned by the maintainer, with public distribution turned on and not listed in the Slack Marketplace. Any person in any workspace can sign in with it. NJ PBS is the first workspace to test. The manifest is in [slack-app-manifest.json](slack-app-manifest.json).

Flack asks for user token scopes only, and has no bot user.

| Scope | Why |
| --- | --- |
| `chat:write` | Post the reply as the signed-in person with `chat.postMessage` |
| `search:read` | Find the exact message a notification announced, with `search.messages`, when the notification has no identifiers. This is the only way to find it at a usable rate (see below) |
| `users:read` | Turn the user ID in a search result, and user mentions in its text, into the names the notification shows, with `users.info`, so the sender and text can be compared |

`search:read` lets Flack search every message the person can see. Flack uses it only for one query per unanswered notification, built from that notification's text, and keeps the results in memory only until the match is decided. The other ways to find a message are ruled out:

- `conversations.history` and `conversations.replies` are limited to one request per minute and 15 messages per request for distributed apps that are not in the Marketplace ([changelog, May 29, 2025](https://docs.slack.dev/changelog/2025/05/29/rate-limit-changes-for-non-marketplace-apps)). Finding a message across a person's channels that way is not practical. `search.messages` is not affected and allows about 20 requests per minute ([search.messages](https://docs.slack.dev/reference/methods/search.messages)).
- Slack's newer real-time search API (`assistant.search.context`) has narrower scopes, but it is intended for AI apps that a person uses inside the Slack client. This design does not use it.

## Sign-in

### PKCE, without a client secret

Slack supports PKCE (RFC 7636) for public clients such as mobile apps. It became generally available on March 30, 2026. With PKCE, the code exchange sends a `code_verifier` instead of a `client_secret`, so Flack ships no secret and needs no server. Sources, read November 12, 2026:

- [Using PKCE](https://docs.slack.dev/authentication/using-pkce)
- [PKCE general availability, March 30, 2026](https://docs.slack.dev/changelog/2026/03/30/pkce)
- [Using token rotation](https://docs.slack.dev/authentication/using-token-rotation)
- [App distribution](https://docs.slack.dev/app-management/distribution/)

### Redirect page

Slack requires HTTPS for every OAuth redirect URL of a distributed app ([App distribution](https://docs.slack.dev/app-management/distribution/)). A custom scheme such as `tech.amditis.flack://` is not HTTPS, so the redirect URL is a static page on the existing GitHub Pages site:

`https://jamditis.github.io/flack/oauth/`

The page has no server code. It makes no network requests and loads no scripts or fonts from other sites. Its only job is to pass `code`, `state` and `error` back to the app with an Android intent link restricted to the package `tech.amditis.flack`, so no other app can receive them. If the browser does not open the app, the page shows a **Return to Flack** button that uses the same link.

The authorization code passes through the browser and GitHub's web servers in the page URL. That is acceptable: the code works only once, expires in minutes, and is useless without the verifier, which stays in Flack's memory. The page source is in `site/oauth/` and can be reviewed.

### Flow

1. The person taps **Add a Slack workspace** on the phone.
2. Flack makes a random 32-byte `code_verifier` and a random `state`, in memory only. The `code_challenge` is the URL-safe Base64 SHA-256 of the verifier, method `S256`, the only method Slack supports.
3. Flack opens `https://slack.com/oauth/v2/authorize` in the default browser with `client_id`, an empty `scope`, `user_scope=chat:write,search:read,users:read`, `redirect_uri`, `state`, `code_challenge` and `code_challenge_method=S256`. The browser is used, never a WebView, so Flack never sees the Slack password (RFC 8252). The person picks the workspace on Slack's page.
4. Slack redirects to the redirect page, which opens Flack.
5. Flack accepts the result only when `state` matches the one pending attempt, and uses that attempt once. An `error`, a missing code or a second result ends the attempt.
6. Flack posts `client_id`, `code`, `code_verifier` and `redirect_uri` to `oauth.v2.access` over HTTPS and keeps the user token from `authed_user` for the workspace in `team`.
7. Flack calls `auth.test` and lists the workspace on the phone: "NJ PBS: signed in as Alex Rivera". Names are shown on the phone only, never in the test report.

If Android stops Flack while the browser is open, the verifier is gone and the person starts again. Flack does not save the verifier.

Another site or app cannot complete a sign-in for the person: a result Flack did not start fails the `state` check, and a code from another sign-in fails the verifier check at Slack.

### Several workspaces

Each sign-in adds one workspace. Flack keeps one token per person per workspace, keyed by the workspace ID that Slack returns. Signing in to a workspace that is already listed replaces its token. Each workspace has its own **Sign out**.

### Workspace admin approval

Some workspaces let only admins approve apps, and some let people request an app for admin approval ([App distribution](https://docs.slack.dev/app-management/distribution/)). In those workspaces, Slack's sign-in page shows that approval is needed and, where the workspace allows it, a way to send a request instead of **Allow**. Flack cannot change this. When the person returns without a code, Flack shows: "Slack did not finish signing in. If your workspace needs an admin to approve Flack, ask them to approve it, then tap Add a Slack workspace again." The exact Slack page is recorded during the first test in each workspace.

### Token lifetime

Access tokens expire after 12 hours. Each refresh returns a new refresh token, and the old one stops working after a short grace period. Refresh tokens for PKCE apps expire after 30 days. Refresh sends `client_id`, `grant_type=refresh_token` and `refresh_token`, with no secret.

Flack refreshes a workspace's token only when it needs it: before a lookup or a reply, and at sign-out. It does no background refreshing. One refresh runs at a time per workspace, because Slack revokes tokens beyond two active ones. If the refresh token has expired, that workspace shows "Sign in again" on the phone, and its messages fall back to the original action or read-only. A person who does not use Flack for 30 days signs in again.

### Costs and risks

- Enabling PKCE marks the Slack app as a public client. Slack support must be contacted to undo it. The app is used only for Flack.
- The client ID is public by design. The build reads it from the Gradle property `slackClientId`, set by the release workflow from the repository variable `FLACK_SLACK_CLIENT_ID`. A build without it hides sign-in and says "Slack sign-in is not set up in this build."
- The person must be able to sign in to the workspace in the phone's browser.
- An unlisted app can be used by anyone who has the sign-in link. Slack recommends Marketplace review for commercial distribution. Flack is not commercial; a listing would be a separate decision.

## Token storage

- Each workspace's record (access token, refresh token, expiry, Slack user ID, workspace ID, workspace and person names for the phone screen) is encrypted with AES-256-GCM. The key is created in Android Keystore and cannot be exported. Each write uses a random IV. The ciphertext is one file in the app's no-backup directory. Backups are already off.
- The key does not require an unlocked screen, because car replies happen with the phone locked. Keystore keeps the tokens unreadable to other apps and to someone who copies the app's storage. It does not protect a rooted phone.
- Tokens never appear in logs, exceptions, intents, notifications or the test report. Errors are reduced to fixed categories before they are recorded.
- **Sign out** refreshes the workspace's token if needed, calls `auth.revoke`, then deletes the record. When the last workspace signs out, Flack deletes the file and the key. If Slack cannot be reached, Flack deletes them anyway and says that Slack may keep the sign-in until it expires or the person removes Flack from the workspace's apps.
- Uninstalling Flack deletes the file and the key.

## Routing

A post to the wrong channel, thread or workspace is the worst failure this route can cause. Every step below either finds one destination or leaves the copy read-only.

### Order

For each Slack notification Flack forwards:

1. **Original action.** The existing check (SPEC.md section 6) finds one usable reply action. Flack uses it and makes no API call for this message.
2. **Notification identifiers.** Structured fields in the notification give the workspace, the conversation and, for a thread reply, the thread.
3. **Message match.** A search in the person's signed-in workspaces finds exactly one message that matches the notification.
4. **Read-only.** Otherwise.

Steps 2 and 3 apply to every notification that the existing check makes read-only. Notifications it rejects (group summaries, empty or context-free notifications, non-message notifications) are still not forwarded.

### Notification identifiers

Flack uses only structured fields: the notification's shortcut ID, its `LocusId`, its tag, and string values in its extras. It never uses the conversation title, message text, sender names or action labels to choose a destination.

Slack does not document which fields carry which IDs, so the mapping comes from device evidence:

1. The route field probe (build step 2) adds, for each forwarded Slack notification, a line to the test report: which of those fields are present, and how many values in each have the shape of a channel ID (`C`, `G` or `D` and 8 or more uppercase letters or digits), a workspace ID (`T` or `E`), a user ID (`U` or `W`) and a message timestamp (digits, a dot, six digits). Extras keys are listed only when they look like code constants. No values, titles or text are recorded.
2. The maintainer collects reports for each message type in NJ PBS and adds them to the device test record.
3. A field is used only if the evidence shows it gives exactly one workspace ID and one conversation ID for that message type, and a thread parent timestamp that can be told apart from the message's own timestamp for thread replies. Otherwise this step is skipped for that message type, and step 3 covers it.

When this step names a conversation, Flack uses the token for that workspace. A workspace the person has not signed in to falls through to read-only.

### Message match

When step 2 does not apply, Flack looks for the message in each signed-in workspace:

1. It takes the newest incoming message in the notification: its sender name, text and timestamp.
2. It builds a `search.messages` query from up to eight plain words of the text, quoted, sorted by timestamp, newest first, 20 results.
3. It keeps a result only when all of these hold:
   - its timestamp is within 10 seconds of the notification message's timestamp;
   - its author, looked up with `users.info`, has a display name or real name equal to the notification's sender name;
   - its text equals the notification text after both are normalized: user and channel mentions replaced by names, links replaced by their labels, HTML entities decoded and whitespace collapsed;
   - for a channel notification with a title, the result's channel name equals the title without a leading `#`;
   - if the notification has a channel ID or workspace ID in a structured field, the result has the same one.
4. Exactly one result must remain across all signed-in workspaces. None, or more than one, means read-only.
5. The destination is the result's workspace and channel. If the result's permalink has a `thread_ts` parameter, the reply goes to that thread; otherwise to the channel. A thread or channel classification that cannot be read from the result means read-only.

Slack may need a few seconds to index a new message, so Flack searches at most three times per notification: at once, after 2 seconds and after 6 seconds. It stops at the first unique match, or at the first result that is not unique. This is a lookup; a reply is never retried. Slack's documentation says search can merge several matches that are close together into one result, so the timestamp, sender, text, channel and identifier checks all apply to that one result.

Search results, user names and the match are kept in memory only. User lookups are cached for the session.

### Before the car copy

Flack resolves the route before it posts the car copy, so Android Auto offers a reply only for a certain route. A copy that needs a lookup can appear up to about seven seconds after the Slack notification. A message that stays read-only is posted read-only after the last attempt, and Android Auto may not show it, as today.

### Counting read-only cases

The test report counts how each forwarded message was routed: original action, identifiers, message match, or read-only. Read-only cases are counted by reason: not signed in to that workspace, no match, more than one match, sender or text mismatch, lookup failed, sign-in expired. The target is close to zero read-only cases for signed-in workspaces. A rise in one reason shows what to fix.

## Dispatch

1. The car reply arrives with its token, as now. The registry consumes the token once and checks that the generation is current.
2. Flack refreshes the workspace's access token if it expires within five minutes.
3. Flack calls `chat.postMessage` once with `channel`, `text` and, for a thread, `thread_ts`. Connect timeout 5 seconds, read timeout 10 seconds, off the main thread inside the receiver's `goAsync` window.
4. Results:

| Result | Status shown |
| --- | --- |
| `ok: true` and the response `channel` equals the requested one | Posted to Slack (Slack confirmed) |
| `ok: false`, or the refresh or connection failed before the request was sent | Not sent, with a fixed error category |
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

1. This design and the SPEC.md changes.
2. Route field probe in the content-free test report, shipped in the next test build.
3. Sign-in: redirect page, PKCE, Keystore token storage for several workspaces, sign-out with `auth.revoke`, the `INTERNET` permission, the phone screen and the public text changes.
4. Message match and API reply: matching and certainty rules, dispatch, the route order and the test report counts.
5. Notification identifiers: the field mapping, once the probe evidence shows one.

## What the maintainer does

1. Open https://api.slack.com/apps, choose **Create New App**, then **From a manifest**, and pick the NJ PBS workspace as the development workspace.
2. Paste [slack-app-manifest.json](slack-app-manifest.json) and create the app. Under **OAuth & Permissions**, check that PKCE is on and the redirect URL is `https://jamditis.github.io/flack/oauth/`.
3. Under **Manage Distribution**, complete Slack's checklist and turn on public distribution. Slack requires HTTPS redirect URLs and no hard-coded workspace information; Flack meets both. Do not submit it to the Marketplace. Unlisted distribution needs no Slack review.
4. Optional: under **Basic Information**, add an icon, and add the privacy page `https://jamditis.github.io/flack/privacy.html` if Slack asks for one.
5. If a workspace requires admin approval for apps, an admin of that workspace approves Flack. For NJ PBS, check this before the first test.
6. Copy the **Client ID** from **Basic Information** and set it as the repository variable `FLACK_SLACK_CLIENT_ID` (Settings, Secrets and variables, Actions, Variables). It is a variable, not a secret. Agents do not set it.
7. After build step 2 ships, run the route field probe test in [TEST_PLAN.md](TEST_PLAN.md) on the S24 Ultra and paste the report.
8. After build step 3 ships, each tester signs in from Flack's phone screen while parked.
