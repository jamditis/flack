# Device test record

Status: not run. Complete with observed results; do not mark a planned step as a pass.

## Environment

Fill in one column per target phone. Record the exact installed versions on the day of the test. "Latest" or "kept up to date" is not a recorded value: the two phones can be on different Android and One UI versions.

| Field | Phone A (primary) | Phone B (second tester) |
| --- | --- | --- |
| Date and tester | Pending | Pending |
| Phone model | Samsung Galaxy S24 Ultra; exact model number pending | Samsung Galaxy S21; exact model number pending |
| Android version | Pending | Pending |
| One UI version | Pending | Pending |
| Slack version | Pending | Pending |
| Slack profile | Personal or managed/work profile; pending | Personal or managed/work profile; pending |
| Android Auto version | Pending | Pending |
| Google/Gemini version and active assistant | Pending | Pending |
| Car/head-unit model and software | Pending | Pending |
| Connection | Wired or wireless; pending | Wired or wireless; pending |
| Build version/commit, if testing bridge | Not applicable until implementation | Not applicable until implementation |
| Installation method | Pending | Pending |
| Android Auto "Unknown sources" developer setting | Pending | Pending |
| Permission and background settings | Pending | Pending |
| Charging/power conditions | Pending | Pending |

## Native route

The first informal check was reported by the maintainer on November 12, 2026, without recorded phone or software versions. Repeat it on each phone and fill in the versions above before treating it as a recorded result.

| Check | Result | Observation |
| --- | --- | --- |
| Fresh Slack notification reaches phone | Not run | |
| Phone assistant reads intended notification | Not run | |
| Phone assistant reply reaches correct DM | Not run | |
| Car assistant reads intended notification | Reported, not recorded | Maintainer report: Gemini reads Slack notifications in the car |
| Car assistant reply reaches correct DM | Reported fail, not recorded | Maintainer report: no voice reply is offered for Slack, unlike text messages and other messaging apps |
| Readback and cancel work | Not run | |
| Locked-phone repeat works | Not run | |

Decision: provisional fail of G0 by maintainer report. The reported limitation is that Android Auto offers no reply action for Slack notifications. Proceed to G1 and record versions when repeating the native check.

## Bridge route

| Gate | Result | Evidence summary |
| --- | --- | --- |
| G1 Original Slack inline capability | Not run | |
| G2 One DM round trip | Not run | |
| G3 Concurrency and thread routing | Not run | |
| G4 Confirmation and honest outcomes | Not run | |
| G5 Recovery and privacy | Not run | |
| Three-hour endurance | Not run | |

Record every exercised R01–R36 case separately, including unexpected behavior and repeat conditions. Use a synthetic test label rather than real conversation identifiers. Record actual head-unit wording where relevant, with no private message content.

## Support decision

| Message type | Phone A (S24 Ultra) | Phone B (S21) | Limits |
| --- | --- | --- | --- |
| DM | Not yet tested | Not yet tested | Confirm exact destination, readback/cancel, one dispatch |
| Group DM | Not yet tested | Not yet tested | Readout and reply to the same group |
| Public channel mention | Not yet tested | Not yet tested | Readout and reply to the same channel |
| Public channel without mention | Not yet tested | Not yet tested | Enable notifications for the synthetic channel |
| Private channel | Not yet tested | Not yet tested | Readout and reply to the same private channel |
| Existing thread reply | Not yet tested | Not yet tested | Two threads in one channel; reply stays in the selected thread |
| Mixed channel/thread reply actions | Not yet tested | Not yet tested | Readout with no reply offered; neither destination receives text |
| Unknown routing or missing group title | Not yet tested | Not yet tested | Readout with no reply offered |
| Read-only copy on Android Auto | Not yet tested | Not yet tested | Verify the host reads MessagingStyle without a reply action |

Release decision: pending. Keep sensitive evidence local and publish only a sanitized summary with exact software versions.
