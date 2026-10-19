"""Build the territory plugin against the installed 26.2 API, embedding pinned JTS."""
from pathlib import Path
import argparse
import hashlib
import json
import shutil
import subprocess
import urllib.request
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument('--server-root', type=Path, required=True)
args = parser.parse_args()
BASE = Path(__file__).resolve().parent
FORK = BASE.parent.parent
server = args.server_root.resolve()
dependencies = BASE / 'dependencies'
for name, entry in json.loads((dependencies / 'lock.json').read_text()).items():
    path = dependencies / name
    if not path.exists():
        with urllib.request.urlopen(entry['url'], timeout=40) as response:
            path.write_bytes(response.read())
    if hashlib.sha256(path.read_bytes()).hexdigest() != entry['sha256']:
        raise SystemExit('Dependency hash mismatch: ' + name)
clan = BASE.parent / 'SimpleClans-2.19.2-wiut.3.jar'
if not clan.exists():
    raise SystemExit('Build SimpleClans wiut.2 first')
auth = list((server / 'plugins').glob('AuthMe*.jar'))
if len(auth) != 1:
    raise SystemExit('Expected exactly one AuthMe JAR under the supplied server root')
libraries = sorted((server / 'libraries').rglob('*.jar'))
blue_map = server/'plugins/bluemap-5.29-paper.jar'
if not blue_map.exists():
    raise SystemExit('Expected the tested BlueMap 5.29 JAR under the supplied server root')
classpath = [*libraries, clan, *auth, blue_map, *dependencies.glob('*.jar')]
classes = BASE / 'classes'
if classes.exists():
    shutil.rmtree(classes)
classes.mkdir()
subprocess.run(['javac', '--release', '25', '-encoding', 'UTF-8', '-g',
                '-cp', ':'.join(map(str, classpath)), '-d', str(classes),
                *map(str, sorted((BASE / 'src').rglob('*.java')))], check=True)
entries = {'plugin.yml': (BASE / 'src/plugin.yml').read_bytes()}
for path in classes.rglob('*.class'):
    entries[str(path.relative_to(classes))] = path.read_bytes()
for dependency in dependencies.glob('*.jar'):
    with zipfile.ZipFile(dependency) as source:
        for item in source.infolist():
            if not item.is_dir() and (item.filename.startswith('org/locationtech/jts/')
                                      or 'LICENSE' in item.filename.upper()
                                      or 'NOTICE' in item.filename.upper()):
                entries[item.filename] = source.read(item)
entries['META-INF/WIUT-LICENSE.txt'] = (FORK / 'LICENSE').read_bytes()
for path in dependencies.glob('JTS-*'):
    entries['META-INF/'+path.name] = path.read_bytes()
entries['META-INF/WIUT-BUILD.json'] = json.dumps({
    'name': 'WiutClanTerritories', 'version': '1.0.1', 'java_release': 25,
    'server': 'Purpur 26.2 build 2633', 'jts': json.loads((dependencies/'lock.json').read_text()),
    'protection': False, 'fixed_vertex_limit': None,
}, indent=2).encode()
output = BASE / 'WiutClanTerritories-1.0.1.jar'
with zipfile.ZipFile(output, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as target:
    for name, data in sorted(entries.items()):
        item = zipfile.ZipInfo(name, (2026, 10, 20, 0, 0, 0))
        item.compress_type = zipfile.ZIP_DEFLATED
        item.external_attr = 0o100644 << 16
        target.writestr(item, data)
digest = hashlib.sha256(output.read_bytes()).hexdigest()
output.with_suffix('.jar.sha256').write_text(digest + '  ' + output.name + '\n')
print('Built', output.name, digest)
