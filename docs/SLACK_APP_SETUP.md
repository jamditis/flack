# Slack app setup

How the maintainer creates Flack's Slack app once and installs it in each workspace. The design is in [SLACK_SIGN_IN.md](SLACK_SIGN_IN.md). Agents do not create Slack apps or change Slack settings.

You need: a Slack account that is an admin of the first test workspace, and the Flack app on a phone.

## One-way steps

Read these before you start.

- **PKCE makes the app a public client. This cannot be undone without contacting Slack support.** The manifest turns PKCE on. Use this app only for Flack.
- **Public distribution** lets people in other workspaces sign in. Slack may not let you turn it off again after another workspace has installed the app. Turn it on only when you want a second workspace.

## Manifest

```yaml
display_information:
  name: Flack
  description: Reply from Android Auto to Slack messages that reach your phone.
oauth_config:
  redirect_urls:
    - tech.amditis.flack://oauth
  scopes:
    user:
      - chat:write
      - search:read
      - users:read
  pkce_enabled: true
settings:
  org_deploy_enabled: false
  socket_mode_enabled: false
  token_rotation_enabled: true
```

- `tech.amditis.flack://oauth` is the only redirect. It opens Flack on the phone after you sign in.
- User scopes only, no bot user. Sign-in keeps all three scopes so existing sign-ins work with API replies. Flack uses the token to confirm the workspace with `auth.test`, post a confirmed top-level reply with `chat.postMessage`, and sign out. This reply route does not search messages or read names.
- Token rotation is on: access tokens last 12 hours and Flack refreshes them when needed.

## Create the app

1. Go to https://api.slack.com/apps and choose **Create New App**, then **From a manifest**.
2. Pick the first test workspace. It becomes the app's development workspace.
3. Choose YAML, paste the manifest above, and create the app.
4. Open **OAuth & Permissions**. Check that the redirect URL is `tech.amditis.flack://oauth`, that PKCE is on, and that the user token scopes are `chat:write`, `search:read` and `users:read`. If the PKCE setting asks you to confirm the public-client change, confirm it. This is the one-way step above.
5. Open **Basic Information**. Copy the **Client ID**. Optional: add an app icon. You do not need the client secret: Flack never uses it.

## Give the Client ID to the build

6. In GitHub, open jamditis/flack, then **Settings**, **Secrets and variables**, **Actions**, **Variables**. Add a repository variable named `FLACK_SLACK_CLIENT_ID` with the Client ID. It is a variable, not a secret: the Client ID is public by design. The next release build includes it. A build without it shows "Slack sign-in is not set up in this build."

## Install in the first workspace

7. Install the release that includes sign-in.
8. Parked, open Flack. Under **Slack sign-in**, tap **Add a Slack workspace**.
9. In the browser, sign in to Slack if asked, pick the first test workspace, review the permissions and tap **Allow**. Signing in installs the app for your account. You do not need the **Install to Workspace** button in the app settings.
10. The browser opens Flack again. The screen shows "Signed in to <workspace>" and lists the workspace.

## Install in a second workspace

11. In the app settings, open **Manage Distribution**. Complete Slack's checklist and choose **Activate Public Distribution**. Do not submit the app to the Slack Marketplace: unlisted distribution needs no review.
12. If Slack refuses because the redirect URL is not HTTPS, stop and write it on #28. Flack's sign-in without a server depends on this redirect.
13. Repeat steps 8 to 10 and pick the second workspace. Flack lists both.

## Workspaces you are not an admin of

If a workspace lets only admins approve apps, Slack's sign-in page shows that approval is needed instead of **Allow**. Where the workspace allows it, the person can send a request. A workspace admin approves Flack under the workspace's app management settings. Then the person taps **Add a Slack workspace** again. Flack shows "Slack did not finish signing in" until then.

## Check it

- Each workspace shows "signed in as <your Slack name>" in Flack.
- **Copy test report** shows no workspace name, user name or token.
- **Sign out** on one workspace shows "Signed out of <workspace>", and the workspace leaves the list. If the phone is offline, Flack says Slack could not confirm the sign-out. To end it at Slack, remove Flack from that workspace's apps.
