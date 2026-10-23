"""Real local Java protocol sessions exercise SimpleClans on the full WestShield stack."""
from pathlib import Path
from datetime import datetime, timezone
import json
import os
import secrets
import struct
import subprocess
import sys
import threading
import time
import traceback
import yaml

BASE = Path(__file__).resolve().parent
SERVER = Path(os.environ.get('WESTSHIELD_TEST_SERVER', str(BASE / 'server'))).resolve()
sys.path.insert(0, str(BASE.parent))
from clan_protocol_client import Client, status, vint

JAVA = os.environ.get('WESTSHIELD_JAVA_BIN', 'java')
RESULT = BASE / 'scenario-results.json'
report = {'started_at': datetime.now(timezone.utc).isoformat(), 'checks': [], 'observations': [],
          'scope': 'Isolated full production plugin stack plus SimpleClans and local test harness; Java protocol 26.2, no OP, no Bedrock GUI'}
clients = []
process = None
sequence = 0
phase = 0
password_file = BASE / 'test-password.txt'
if not password_file.exists():
    password_file.write_text(secrets.token_hex(12))
    password_file.chmod(0o600)
password = password_file.read_text()


def save():
    RESULT.write_text(json.dumps(report, ensure_ascii=False, indent=2))


def check(value, label):
    report['checks'].append({'check': label, 'passed': bool(value)})
    save()
    print(('PASS ' if value else 'FAIL ') + label, flush=True)
    if not value:
        raise AssertionError(label)


def console(text):
    process.stdin.write(text + '\n')
    process.stdin.flush()
    time.sleep(.35)


def evidence():
    return json.loads((SERVER / 'plugins/ClanProbe/results.json').read_text())


def observation(label, value):
    report['observations'].append({'label': label, 'value': value})
    save()


def inspect(name, label):
    before = len(evidence())
    console(f'clanprobe inspect {name} {label}')
    for _ in range(30):
        for entry in evidence()[before:]:
            if entry['action'] == label:
                return entry['value']
        time.sleep(.1)
    raise RuntimeError('Missing observation ' + label)


def block(label):
    before = len(evidence())
    token = label.replace(' ', '_')
    console('clanprobe block 602 64 600 ' + token)
    return next(entry['value'] for entry in evidence()[before:] if entry['action'] == token)


def start():
    global process, phase
    properties = (SERVER / 'server.properties').read_text()
    assert 'server-ip=127.0.0.1' in properties and 'server-port=25597' in properties, 'Disposable loopback fixture required'
    phase += 1
    log = BASE / f'console-{phase}.log'
    process = subprocess.Popen([JAVA, '-Xms256M', '-Xmx1200M', '-Dterminal.jline=false',
                                '-Dterminal.ansi=false', '-jar', 'server.jar', 'nogui'],
                               cwd=SERVER, stdin=subprocess.PIPE, stdout=log.open('w'), stderr=subprocess.STDOUT, text=True)
    report['server_pid'] = process.pid
    save()
    for _ in range(160):
        if process.poll() is not None:
            raise RuntimeError('Local server exited during startup')
        if 'Done (' in log.read_text():
            return
        time.sleep(.5)
    raise TimeoutError('Local startup not yet observed')


def close(client):
    client.closed = True
    try:
        client.socket.shutdown(2)
    except OSError:
        pass
    client.close()
    if client in clients:
        clients.remove(client)


def stop():
    for client in list(clients):
        close(client)
    if process and process.poll() is None:
        console('stop')
        process.wait(timeout=50)


def connect(name):
    protocol = status('127.0.0.1', 25597)['version']['protocol']
    client = Client('127.0.0.1', 25597, name, protocol)
    clients.append(client)
    lock = threading.RLock()
    original = client.send
    def send(*args, **kwargs):
        with lock:
            original(*args, **kwargs)
    client.send = send
    def pump():
        try:
            while not client.closed:
                client.pump(.2)
        except (OSError, ValueError):
            client.closed = True
    threading.Thread(target=pump, daemon=True).start()
    for _ in range(80):
        if client.state == 'game':
            client.send(44)
            time.sleep(1.2)
            return client
        if client.closed:
            raise RuntimeError('Client disconnected before game: ' + name)
        time.sleep(.1)
    raise TimeoutError('No game session: ' + name)


def command(client, text):
    previous = len(client.messages)
    client.command(text)
    time.sleep(.7)
    if not text.startswith(('login ', 'register ')):
        observation('command ' + text, [str(value)[:1200] for _, value in client.messages[previous:] if value])


def authenticate(client, fresh=False):
    if fresh:
        command(client, 'register ' + password + ' ' + password)
    command(client, 'login ' + password)


def position(name):
    console('clanprobe position ' + name)
    time.sleep(.4)


def pos(x, y, z):
    return struct.pack('>Q', ((x & 0x3ffffff) << 38) | ((z & 0x3ffffff) << 12) | (y & 0xfff))


def interact(client, y):
    global sequence
    sequence += 1
    client.send(53, struct.pack('>h', 0))
    client.send(66, vint(0) + pos(602, y, 600) + vint(1) + struct.pack('>fff', .5, 1., .5)
                + b'\x00\x00' + vint(sequence))
    time.sleep(.6)


def place(client, name, allowed, label):
    position(name)
    console('clanprobe fixture ' + name + ' AIR')
    before = len(evidence())
    interact(client, 63)
    value = block(label)
    observation(label + ' events', evidence()[before:])
    check(value == ('STONE' if allowed else 'AIR'), label)


def destroy(client, name, allowed, label):
    global sequence
    position(name)
    console('clanprobe fixture ' + name + ' STONE')
    before = len(evidence())
    sequence += 1
    client.send(41, vint(0) + pos(602, 64, 600) + b'\x01' + vint(sequence))
    time.sleep(.7)
    value = block(label)
    observation(label + ' events', evidence()[before:])
    check(value == ('AIR' if allowed else 'STONE'), label)


def container(client, name, allowed, label):
    position(name)
    console('clanprobe fixture ' + name + ' CHEST')
    before = len(evidence())
    interact(client, 64)
    state = inspect(name, label.replace(' ', '_'))
    observation(label + ' events', evidence()[before:])
    check((state['open-inventory'] == 'CHEST') == allowed, label)



def run():
    try:
        assert not RESULT.exists(), 'Retain the preceding attempt before rerunning'
        start()
        check('[SimpleClans] Enabling SimpleClans v2.19.2-westshield.1' in (BASE / 'console-1.log').read_text(), 'WestShield SimpleClans fork loads on Purpur 26.2 with GriefPrevention 16.18.7')
        denied = ['simpleclans.member.coords', 'simpleclans.member.vitals', 'simpleclans.member.home',
                  'simpleclans.member.bank', 'simpleclans.leader.home-set', 'simpleclans.leader.regroup.me',
                  'simpleclans.leader.regroup.home', 'simpleclans.leader.war', 'simpleclans.leader.ally',
                  'simpleclans.leader.rival', 'simpleclans.anyone.leaderboard', 'simpleclans.member.stats',
                  'simpleclans.member.land']
        for permission in denied:
            console('lp group default permission set ' + permission + ' false')
        owner = connect('ClanProbeOwner')
        member = connect('ClanProbeMember')
        other = connect('ClanProbeOther')
        command(owner, 'clan create nope NotAuthorized')
        check(inspect('ClanProbeOwner', 'preauth')['clan'] == '', 'AuthMe blocks clan creation before login')
        survival = None
        for client in [owner, member, other]:
            authenticate(client, fresh=True)
            if client is owner:
                survival = inspect('ClanProbeOwner', 'survival-before')
            command(client, 'creative')
        check(all(inspect(name, 'nonop-' + name)['authenticated'] and not inspect(name, 'op-' + name)['op']
                  for name in ['ClanProbeOwner', 'ClanProbeMember', 'ClanProbeOther']), 'Three ordinary non-OP players log in and enter creative')
        position('ClanProbeOwner')
        command(owner, 'claim 16')
        check(inspect('ClanProbeOwner', 'owner-claim')['claimed'], 'Ordinary owner creates a creative claim')
        place(owner, 'ClanProbeOwner', True, 'Owner places a real block through the client protocol')
        destroy(owner, 'ClanProbeOwner', True, 'Owner breaks a real block through the client protocol')
        place(other, 'ClanProbeOther', False, 'Outsider cannot place a real block')
        destroy(other, 'ClanProbeOther', False, 'Outsider cannot break a real block')
        command(owner, 'clan create westshield WestShield Builders')
        created = inspect('ClanProbeOwner', 'created')
        check(created['clan'] == 'westshield' and created['clan-name'] == 'WestShield Builders', 'Ordinary player creates a named clan with a short tag')
        command(owner, 'clan invite ClanProbeMember')
        command(member, 'accept')
        joined = inspect('ClanProbeMember', 'joined')
        check(joined['clan'] == 'westshield' and joined['members'] == 2, 'Invited player joins the clan')
        check(joined['group-westshield'], 'New clan receives its live group permission immediately, without reload or reconnect')
        command(member, 'clan roster')
        position('ClanProbeOwner')
        command(owner, 'trust [group.westshield]')
        group = inspect('ClanProbeMember', 'shared-group')
        check(group['raw-build'] and group['raw-container'] and group['raw-build-low'] and group['raw-build-high'], 'Native GriefPrevention grants clan access across the full claim height')
        check(not group['wilderness-build'], 'Clan membership does not grant building in unclaimed creative land')
        place(member, 'ClanProbeMember', True, 'Clan member places a real block in the shared claim')
        destroy(member, 'ClanProbeMember', True, 'Clan member breaks a real block in the shared claim')
        container(member, 'ClanProbeMember', True, 'Clan member opens a real claimed chest')
        container(other, 'ClanProbeOther', False, 'Outsider cannot open the claimed chest')
        place(other, 'ClanProbeOther', False, 'Sharing with the clan does not admit an outsider')
        command(member, 'clan resign confirm')
        check(inspect('ClanProbeMember', 'resigned')['clan'] == '', 'Member leaves the clan')
        place(member, 'ClanProbeMember', False, 'Leaving the clan immediately removes native build access')
        command(owner, 'clan invite ClanProbeMember')
        command(member, 'accept')
        place(member, 'ClanProbeMember', True, 'Rejoining restores clan access')
        command(owner, 'clan kick ClanProbeMember')
        check(inspect('ClanProbeMember', 'kicked')['clan'] == '', 'Leader excludes a member')
        destroy(member, 'ClanProbeMember', False, 'Exclusion immediately removes native break access')
        command(owner, 'clan invite ClanProbeMember')
        command(member, 'accept')
        position('ClanProbeMember')
        close(member)
        time.sleep(1)
        member = connect('ClanProbeMember')
        check(not inspect('ClanProbeMember', 'rejoin-preauth')['authenticated'], 'Reconnect requires AuthMe again')
        place(member, 'ClanProbeMember', False, 'Clan membership does not allow block placement before AuthMe login')
        destroy(member, 'ClanProbeMember', False, 'Clan membership does not allow block breaking before AuthMe login')
        authenticate(member)
        check(inspect('ClanProbeMember', 'rejoin-auth')['clan'] == 'westshield', 'Clan membership survives reconnect')
        close(owner)
        place(member, 'ClanProbeMember', True, 'Clan building works while claim owner is offline')
        owner = connect('ClanProbeOwner')
        authenticate(owner)
        position('ClanProbeOwner')
        command(owner, 'untrust [group.westshield]')
        place(member, 'ClanProbeMember', False, 'Owner revokes shared clan access')
        state = inspect('ClanProbeOwner', 'minimal-features')
        check(all(not state[permission] for permission in denied if permission in state), 'Coordinates, vitals, home teleport, bank and war permissions are disabled')
        command(owner, 'trust [group.westshield]')
        other = connect('ClanProbeOther') if other.closed else other
        command(other, 'clan create other Other Builders')
        check(inspect('ClanProbeOther', 'other-clan')['group-other'], 'A second freshly created clan immediately receives its independent group permission')
        position('ClanProbeOther')
        command(other, 'trust [group.other]')
        place(other, 'ClanProbeOther', False, 'Another clan cannot grant itself access to a foreign claim')
        position('ClanProbeOwner')
        command(owner, 'trust [group.other]')
        place(other, 'ClanProbeOther', True, 'Owner can grant access to a specifically selected different clan')
        position('ClanProbeOwner')
        command(owner, 'untrust [group.other]')
        command(owner, 'containertrust [group.other]')
        container(other, 'ClanProbeOther', True, 'A selected clan can receive container-only access')
        place(other, 'ClanProbeOther', False, 'Container-only clan permission does not grant building')
        position('ClanProbeOwner')
        before_creative = inspect('ClanProbeOwner', 'creative-before-survival')
        command(owner, 'survival')
        returned = inspect('ClanProbeOwner', 'survival-restored')
        check(returned['clan'] == 'westshield' and returned['world'] == 'westshield' and returned['mode'] == 'SURVIVAL'
              and not returned['claims-enabled-here'], 'Clan identity is shared across worlds while claim protection remains creative-only')
        check(all(returned[key] == survival[key] for key in ['inventory', 'ender', 'location']), 'Clan commands preserve survival inventory, Ender Chest and return location')
        command(owner, 'creative')
        restored = inspect('ClanProbeOwner', 'creative-restored')
        check(all(restored[key] == before_creative[key] for key in ['inventory', 'ender', 'location']), 'Creative inventory and location remain separate after clan operations')
        stop()
        start()
        owner = connect('ClanProbeOwner'); member = connect('ClanProbeMember')
        for client in [owner, member]: authenticate(client)
        check(inspect('ClanProbeOwner', 'restart-owner')['clan'] == 'westshield'
              and inspect('ClanProbeMember', 'restart-member')['clan'] == 'westshield', 'Clan identity persists after a clean server restart')
        place(member, 'ClanProbeMember', True, 'Shared claim access persists after restart')
        check(inspect('ClanProbeMember', 'group-after-restart')['group-westshield'], 'Clan permission reattaches after server restart')
        position('ClanProbeOwner')
        command(owner, 'clan resign confirm')
        check(inspect('ClanProbeMember', 'disbanded')['clan'] == '' and not inspect('ClanProbeMember', 'disbanded-group')['group-westshield'], 'Disbanding the clan removes membership and live group permissions')
        place(member, 'ClanProbeMember', False, 'Disbanding the clan removes native block access')
        log_text = '\n'.join((BASE / f'console-{index}.log').read_text() for index in range(1, phase + 1))
        check('NoSuchFieldException: locale' not in log_text and "Can't read players locale" not in log_text, 'Supported locale API fixes the old ACF player-language exception')
        report['status'] = 'passed'
    except BaseException as failure:
        report['status'] = 'failed'
        report['failure'] = type(failure).__name__ + ': ' + str(failure)
        traceback.print_exc()
    finally:
        try: stop()
        except Exception as failure: report['shutdown_error'] = type(failure).__name__
        report['finished_at'] = datetime.now(timezone.utc).isoformat()
        save()
    if report['status'] != 'passed':
        raise SystemExit(1)


if __name__ == "__main__":
    run()
