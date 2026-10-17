"""Verify access-only clan trust with actual lever interactions in the isolated fixture."""
from pathlib import Path
import time
import traceback
import run_scenario as test

test.RESULT = Path(__file__).with_name('access-results.json')
test.report['checks'] = []
test.report['observations'] = []
test.report['scope'] = 'Supplemental native mechanism-only clan trust on the isolated full WIUT stack'
test.phase = 3


def lever(client, name, expected, label):
    test.position(name)
    test.console('execute in minecraft:wiut_creative run setblock 602 64 600 minecraft:lever[face=floor,facing=west,powered=false]')
    test.interact(client, 64)
    log = test.BASE / 'console-4.log'
    previous = log.read_text()
    test.console('execute in minecraft:wiut_creative if block 602 64 600 minecraft:lever[powered=true]')
    output = log.read_text()[len(previous):]
    assert 'Test passed' in output or 'Test failed' in output, 'Missing native lever observation'
    test.observation(label + ' native output', output)
    test.check(('Test passed' in output) == expected, label)


try:
    assert not test.RESULT.exists(), 'Retain or observe the existing attempt first'
    test.start()
    # The startup log precedes readiness of the status listener on this engine.
    for attempt in range(30):
        try:
            test.status('127.0.0.1', 25597)
            break
        except (EOFError, OSError):
            if attempt == 29: raise
            time.sleep(.2)
    owner = test.connect('ClanProbeOwner')
    member = test.connect('ClanProbeMember')
    other = test.connect('ClanProbeOther')
    names = ['ClanProbeOwner', 'ClanProbeMember', 'ClanProbeOther']
    for client in [owner, member, other]: test.authenticate(client)
    states = [test.inspect(name, 'access-auth-' + name) for name in names]
    test.check(all(state['authenticated'] and not state['op'] for state in states), 'Mechanism checks use three authenticated non-OP players')
    if states[0]['clan']:
        test.command(owner, 'clan resign confirm')
    test.command(owner, 'clan create wiut WIUT Builders')
    test.command(owner, 'clan invite ClanProbeMember')
    test.command(member, 'accept')
    test.position('ClanProbeOwner')
    test.command(owner, 'untrust [group.wiut]')
    test.command(owner, 'untrust [group.other]')
    lever(member, 'ClanProbeMember', False, 'A clan member cannot activate a protected lever before permission is granted')
    test.position('ClanProbeOwner')
    test.command(owner, 'accesstrust [group.wiut]')
    lever(member, 'ClanProbeMember', True, 'Mechanism-only clan trust permits a native lever interaction')
    test.place(member, 'ClanProbeMember', False, 'Mechanism-only clan trust does not grant block placement')
    test.container(member, 'ClanProbeMember', False, 'Mechanism-only clan trust does not grant chest access')
    lever(other, 'ClanProbeOther', False, 'Mechanism-only trust does not admit another clan')
    test.position('ClanProbeOwner')
    test.command(owner, 'untrust [group.wiut]')
    lever(member, 'ClanProbeMember', False, 'Revoking clan trust immediately removes lever access')
    test.command(owner, 'clan resign confirm')
    test.report['status'] = 'passed'
except BaseException as failure:
    test.report['status'] = 'failed'
    test.report['failure'] = type(failure).__name__ + ': ' + str(failure)
    traceback.print_exc()
finally:
    try: test.stop()
    except Exception as failure: test.report['shutdown_error'] = type(failure).__name__
    test.save()
if test.report['status'] != 'passed': raise SystemExit(1)
