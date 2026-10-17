"""Confirm the allowlisted player command set, tagged native chat and disband prompt."""
from pathlib import Path
import json
import struct
import time
import traceback
import yaml
import run_scenario as test
from clan_protocol_client import string, vint

test.RESULT = Path(__file__).with_name('minimal-results.json')
test.report['checks'] = []
test.report['observations'] = []
test.report['scope'] = 'Supplemental ordinary-player allowlist and native chat verification on the full isolated stack'
test.phase = 2
allow = ['simpleclans.anyone.list', 'simpleclans.anyone.profile', 'simpleclans.anyone.roster',
         'simpleclans.anyone.lookup', 'simpleclans.leader.create', 'simpleclans.leader.invite',
         'simpleclans.leader.kick', 'simpleclans.leader.disband', 'simpleclans.member.can-join',
         'simpleclans.member.profile', 'simpleclans.member.roster', 'simpleclans.member.lookup',
         'simpleclans.member.resign']


def chat(client, text):
    client.send(9, string(text) + struct.pack('>qq', int(time.time() * 1000), 0)
                + b'\x00' + vint(0) + b'\x00\x00\x00\x00')
    time.sleep(1)


try:
    assert not test.RESULT.exists(), 'Observe or retain the preceding attempt first'
    config = test.SERVER / 'plugins/SimpleClans/config.yml'
    values = yaml.safe_load(config.read_text())
    values['clanchat']['enable'] = False
    values['allychat']['enable'] = False
    config.write_text(yaml.safe_dump(values, allow_unicode=True, sort_keys=False))
    test.start()
    test.console('lp group default permission set simpleclans.* false')
    for permission in allow:
        test.console('lp group default permission set ' + permission + ' true')
    owner = test.connect('ClanProbeOwner')
    member = test.connect('ClanProbeMember')
    other = test.connect('ClanProbeOther')
    for client in [owner, member, other]: test.authenticate(client)
    if test.inspect('ClanProbeOwner', 'prior-minimal-clan')['clan']:
        test.command(owner, 'clan resign confirm')
    test.command(owner, 'clan create wiut WIUT Builders')
    test.command(owner, 'clan invite ClanProbeMember')
    test.command(member, 'accept')
    joined = test.inspect('ClanProbeMember', 'minimal-joined')
    test.check(joined['clan'] == 'wiut' and joined['group-wiut'], 'Allowlisted create, invite and accept work without broad player permissions')
    test.position('ClanProbeOwner')
    test.command(owner, 'trust [group.wiut]')
    test.place(member, 'ClanProbeMember', True, 'Native clan claim access works with the minimal command permissions')
    state = test.inspect('ClanProbeOwner', 'minimal-permissions')
    blocked = ['simpleclans.member.coords', 'simpleclans.member.vitals', 'simpleclans.member.home',
               'simpleclans.member.bank', 'simpleclans.leader.home-set', 'simpleclans.leader.regroup.me',
               'simpleclans.leader.war']
    test.check(all(not state[key] for key in blocked), 'Unused coordinate, vitals, home, regroup, bank and war permissions are absent')
    before = test.inspect('ClanProbeOwner', 'before-disabled-home')
    for text in ['clan coords', 'clan home', 'clan war start other', 'clan bank status']:
        test.command(owner, text)
    after = test.inspect('ClanProbeOwner', 'after-disabled-home')
    test.check(before['world'] == after['world'] and before['location'] == after['location'], 'Disabled feature commands cannot teleport the player')
    previous = len(member.messages)
    chat(owner, 'WIUT_CLAN_CHAT_CHECK')
    packets = [bytes.fromhex(value) for kind, value in member.messages[previous:] if kind == 'clan-chat-raw']
    test.observation('Tagged native chat packets received', [packet.hex() for packet in packets])
    test.check(any(b'WIUT_CLAN_CHAT_CHECK' in packet and b'wiut' in packet.lower() for packet in packets), 'Another real protocol client receives ordinary chat with the clan tag')
    test.command(owner, 'clan disband')
    chat(owner, 'yes')
    final = test.inspect('ClanProbeOwner', 'prompt-disbanded')
    test.check(final['clan'] == '' and not final['group-wiut'], 'Native chat confirms the disband prompt and revokes group permission')
    test.place(member, 'ClanProbeMember', False, 'A disbanded clan loses claim access under the minimal configuration')
    config_values = yaml.safe_load(config.read_text())
    Path(__file__).with_name('minimal-config.yml').write_text(yaml.safe_dump(config_values, sort_keys=False, allow_unicode=True))
    Path(__file__).with_name('player-permissions.json').write_text(json.dumps({'deny': ['simpleclans.*'], 'allow': allow}, indent=2))
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
