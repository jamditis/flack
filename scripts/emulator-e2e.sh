#!/usr/bin/env bash
# End-to-end check on a real Android system image, with the test-only fake Slack:
# every text conversation gets a car copy, safe replies reach their exact source
# once, ambiguous copies have no reply, and turning Flack off removes copies.
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

step "Group DMs, public/private channels and two threads become separate car copies"
for item in group-project:GROUP channel-kites:CHANNEL private-paper:PRIVATE_CHANNEL thread-launch:THREAD thread-folding:THREAD; do
  conversation=${item%:*}
  kind=${item#*:}
  before=$(flack_copies)
  fixture_post "$conversation" "Riley Example" "Synthetic paper update." "$kind"
  wait_for 15 has_new_copy || fail "no car copy for $conversation"
done

step "Replies in reverse arrival order keep their conversation and thread, with duplicate callbacks refused"
for conversation in thread-folding thread-launch private-paper channel-kites group-project; do
  reply="e2e-$conversation-$RANDOM"
  adb shell "am broadcast -n $FLACK/.DebugControlReceiver -a $FLACK.debug.REPLY_FROM_CAR --es conversation $conversation --es text $reply --ez repeat true" > /dev/null
  got_target_reply() { adb logcat -d -s FlackFixture:I | grep -q "reply conversation=$conversation text=$reply"; }
  wait_for 15 got_target_reply || fail "reply did not reach $conversation"
  sleep 1
  count=$(adb logcat -d -s FlackFixture:I | grep -c "text=$reply" || true)
  [ "$count" -eq 1 ] || fail "reply for $conversation dispatched $count times"
done

step "Ambiguous actions and unknown routing are read-only"
for item in mixed-paper:TWO_ACTIONS unknown-paper:UNKNOWN; do
  conversation=${item%:*}
  kind=${item#*:}
  before=$(flack_copies)
  fixture_post "$conversation" "Sam Example" "Read this without replying." "$kind"
  wait_for 15 has_new_copy || fail "no read-only copy for $conversation"
  adb logcat -c
  adb shell "am broadcast -n $FLACK/.DebugControlReceiver -a $FLACK.debug.REPLY_FROM_CAR --es conversation $conversation --es text should-not-send" > /dev/null
  refused_read_only() { adb logcat -d -s FlackDebug:W | grep -q "car copy has no reply action"; }
  wait_for 10 refused_read_only || fail "$conversation still offers a reply"
  if adb logcat -d -s FlackFixture:I | grep -q "text=should-not-send"; then fail "read-only copy sent a reply"; fi
done

step "Turning Flack off removes every car copy"
flack_configure false false
no_copies() { [ "$(flack_copies)" -eq 0 ]; }
wait_for 15 no_copies || fail "car copies remained after turning Flack off"

echo "PASS: emulator end-to-end"
