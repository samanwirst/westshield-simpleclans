"""Exercise the real local server, authenticated non-OP clients, packets and restart."""
from pathlib import Path
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
import urllib.request

BASE=Path(__file__).resolve().parent
SERVER=BASE/'server'
PORT=25601
sys.path.insert(0,str(BASE.parent.parent/'tests/integration'))
from clan_protocol_client import Client,status,string,vint

RESULT=BASE/'integration-results.json'
report={'scope':'Purpur 26.2, authenticated non-OP Java clients; real actionbar/gameplay/chat packets and live BlueMap markers; no physical Bedrock GUI', 'checks':[]}
clients=[]
process=None
phase=0
sequence=0
password_path=BASE/'test-password.txt'
if not password_path.exists():
    password_path.write_text(secrets.token_hex(12));password_path.chmod(0o600)
password=password_path.read_text()
last_chat={}

def save(): RESULT.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
def check(condition,label):
    report['checks'].append({'check':label,'passed':bool(condition)});save()
    print(('PASS ' if condition else 'FAIL ')+label,flush=True)
    if not condition: raise AssertionError(label)
def console(text,delay=.4):
    process.stdin.write(text+'\n');process.stdin.flush();time.sleep(delay)
def records(): return json.loads((SERVER/'plugins/TerritoryProbe/results.json').read_text())
def probe(text,label):
    offset=len(records());console('territoryprobe '+text+' '+label)
    for _ in range(40):
        values=records()[offset:]
        if any(item['label']=='error' for item in values): raise RuntimeError('Fixture probe failed')
        found=[item['value'] for item in values if item['label']==label]
        if found:return found[-1]
        time.sleep(.1)
    raise TimeoutError('Missing probe receipt '+label)
def inspect(name,label):return probe('inspect '+name,label)
def position(name,world,x,y,z):return probe(f'position {name} {world} {x} {y} {z}',f'position-{name}-{time.monotonic_ns()}')
def fixture(world,x,y,z,kind):return probe(f'fixture {world} {x} {y} {z} {kind}',f'fixture-{time.monotonic_ns()}')
def block(world,x,y,z):return probe(f'block {world} {x} {y} {z}',f'block-{time.monotonic_ns()}')
def pace(client):
    remaining=1.1-(time.monotonic()-last_chat.get(id(client),0))
    if remaining>0:time.sleep(remaining)
    last_chat[id(client)]=time.monotonic()
def command(client,text,delay=.7):pace(client);client.command(text);time.sleep(delay)
def messages(client,offset,kind=None):return [text for category,text in client.messages[offset:] if kind is None or category==kind]
def auth(client,fresh=False):
    if fresh:command(client,'register '+password+' '+password,1.2)
    command(client,'login '+password,1.2)
def chat(client,text):pace(client);client.send(9,string(text)+struct.pack('>qq',int(time.time()*1000),0)+b'\x00'+vint(0)+b'\x00\x00\x00\x00');time.sleep(.8)
def pos(x,y,z):return struct.pack('>Q',((x&0x3ffffff)<<38)|((z&0x3ffffff)<<12)|(y&0xfff))
def dig(client,x,y,z):
    global sequence
    sequence+=1;client.send(41,vint(0)+pos(x,y,z)+b'\x01'+vint(sequence));time.sleep(.8)
def use(client,x,y,z):
    global sequence
    sequence+=1;client.send(66,vint(0)+pos(x,y,z)+vint(1)+struct.pack('>fff',.5,1.,.5)+b'\x00\x00'+vint(sequence));time.sleep(.8)
def regions():
    path=SERVER/'plugins/WestShieldClanTerritories/territories.yml'
    return [] if not path.exists() else yaml.safe_load(path.read_text())['territories']
def map_state():
    deadline=time.monotonic()+90
    while time.monotonic()<deadline:
        result=probe('map TerritoryAdmin westshield',f'map-{time.monotonic_ns()}')
        if result['ready']:return result
        time.sleep(1)
    raise TimeoutError('BlueMap marker API did not become ready')
def marker(identity):return next(item for item in map_state()['markers'] if item['id']==identity)
def outline(client,tag,identity,points):
    command(client,'cterr clear')
    for x,z in points:command(client,f'cterr point {x} {z}',.2)
    command(client,f'cterr save {tag} {identity}')
def connect(name):
    client=Client('127.0.0.1',PORT,name,status('127.0.0.1',PORT)['version']['protocol']);clients.append(client)
    lock=threading.RLock();original=client.send
    def send(*args,**kwargs):
        with lock:original(*args,**kwargs)
    client.send=send
    def pump():
        try:
            while not client.closed:client.pump(.2)
        except (OSError,ValueError,EOFError):client.closed=True
    threading.Thread(target=pump,daemon=True).start()
    for _ in range(100):
        if client.state=='game':client.send(44);time.sleep(1);return client
        if client.closed:raise RuntimeError('Disconnected before play: '+name)
        time.sleep(.1)
    raise TimeoutError('No game session '+name)
def start():
    global process,phase
    phase+=1
    log=BASE/f'local-console-{phase}.log'
    java=os.environ['WESTSHIELD_JAVA_BIN']
    process=subprocess.Popen([java,'-Xms256M','-Xmx1500M','-XX:ActiveProcessorCount=2','-Dterminal.jline=false','-Dterminal.ansi=false','-jar','server.jar','nogui'],cwd=SERVER,stdin=subprocess.PIPE,stdout=log.open('w'),stderr=subprocess.STDOUT,text=True)
    report['server_pid']=process.pid;save()
    for _ in range(240):
        if process.poll() is not None:raise RuntimeError('Isolated server exited')
        if ']: Done (' in log.read_text():return
        time.sleep(.5)
    raise TimeoutError('Local startup observation timeout')
def stop():
    for client in clients:
        client.closed=True
        try:client.socket.shutdown(2)
        except OSError:pass
        try:client.close()
        except OSError:pass
    clients.clear()
    if process and process.poll() is None:
        console('stop');process.wait(timeout=60)

try:
    if RESULT.exists():raise RuntimeError('Retain the preceding diagnostic before a new attempt')
    if 'server-ip=127.0.0.1' not in (SERVER/'server.properties').read_text():raise RuntimeError('Fixture is not loopback-only')
    start()
    log=(BASE/'local-console-1.log').read_text()
    check('Enabling SimpleClans v2.19.2-westshield.1' in log and 'informational polygon territories; protection=false' in log,'Both candidate plugins load on the real 26.2 server')
    for line in (BASE.parent.parent/'config/player-permissions.txt').read_text().splitlines():
        if line.startswith('lp '):console(line,.1)
    actors={name:connect(name) for name in ['TerritoryAdmin','TerritoryUser','TerritoryLead','TerritoryOther']}
    administrator,user,leader,other=[actors[name] for name in ['TerritoryAdmin','TerritoryUser','TerritoryLead','TerritoryOther']]
    console('lp user TerritoryAdmin permission set westshieldclans.admin true')
    console('lp user TerritoryOther permission set simpleclans.leader.create true')
    command(administrator,'clanadmin create nope TerritoryLead Before Login')
    command(administrator,'cterr point 100 100')
    check(inspect('TerritoryAdmin','pre-auth')['clans']==0 and not regions(),'AuthMe prevents administrative writes before login')
    for client in actors.values():auth(client,True)
    states=[inspect(name,'initial-'+name) for name in actors]
    check(all(state['authenticated'] and not state['op'] for state in states),'Every test actor is authenticated and non-OP')
    check(states[0]['admin'] and not any(state['admin'] for state in states[1:]),'Only the designated test administrator receives the new administrative permission')
    for text in ['clan create nope Not Allowed','simpleclans:clan create nope Not Allowed','CLAN CREATE nope Not Allowed']:
        command(other,text)
    check(inspect('TerritoryOther','denied-create')['clans']==0,'Ordinary users cannot create clans, including legacy create permission and aliases')
    command(administrator,'clanadmin create poly TerritoryLead Poly Crew')
    check(inspect('TerritoryLead','assigned-leader')['clan']=='poly' and inspect('TerritoryAdmin','administrator-unaffiliated')['clan']=='','An administrator creates a clan for another authenticated leader')
    command(administrator,'clanadmin color poly #2A9D8F')
    color_tag=inspect('TerritoryLead','custom-color')['color-tag']
    check(color_tag.lower().startswith('§x§2§a§9§d§8§f'),'An administrator assigns an arbitrary RGB color in the native clan record')
    offset=len(user.messages);chat(leader,'WESTSHIELD_CLAN_CHAT_CHECK')
    packets=[bytes.fromhex(text) for kind,text in user.messages[offset:] if kind=='clan-chat-raw']
    report['chat_color_packets']=[packet.hex() for packet in packets];save()
    check(any(b'#2a9d8f' in packet.lower() for packet in packets),'Another real client receives the exact chosen RGB color in the clan chat prefix')
    command(other,'clanadmin color poly #FF0000')
    command(administrator,'clanadmin color poly #invalid')
    check(inspect('TerritoryLead','color-unchanged')['color-tag']==color_tag,'Ordinary users and invalid RGB input cannot change the clan color')
    command(leader,'clan invite TerritoryUser');command(user,'accept')
    check(inspect('TerritoryUser','joined')['group-poly'],'Ordinary leader invitations and clan group permissions still work')
    command(administrator,'clan create lost')
    console('lp user TerritoryAdmin permission unset westshieldclans.admin')
    chat(administrator,'Revoked Clan')
    check(inspect('TerritoryAdmin','revoked-conversation')['clans']==1,'Revoking administration during a creation conversation prevents its final write')
    console('lp user TerritoryAdmin permission set westshieldclans.admin true')
    command(administrator,'clan create adm Admin Council')
    check(inspect('TerritoryAdmin','native-admin-created')['clan']=='adm','The native clan create command works for a non-OP administrator')
    command(administrator,'clanadmin create other TerritoryOther Other Crew')
    check(inspect('TerritoryOther','second-assignment')['clan']=='other','An administrator already in a clan can create a separate clan for another leader')
    position('TerritoryAdmin','westshield',100,64,100)
    command(administrator,'cterr wand')
    probe('copywand TerritoryAdmin TerritoryAdmin','equip-wand')
    dig(administrator,102,63,100)
    offset=len(administrator.messages);command(administrator,'cterr selection')
    check(any('vertices: 1' in text for text in messages(administrator,offset)) and block('westshield',102,63,100)=='STONE','Native left click adds a wand vertex without breaking the selected block')
    use(administrator,102,63,100)
    offset=len(administrator.messages);command(administrator,'cterr selection')
    check(any('vertices: 0' in text for text in messages(administrator,offset)),'Native right click undoes the last wand vertex')
    position('TerritoryUser','westshield',100,64,100)
    probe('copywand TerritoryAdmin TerritoryUser','stolen-wand')
    dig(user,102,63,100)
    check(block('westshield',102,63,100)=='AIR','A non-admin holding a copied wand gets no editing ability or special block protection')
    outline(administrator,'poly','polyland',[(100,100),(120,100),(120,106),(106,106),(106,120),(100,120)])
    check(len(regions())==1 and len(regions()[0]['vertices'])==6,'An administrator saves a six-vertex concave territory')
    mapped=map_state();shape=marker('polyland-area');label=marker('polyland-name')
    check(mapped['toggleable'] and mapped['label']=='Clan territories','BlueMap exposes clan territories as a separate toggleable layer')
    check(shape['vertices']==[[100,100],[120,100],[120,106],[106,106],[106,120],[100,120]] and shape['rgb']=='#2A9D8F' and .1<shape['alpha']<.3,'The map receives the same concave polygon with the exact clan RGB and transparent fill')
    check('Poly Crew' in label['html'] and '#2A9D8F' in label['html'],'A permanent name marker is placed over the filled territory')
    before=json.dumps(regions(),sort_keys=True)
    for text in ['cterr point 300 300','cterr save poly unauthorized','cterr delete polyland','cterr assign polyland other']:
        command(user,text)
    check(json.dumps(regions(),sort_keys=True)==before,'Ordinary players cannot create, delete or reassign territories')
    position('TerritoryUser','westshield',90,64,110);time.sleep(.7)
    offset=len(user.messages);position('TerritoryUser','westshield',102,64,115);time.sleep(.7)
    check(any('Poly Crew' in text and 'Clan territory' in text for text in messages(user,offset,'actionbar')),'A real client receives the clan name in the actionbar on entry')
    offset=len(user.messages);position('TerritoryUser','westshield',115,64,115);time.sleep(.7)
    check(any('left clan territory' in text for text in messages(user,offset,'actionbar')),'The concave notch is outside and triggers a real exit actionbar')
    offset=len(user.messages);position('TerritoryUser','westshield',110,200,102);time.sleep(.7)
    check(any('Poly Crew' in text for text in messages(user,offset,'actionbar')),'Territory detection applies at high altitude without a Y restriction')
    position('TerritoryUser','westshield',110,64,102)
    fixture('westshield',112,64,102,'STONE');dig(user,112,64,102)
    check(block('westshield',112,64,102)=='AIR','A player really breaks a block inside another clan territory')
    fixture('westshield',112,64,102,'AIR');use(user,112,63,102)
    check(block('westshield',112,64,102)=='STONE','A player really places a block inside the territory')
    fixture('westshield',112,64,102,'CHEST');use(user,112,64,102)
    check(inspect('TerritoryUser','survival-container')['open']=='CHEST','Territory labels do not restrict opening a real chest')
    outline(administrator,'other','neighbor',[(120,100),(130,100),(130,106),(120,106)])
    check(len(regions())==2,'Adjacent clans may have a shared edge')
    outline(administrator,'other','overlap',[(101,101),(104,101),(104,104),(101,104)])
    check(len(regions())==2,'Overlapping interiors are refused without changing existing territories')
    offset=len(user.messages);position('TerritoryUser','westshield',125,64,103);time.sleep(.7)
    check(any('Other Crew' in text for text in messages(user,offset,'actionbar')),'Crossing directly into a neighboring clan shows its own name')
    command(administrator,'cterr clear')
    position('TerritoryAdmin','westshield_creative',600,64,600)
    command(administrator,'claim 16')
    check(probe('claim TerritoryAdmin','creative-owner')['claimed'],'The existing creative claim system still creates claims')
    outline(administrator,'poly','creative-plot',[(580,580),(630,580),(630,630),(580,630)])
    position('TerritoryUser','westshield_creative',600,64,600)
    fixture('westshield_creative',602,64,600,'AIR');use(user,602,63,600)
    check(block('westshield_creative',602,64,600)=='AIR','Clan labels do not bypass an existing creative claim')
    command(administrator,'trust [group.poly]')
    use(user,602,63,600)
    check(block('westshield_creative',602,64,600)=='STONE','Existing creative clan trust still grants native block placement')
    command(administrator,'cterr assign creative-plot other')
    check(probe('claim TerritoryUser','trust-after-label-change')['build'],'Changing a territory label does not change GriefPrevention permissions')
    check(not any(item['id'].startswith('creative-plot') for item in map_state()['markers']),'Territories from an unmapped creative dimension do not leak onto the survival map')
    probe('rename TerritoryAdmin poly RenamedCrew','rename-clan')
    offset=len(user.messages);position('TerritoryUser','westshield',102,64,115);time.sleep(.7)
    check(any('RenamedCrew' in text for text in messages(user,offset,'actionbar')),'Existing territories display the current clan name')
    command(leader,'clan disband');chat(leader,'yes')
    check(inspect('TerritoryLead','disbanded')['clan']=='','The clan disbands through its normal leader flow')
    command(administrator,'clanadmin create poly TerritoryLead Reborn Crew')
    command(administrator,'clanadmin color poly #A142F4')
    offset=len(user.messages);position('TerritoryUser','westshield',90,64,110);position('TerritoryUser','westshield',102,64,115);time.sleep(.7)
    check(not any('Reborn Crew' in text for text in messages(user,offset,'actionbar')),'Recreating a clan with the same tag does not inherit old territories')
    check(not any(item['id'].startswith('polyland') for item in map_state()['markers']),'A disbanded clan territory is also removed from BlueMap until explicitly reassigned')
    command(administrator,'cterr assign polyland poly')
    offset=len(user.messages);position('TerritoryUser','westshield',90,64,110);position('TerritoryUser','westshield',102,64,115);time.sleep(.7)
    check(any('Reborn Crew' in text for text in messages(user,offset,'actionbar')),'An administrator can explicitly reassign an old territory')
    check(marker('polyland-area')['rgb']=='#A142F4' and 'Reborn Crew' in marker('polyland-name')['html'],'Explicit reassignment updates the map color and permanent name together')
    before_restart=regions();stop();start()
    actors={name:connect(name) for name in ['TerritoryAdmin','TerritoryUser','TerritoryLead','TerritoryOther']}
    administrator,user,leader,other=[actors[name] for name in ['TerritoryAdmin','TerritoryUser','TerritoryLead','TerritoryOther']]
    for client in actors.values():auth(client)
    check(regions()==before_restart and inspect('TerritoryAdmin','restart-admin')['admin'],'Polygons, clan binding and delegated admin permission persist after restart')
    check(marker('polyland-area')['rgb']=='#A142F4' and inspect('TerritoryLead','restarted-color')['color-tag'].lower().startswith('§x§a§1§4§2§f§4'),'Custom clan RGB remains identical in the native record and map after restart')
    offset=len(user.messages);position('TerritoryUser','westshield',90,64,110);position('TerritoryUser','westshield',102,64,115);time.sleep(.7)
    check(any('Reborn Crew' in text for text in messages(user,offset,'actionbar')),'A real entry actionbar works after server restart')
    count=inspect('TerritoryUser','restart-count')['clans'];command(user,'clan create nope Still Denied')
    check(inspect('TerritoryUser','restart-denied')['clans']==count,'Creation remains admin-only after restart')
    position('TerritoryAdmin','westshield',300,64,300)
    command(administrator,'cterr clear')
    for x,z in [(300,300),(340,300),(320,330)]:command(administrator,f'cterr point {x} {z}',.2)
    command(administrator,'cterr reshape polyland')
    check(len(next(row for row in regions() if row['id']=='polyland')['vertices'])==3,'An administrator can replace an existing boundary with a different polygon')
    command(administrator,'cterr delete neighbor')
    check(not any(row['id']=='neighbor' for row in regions()),'An administrator can delete a territory without deleting the clan')
    final_log='\n'.join((BASE/f'local-console-{index}.log').read_text() for index in range(1,phase+1))
    check('Territory persistence failed' not in final_log and 'Local territory probe failed' not in final_log and 'Could not pass event' not in final_log,'No plugin/event errors occurred in the accepted scenario')
    report['status']='passed'
except BaseException as failure:
    report.update(status='failed',failure=type(failure).__name__+': '+str(failure));traceback.print_exc()
finally:
    try:stop()
    except Exception as failure:report['shutdown_error']=type(failure).__name__
    save()
if report['status']!='passed':raise SystemExit(1)
