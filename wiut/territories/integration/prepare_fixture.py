"""Prepare a disposable loopback fixture from the existing isolated four-world sample."""
from pathlib import Path
import argparse
import shutil
import subprocess
import yaml
from map_fixture import prepare_map

parser=argparse.ArgumentParser()
parser.add_argument('--source',type=Path,required=True)
parser.add_argument('--server-root',type=Path,required=True)
args=parser.parse_args()
base=Path(__file__).resolve().parent
target=base/'server'
source=args.source.resolve()
runtime=args.server_root.resolve()
if target.exists(): raise SystemExit('Inspect or retain the existing fixture instead of overwriting it')
if 'server-ip=127.0.0.1' not in (source/'server.properties').read_text():
    raise SystemExit('The source must be an isolated loopback fixture, never production')
(target/'plugins').mkdir(parents=True)
for name in ['libraries','versions','cache']:
    (target/name).symlink_to(runtime/name,target_is_directory=True)
(target/'server.jar').symlink_to(runtime/'server.jar')
shutil.copytree(source/'wiut',target/'wiut',ignore=shutil.ignore_patterns('session.lock','playerdata','stats','advancements'))
for name in ['bukkit.yml','spigot.yml']:
    if (source/name).exists(): shutil.copy2(source/name,target/name)
shutil.copytree(source/'config',target/'config')
families={'AuthMe':'AuthMe*.jar','LuckPerms':'LuckPerms*.jar','GriefPreventionData':'GriefPrevention.jar',
          'Multiverse-Core':'multiverse-core*.jar','Multiverse-Inventories':'multiverse-inventories*.jar',
          'WiutCreative':'WiutCreative*.jar','WiutWorldRules':'WiutWorldRules*.jar',
          'Geyser-Spigot':'Geyser-Spigot.jar','floodgate':'floodgate-spigot.jar'}
for directory,pattern in families.items():
    jars=list((source/'plugins').glob(pattern))
    if len(jars)!=1: raise SystemExit('Expected one fixture plugin: '+pattern)
    shutil.copy2(jars[0],target/'plugins')
    destination=target/'plugins'/directory;destination.mkdir(exist_ok=True)
    for filename in ['config.yml','groups.yml','worlds.yml','messages.yml']:
        path=source/'plugins'/directory/filename
        if path.exists(): shutil.copy2(path,destination/filename)
geyser_path=target/'plugins/Geyser-Spigot/config.yml'
geyser=yaml.safe_load(geyser_path.read_text())
geyser['bedrock'].update({'address':'127.0.0.1','port':25602,'clone-remote-port':False})
if 'remote' in geyser:
    geyser['remote'].update({'address':'127.0.0.1','port':25601})
geyser_path.write_text(yaml.safe_dump(geyser,allow_unicode=True,sort_keys=False))
clan=base.parent.parent/'SimpleClans-2.19.2-wiut.2.jar'
territory=base.parent/'WiutClanTerritories-1.0.0.jar'
for jar in [clan,territory]: shutil.copy2(jar,target/'plugins')
(target/'plugins/SimpleClans').mkdir(exist_ok=True)
shutil.copy2(base.parent.parent/'config/simpleclans.yml',target/'plugins/SimpleClans/config.yml')
properties=(source/'server.properties').read_text().splitlines()
replacements={'server-ip':'127.0.0.1','server-port':'25601','online-mode':'false','white-list':'false',
              'enforce-secure-profile':'false','spawn-protection':'0','enable-rcon':'false','enable-query':'false',
              'management-server-enabled':'false','pause-when-empty-seconds':'-1','view-distance':'2','simulation-distance':'2'}
lines=[line for line in properties if line.split('=',1)[0] not in replacements]
lines.extend(key+'='+value for key,value in replacements.items())
(target/'server.properties').write_text('\n'.join(lines)+'\n')
(target/'eula.txt').write_text('eula=true\n')
prepare_map(source,target,runtime)
classes=base/'probe-classes';classes.mkdir(exist_ok=True)
classpath=[*sorted((runtime/'libraries').rglob('*.jar')),*sorted((target/'plugins').glob('*.jar'))]
subprocess.run(['javac','--release','25','-encoding','UTF-8','-cp',':'.join(map(str,classpath)),
                '-d',str(classes),*map(str,(base/'src').rglob('*.java'))],check=True)
shutil.copy2(base/'src/plugin.yml',classes/'plugin.yml')
subprocess.run(['jar','--create','--file',str(target/'plugins/TerritoryProbe-test-only.jar'),'-C',str(classes),'.'],check=True)
print('Disposable territory fixture prepared at 127.0.0.1:25601')
