"""Purpur loopback acceptance. Requires a disposable synthetic template; never uses production."""
from pathlib import Path
import argparse,base64,hashlib,io,json,re,secrets,shutil,socket,sqlite3,struct,subprocess,sys,threading,time,uuid,zlib
import nbtlib,yaml
parser=argparse.ArgumentParser();parser.add_argument('--template',type=Path,required=True);parser.add_argument('--server-root',type=Path,required=True);parser.add_argument('--output',type=Path,required=True);parser.add_argument('--java',required=True);parser.add_argument('--confirm-synthetic-template',action='store_true',required=True);args=parser.parse_args()
F=Path(__file__).resolve().parents[2];R=args.output.resolve();S=R/'server';SOURCE=args.template.resolve();assert not S.exists(),'Keep previous fixture evidence';assert 'server-ip=127.0.0.1' in (SOURCE/'server.properties').read_text();R.mkdir(parents=True,exist_ok=True);R.chmod(0o700)
shutil.copytree(SOURCE,S,symlinks=True,ignore=shutil.ignore_patterns('logs','*.log','bluemap','backups'))
for p in (S/'plugins').glob('*test-only.jar'):
 if p.name!='AutoProbe-test-only.jar':p.unlink()
for rel in ['server.properties','Caddyfile','plugins/Geyser-Spigot/config.yml','plugins/BlueMap/webserver.conf','plugins/Plan/config.yml','plugins/SimpleVoice-Geyser/config.yml','plugins/voicechat/voicechat-server.properties']:
 p=S/rel;s=p.read_text().replace(str(SOURCE),str(S))
 for i in range(7):s=s.replace(str(25711+i),str(25721+i))
 s=s.replace('0.0.0.0','127.0.0.1');p.write_text(s)
for p in (S/'plugins/BlueMap').rglob('*.conf'):p.write_text(p.read_text().replace(str(SOURCE),str(S)))
for name in ['ops.json','banned-players.json','banned-ips.json']:(S/name).write_text('[]')
p=S/'plugins/LightAntiCheat-Plus/config.yml';d=yaml.safe_load(p.read_text());d['checks']['packet']['badpackets']['badpackets_c']['enabled']=False;p.write_text(yaml.safe_dump(d,sort_keys=False))
uid=lambda name:str(uuid.UUID(bytes=hashlib.md5(('OfflinePlayer:'+name).encode()).digest(),version=3))
(S/'whitelist.json').write_text(json.dumps([{'name':name,'uuid':uid(name)} for name in ['LootOne','LootTwo','LootNew']]))
jar=F/'english/build/WestShieldLoot-1.0.0.jar';shutil.copy2(jar,S/'plugins'/jar.name)
cp=[*sorted((args.server_root/'libraries').rglob('*.jar')),*sorted((args.server_root/'versions').rglob('purpur-*.jar')),*sorted((S/'plugins').glob('*.jar'))];classes=R/'probe-classes';classes.mkdir(exist_ok=True)
subprocess.run(['javac','--release','25','-cp',':'.join(map(str,cp)),'-d',str(classes),str(Path(__file__).with_name('LootProbe.java'))],check=True)
(classes/'plugin.yml').write_text("name: LootProbe\nversion: test-only\nmain: LootProbe\napi-version: '26.2'\ndepend: [WestShieldLoot, AuthMe, floodgate]\ncommands:\n  lootprobe:\n    permission: lootprobe.admin\npermissions:\n  lootprobe.admin:\n    default: op\n")
subprocess.run(['jar','--create','--file',str(S/'plugins/LootProbe-test-only.jar'),'-C',str(classes),'.'],check=True)
sys.path.insert(0,str(F/'tests/integration'));from clan_protocol_client import Client,status,string,vint,vi
report={'checks':[],'production_mutations':False,'physical_bedrock_ui_tested':False,'synthetic_java_movement_exemptions':True,'artifact_sha256':hashlib.sha256(jar.read_bytes()).hexdigest()};clients=[];process=None;sequence=0;protocol_ids={};password=secrets.token_urlsafe(18)
def check(name,value):
 report['checks'].append({'check':name,'passed':bool(value)});print(('PASS ' if value else 'FAIL ')+name,flush=True);assert value,name

def waitfor(fn,seconds=15):
 end=time.monotonic()+seconds
 while time.monotonic()<end:
  if fn():return True
  time.sleep(.1)
 return False

def command(s,delay=.25):process.stdin.write(s+'\n');process.stdin.flush();time.sleep(delay)
def probe(operation,name='unused',*extra):
 global sequence
 sequence+=1;label='result_'+str(sequence);file=S/'plugins/LootProbe'/(label+'.json');command('lootprobe '+operation+' '+name+' '+(' '.join(map(str,extra))+' ' if extra else '')+label,.08);assert waitfor(file.exists,10),'Probe '+operation;return json.loads(file.read_text())
def snapshot(c):return probe('inspect',c.name)
def packet(c,kind,number):
 encoded=base64.b64decode(probe('packet',c.name,kind,number)['packet']);f=io.BytesIO(encoded);pid=vi(f);c.send(pid,f.read());time.sleep(.35)
def opened(c,x):
 probe('teleport',c.name,x);packet(c,'move',0);packet(c,'open',x);assert waitfor(lambda:snapshot(c)['holder'].startswith('com.westshield.loot.'),10),'Personal GUI';return snapshot(c)
def sql(query,params=()):
 with sqlite3.connect('file:'+str(S/'plugins/WestShieldLoot/loot.sqlite')+'?mode=ro',uri=True,timeout=5) as db:return db.execute(query,params).fetchall()
def leave(c):
 c.closed=True
 try:c.socket.shutdown(socket.SHUT_RDWR);c.socket.close()
 except OSError:pass
 time.sleep(1)
def boot(label):
 global process,log
 log=R/(label+'-console.log');process=subprocess.Popen([args.java,'--enable-native-access=ALL-UNNAMED','-Xms256M','-Xmx2000M','-XX:ActiveProcessorCount=2','-Dterminal.jline=false','-Dterminal.ansi=false','-jar','server.jar','nogui'],cwd=S,stdin=subprocess.PIPE,stdout=log.open('w'),stderr=subprocess.STDOUT,text=True)
 assert waitfor(lambda:'INFO]: Done (' in log.read_text(),180),'Server startup'
def stop():
 global process
 for c in clients:leave(c)
 clients.clear()
 if process and process.poll() is None:command('stop',.1);process.wait(timeout=60)
 process=None

def join(name,virtual_host=None):
 c=Client('127.0.0.1',25721,name,status('127.0.0.1',25721)['version']['protocol'],virtual_host=virtual_host);c.name=name.lstrip('.');c.components=[];clients.append(c);lock=threading.RLock();send=c.send
 def safe(*a,**kw):
  with lock:send(*a,**kw)
 c.send=safe
 def read():
  size=vi(c.stream);raw=b''
  while len(raw)<size:
   part=c.stream.read(size-len(raw))
   if not part:raise EOFError()
   raw+=part
  if c.compression:
   stream=io.BytesIO(raw);unpacked=vi(stream);raw=zlib.decompress(stream.read()) if unpacked else stream.read()
  f=io.BytesIO(raw);pid=vi(f);body=f.read()
  if c.state=='login':
   if pid==3:c.compression=True
   elif pid==2:c.send(3);c.state='config'
   elif pid==0:c.closed=True
  elif c.state=='config':
   if pid==14:c.send(7,vint(0))
   elif pid==3:c.send(3);c.state='game'
   elif pid==4:c.send(4,body)
   elif pid==5:c.send(5,body)
   elif pid==2:c.closed=True
  else:
   if pid==11:c.send(11,struct.pack('>f',10.0))
   elif pid==protocol_ids['teleport']:c.send(protocol_ids['teleport_ack'],vint(vi(io.BytesIO(body))))
   elif pid==protocol_ids['keepalive']:c.send(protocol_ids['keepalive_ack'],body)
   elif pid==protocol_ids['ping']:c.send(protocol_ids['pong'],body)
   elif pid==protocol_ids['disconnect']:c.closed=True
 c.read=read
 def pump():
  try:
   while not c.closed:c.pump(.15)
  except (OSError,EOFError,ValueError):c.closed=True
 threading.Thread(target=pump,daemon=True).start();assert waitfor(lambda:c.state=='game',15),'Client game admission';c.send(14,string('en_us')+b'\x02'+vint(0)+b'\x01\x7f'+vint(1)+b'\x00\x01'+vint(0));c.send(protocol_ids['loaded']);time.sleep(1);return c
def login(c):c.command('login '+password);time.sleep(1);assert snapshot(c)['authenticated']
try:
 boot('initial');protocol_ids=probe('protocol');check('Plugin enables on Purpur with AuthMe and shared accounts','Personal loot ready:' in log.read_text());probe('setup')
 for name in ['LootOne','LootTwo','LootNew']:
  command('authme register '+name+' '+password);command('lp user '+uid(name)+' permission set grim.exempt true')
 c=join('LootOne');probe('teleport',c.name,30);packet(c,'open',30);check('AuthMe denial cannot be bypassed by a natural chest',not snapshot(c)['holder'].startswith('com.westshield.loot.') and sql('select count(*) from rolls')[0][0]==0);login(c);probe('clear',c.name)
 first=opened(c,30);check('First authenticated natural chest opens personal loot',len(first['occupied'])>0);before_items=first['items'];slot=first['occupied'][0];packet(c,'click',slot);assert waitfor(lambda:snapshot(c)['items']>before_items,8),'Native click delivered';after=snapshot(c);check('Real client inventory click transfers items into the player inventory',after['items']>before_items);assert waitfor(lambda:not sql('select * from transfers'),5);check('Delivery journal clears only after native player save',not sql('select * from transfers'));remaining=after['loot'];roll_seed=sql('select seed from rolls where player=?',(uid(c.name),))[0][0]
 probe('close',c.name);again=opened(c,30);check('Reopening preserves the exact remaining inventory without reroll',again['loot']==remaining and sql('select count(*) from rolls')[0][0]==1)
 probe('close',c.name);probe('deny','true');probe('teleport',c.name,34);packet(c,'open',34);check('Another plugin can deny opening without being overridden',not snapshot(c)['holder'].startswith('com.westshield.loot.'));probe('deny','false')
 barrel=opened(c,34);check('Natural barrels have their own personal roll',bool(barrel['occupied']));probe('close',c.name)
 pair=opened(c,45);check('Natural double chests preserve both halves and a 54-slot GUI',pair['size']==54 and len(sql('select * from cells where container=(select container from cells where x=45)'))==2);probe('close',c.name)
 probe('teleport',c.name,50);packet(c,'open',50);ordinary=snapshot(c);check('A player storage chest is not converted',not ordinary['holder'].startswith('com.westshield.loot.') and not sql('select * from cells where x=50'));probe('close',c.name)
 probe('teleport',c.name,54);packet(c,'open',54);check('Trial Vaults remain vanilla and are never registered',not sql('select * from cells where x=54'))
 c2=join('LootTwo');login(c2);second=opened(c2,30);check('A new account receives a separate roll from the same chest',bool(second['occupied']) and sql('select count(*) from rolls where container=(select container from cells where x=30)')[0][0]==2);check('Each account has an independently generated seed',len(set(x[0] for x in sql('select seed from rolls where container=(select container from cells where x=30)')))==2);probe('close',c2.name);leave(c2)
 probe('close',c.name);command('save-all flush');leave(c)
 label='bedrock_host';f=S/'plugins/AutoProbe'/(label+'.json');command('autoprobe hostname LootOne 123456789098777 '+label);assert waitfor(f.exists);host=json.loads(f.read_text())['hostname'];c=join('.LootOne',host);check('Floodgate selects the same shared UUID before gameplay',snapshot(c)['bedrock'] and snapshot(c)['uuid']==uid('LootOne'));login(c);bedrock=opened(c,30);check('Switching Java to Bedrock preserves the exact remaining loot',bedrock['loot']==remaining and sql('select count(*) from rolls where container=(select container from cells where x=30)')[0][0]==2);check('Edition change cannot reroll or create another account reward',sql('select seed from rolls where player=? and container=(select container from cells where x=30)',(uid('LootOne'),))[0][0]==roll_seed);probe('close',c.name)
 doomed=opened(c,40);probe('close',c.name);result=probe('break',c.name,40);check('Players may physically destroy a natural loot container',result['broken']);assert waitfor(lambda:sql('select retired from containers where id=(select container from cells where x=40)')[0][0]==1);check('Physical destruction permanently retires all remaining personal loot',sql('select retired from containers where id=(select container from cells where x=40)')[0][0]==1);probe('replace',c.name,40);packet(c,'open',40);check('A replacement chest at the same coordinates is ordinary and never refills',not snapshot(c)['holder'].startswith('com.westshield.loot.'));probe('close',c.name)
 opened(c,60);probe('close',c.name);probe('explode',c.name,60);assert waitfor(lambda:sql('select retired from containers where id=(select container from cells where x=60)')[0][0]==1);check('Explosions can destroy natural loot with permanent loss',sql('select retired from containers where id=(select container from cells where x=60)')[0][0]==1)
 command('save-all flush');stop();boot('restart');c=join('LootOne');login(c);resumed=opened(c,30);check('Personal remaining loot and shared-account history survive server restart',resumed['loot']==remaining and sql('select seed from rolls where player=? and container=(select container from cells where x=30)',(uid('LootOne'),))[0][0]==roll_seed);check('Destroyed-container tombstones also survive server restart',sql('select count(*) from containers where retired=1')[0][0]>=2)
 command('westshieldloot status');check('No loot persistence or runtime failures','failures=0' in log.read_text() and 'Personal loot paused;' not in log.read_text());report['status']='passed'
finally:
 stop();(R/'verification.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps({'status':report.get('status'),'checks':len(report['checks'])}))
