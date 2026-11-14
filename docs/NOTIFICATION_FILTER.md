# Slack notification filtering

Slack's notification structures differ between the November 13 Android 16
SM-S906U report (#29, #40) and the November 14 Android 17 SM-S928U report (#41).
The former forwarded children. The latter forwarded nothing because all four
message-bearing notifications carried the group-summary flag. Both expose
RemoteInput actions that open Slack; those actions remain unsafe for car replies.

Flack classifies observed features without checking Android, Slack or Android
Auto version numbers. `NotificationFilter` applies these rows in order:

| Row | Observed features | Action |
| --- | --- | --- |
| EMPTY | No readable message text | Drop empty content |
| SUMMARY_WITH_CHILD | Summary flag and a readable active child with the same Android group key | Keep the child; withdraw the summary's copy |
| ALREADY_FORWARDED | Another source key already represented this exact group/message, without a live summary copy to transfer | Suppress replay and record a baseline |
| UNKNOWN_READ_ONLY | Readable text with an unfamiliar feature mix | Forward read-only; bypass both reply routes |
| CHILD_REPLACES_SUMMARY | Child matches a message already copied from a live summary | Transfer the existing car notification quietly to the child |
| SUMMARY_MESSAGE | Summary with MessagingStyle messages or a reply input, a group key and a known or absent conversation-ID shape | Forward using the existing reply safety checks |
| MESSAGE | Remaining recognized readable messages | Forward using the existing reply safety checks |

Unfamiliar mixes include plain text without a reply input, summaries without a
group key, unknown shortcut-ID shapes, and ID shapes inconsistent with the
conversation's group flag. An absent shortcut does not disable a validated
original action. The existing API route still requires an exact conversation ID,
one workspace ID and workspace confirmation. All rows retain source/profile,
readable-message and context checks; redacted or empty notifications cannot
produce a usable message.

For a summary/child pair, identity compares the Android group key, exact shortcut
and workspace IDs, conversation context, sender key and newest message's sender,
text and timestamp. These values stay in memory. A child arriving after a
summary replaces the same car-notification ID, invalidates the summary token,
preserves whether its reply was consumed, and produces no second alert. Removing
the summary cannot remove the child's copy. A different message still alerts.
Source action-sharing checks continue to disable ambiguous original reply actions.
Empty or rejected children cannot withdraw a readable summary. Other summaries
in the group are withdrawn only after the child is published successfully.

On listener connection, existing notifications establish the baseline. Paused or
disconnected-car arrivals also establish a baseline. A summary's existing message
cannot replay through a new child key. Listener disconnection clears all content
and bindings. Recent cross-key message history is capped at 50 entries, matching
the session registry's binding limit.

The test report includes aggregate row counts and the last 20 selected rows with
their observed booleans and conversation-ID shapes. It contains no IDs, group
keys, sender names or text. Per-case records are a capped in-memory ring; no
notification diagnostics are written to files, preferences or logs. Unknown rows
remain visible to the next device tester. These contracts use invented field
shapes from the reports; a physical phone/car retest remains necessary.
