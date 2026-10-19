"""Reproducible class overlay against the pinned upstream release and server APIs."""
from pathlib import Path
import argparse
import hashlib
import json
import shutil
import subprocess
import urllib.request
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument('--server-libraries', type=Path, required=True)
args = parser.parse_args()
BASE = Path(__file__).resolve().parent
ROOT = BASE.parent
deps = BASE / 'dependencies'
for name, expected in json.loads((deps / 'sha256.json').read_text()).items():
    if not (deps / name).exists():
        sources = json.loads((deps / 'sources.json').read_text())
        with urllib.request.urlopen(sources[name], timeout=40) as response:
            data = response.read()
        assert hashlib.sha256(data).hexdigest() == expected, name
        (deps / name).write_bytes(data)
    assert hashlib.sha256((deps / name).read_bytes()).hexdigest() == expected, name
lock = BASE / 'server-libraries.sha256.json'
libraries = []
for name, expected in json.loads(lock.read_text()).items():
    path = args.server_libraries.resolve() / name
    assert path.exists(), 'Missing pinned server API library: ' + name
    assert hashlib.sha256(path.read_bytes()).hexdigest() == expected, name
    libraries.append(path)
sources = [ROOT / 'src/main/java/net/sacredlabyrinth/phaed/simpleclans/commands/SCCommandManager.java',
           ROOT / 'src/main/java/net/sacredlabyrinth/phaed/simpleclans/managers/PermissionsManager.java',
           ROOT / 'src/main/java/net/sacredlabyrinth/phaed/simpleclans/managers/StorageManager.java',
           ROOT / 'src/main/java/net/sacredlabyrinth/phaed/simpleclans/commands/general/GeneralCommands.java',
           ROOT / 'src/main/java/net/sacredlabyrinth/phaed/simpleclans/conversation/CreateClanNamePrompt.java',
           *sorted((BASE / 'src').rglob('*.java'))]
classes = BASE / 'classes'
if classes.exists():
    shutil.rmtree(classes)
classes.mkdir()
classpath = [*sorted(deps.glob('*.jar')), *libraries]
compiled = subprocess.run(['javac', '--release', '25', '-encoding', 'UTF-8', '-parameters', '-g',
                '-cp', ':'.join(map(str, classpath)), '-d', str(classes), *map(str, sources)], capture_output=True, text=True)
if compiled.returncode:
    print(compiled.stderr)
    raise SystemExit(compiled.returncode)
entries = {}
with zipfile.ZipFile(deps / 'SimpleClans-2.19.2.jar') as upstream:
    for entry in upstream.infolist():
        if not entry.is_dir():
            entries[entry.filename] = upstream.read(entry)
for path in classes.rglob('*.class'):
    entries[str(path.relative_to(classes))] = path.read_bytes()
descriptor = entries['plugin.yml'].decode()
assert "version: '2.19.2-05e7abc'" in descriptor
descriptor = descriptor.replace("version: '2.19.2-05e7abc'", "version: '2.19.2-wiut.3'")
descriptor = descriptor.rstrip() + '\n  wiutclans.admin:\n    description: Create clans and administer informational clan territories.\n    default: op\n'
entries['plugin.yml'] = descriptor.encode()
entries['META-INF/WIUT-FORK.json'] = json.dumps({
    'version': '2.19.2-wiut.3', 'upstream_commit': '05e7abccbdd46339691b9a01eae84caae817b3ef',
    'upstream_sha256': json.loads((deps / 'sha256.json').read_text())['SimpleClans-2.19.2.jar'],
    'changes': ['Use supported Player.locale API in ACF', 'Grant fresh clan permission attachments immediately',
                'Restrict clan creation to wiutclans.admin and revalidate conversation authority',
                'Prevent an old disbanded clan deferred save from overwriting a reused tag',
                'Use English for direct and interactive clan-creation authority errors'],
    'server': 'Purpur 26.2 build 2633', 'java_release': 25,
}, indent=2).encode()
entries['META-INF/WIUT-SIMPLECLANS-LICENSE.txt'] = (ROOT / 'LICENSE').read_bytes()
output = BASE / 'SimpleClans-2.19.2-wiut.3.jar'
with zipfile.ZipFile(output, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as target:
    for name, data in sorted(entries.items()):
        entry = zipfile.ZipInfo(name, (2026, 10, 17, 0, 0, 0))
        entry.compress_type = zipfile.ZIP_DEFLATED
        entry.external_attr = 0o100644 << 16
        target.writestr(entry, data)
digest = hashlib.sha256(output.read_bytes()).hexdigest()
(BASE / (output.name + '.sha256')).write_text(digest + '  ' + output.name + '\n')
print('Built', output.name, digest)
