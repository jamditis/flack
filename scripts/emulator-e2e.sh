#!/usr/bin/env bash
# End-to-end check on a real Android system image, with the test-only fake Slack:
# a DM becomes a car copy, a car reply reaches the source app once, group
# messages are not forwarded, and turning Flack off removes its copies.
# Needs one running emulator or device on adb, and debug builds of :app and :fixture.
set -euo pipefail

FLACK=tech.amditis.flack
FIXTURE=tech.amditis.flack.fixture
FLACK_APK=${1:-app/build/outputs/apk/debug/app-debug.apk}
FIXTURE_APK=${2:-fixture/build/outputs/apk/debug/fixture-debug.apk}

step() { echo "== $*"; }
fail() { echo "FAIL: $*"; adb logcat -d -s FlackFixture:I FlackDebug:I | tail -40; exit 1; }

flack_copies() {
  adb shell dumpsys notification --noredact | grep -c "NotificationRecord(.*pkg=$FLACK " || true
}

wait_for() { # wait_for <seconds> <command...>
  local limit=$1; shift
  for _ in $(seq "$limit"); do
    if "$@"; then return 0; fi
    sleep 1
  done
  return 1
}

fixture_post() { # fixture_post <conversation> <sender> <text> <kind>
  adb shell "am broadcast -n $FIXTURE/.FixtureCommandReceiver -a $FIXTURE.POST --es conversation '$1' --es sender '$2' --es text '$3' --es kind $4" > /dev/null
}

flack_configure() { # flack_configure <enabled> <car_only>
  adb shell "am broadcast -n $FLACK/.DebugControlReceiver -a $FLACK.debug.CONFIGURE --ez enabled $1 --ez car_only $2" > /dev/null
}

step "Install both apps"
adb install -r -t "$FLACK_APK" > /dev/null
adb install -r -t "$FIXTURE_APK" > /dev/null
adb shell pm grant "$FLACK" android.permission.POST_NOTIFICATIONS
adb shell pm grant "$FIXTURE" android.permission.POST_NOTIFICATIONS
adb logcat -c

step "Grant notification access and turn forwarding on (no car needed)"
adb shell cmd notification allow_listener "$FLACK/$FLACK.SlackListenerService"
flack_configure true false
listener_bound() { adb shell dumpsys notification | grep -q "$FLACK/$FLACK.SlackListenerService"; }
wait_for 30 listener_bound || fail "listener never bound"
sleep 3

step "A new DM becomes a car copy"
before=$(flack_copies)
fixture_post dm-riley "Riley Example" "Lunch at noon?" DM
has_new_copy() { [ "$(flack_copies)" -gt "$before" ]; }
wait_for 30 has_new_copy || fail "no car copy appeared for the DM"

step "A car reply reaches the source app once"
reply="e2e-$RANDOM-$RANDOM"
adb shell "am broadcast -n $FLACK/.DebugControlReceiver -a $FLACK.debug.REPLY_FROM_CAR --es conversation dm-riley --es text $reply" > /dev/null
got_reply() { adb logcat -d -s FlackFixture:I | grep -q "reply conversation=dm-riley text=$reply"; }
wait_for 30 got_reply || fail "the fake Slack never received the reply"
sleep 3
count=$(adb logcat -d -s FlackFixture:I | grep -c "text=$reply" || true)
[ "$count" -eq 1 ] || fail "reply delivered $count times, expected 1"

step "A reply that names no fake Slack conversation is refused"
adb shell "am broadcast -n $FLACK/.DebugControlReceiver -a $FLACK.debug.REPLY_FROM_CAR --es conversation dm-nobody --es text should-not-send" > /dev/null
sleep 3
if adb logcat -d -s FlackFixture:I | grep -q "text=should-not-send"; then fail "an untargeted test reply was sent"; fi
adb logcat -d -s FlackDebug:W | grep -q "refused" || fail "the debug hook did not report refusing the reply"

step "A group message is not forwarded"
before=$(flack_copies)
fixture_post group-project "Riley Example" "Notes are in the shared folder." GROUP
sleep 5
[ "$(flack_copies)" -eq "$before" ] || fail "a group message was forwarded"

step "A message with two reply actions is not forwarded"
fixture_post dm-thread "Sam Example" "See the thread above." TWO_ACTIONS
sleep 5
[ "$(flack_copies)" -eq "$before" ] || fail "an ambiguous message was forwarded"

step "Turning Flack off removes every car copy"
flack_configure false false
no_copies() { [ "$(flack_copies)" -eq 0 ]; }
wait_for 15 no_copies || fail "car copies remained after turning Flack off"

echo "PASS: emulator end-to-end"
