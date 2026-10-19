"""Add the existing BlueMap fixture, bound strictly to loopback, without production data."""
from pathlib import Path
import json
import re
import shutil

def prepare_map(source: Path,target: Path,runtime: Path) -> None:
    if 'server-ip=127.0.0.1' not in (target/'server.properties').read_text():
        raise RuntimeError('Map fixture target must be loopback-only')
    shutil.copy2(runtime/'plugins/bluemap-5.29-paper.jar',target/'plugins/bluemap-5.29-paper.jar')
    shutil.copytree(source/'plugins/BlueMap',target/'plugins/BlueMap',dirs_exist_ok=True)
    shutil.copytree(source/'bluemap',target/'bluemap',dirs_exist_ok=True,ignore=shutil.ignore_patterns('*.log'))
    for path in (target/'plugins/BlueMap').rglob('*.conf'):
        path.write_text(path.read_text().replace(str(source.resolve()),str(target.resolve())))
    webserver=target/'plugins/BlueMap/webserver.conf'
    text=re.sub(r'(?m)^port:\s*\d+','port: 25603',webserver.read_text())
    text=re.sub(r'(?m)^ip:.*','ip: "127.0.0.1"',text)
    if 'ip: "127.0.0.1"' not in text:text+='\nip: "127.0.0.1"\n'
    webserver.write_text(text)
    webroot=target/'bluemap/web'
    source_web=Path(__file__).resolve().parent.parent/'web'
    for path in source_web.iterdir():shutil.copy2(path,webroot/path.name)
    webapp=target/'plugins/BlueMap/webapp.conf'
    settings=webapp.read_text()
    for key,name in [('scripts','wiut-clans-v1.js'),('styles','wiut-clans-v1.css')]:
        pattern=r'(?m)^'+key+r':\s*\[([^\n]*)\]'
        match=re.search(pattern,settings)
        if not match:raise RuntimeError('Expected existing BlueMap custom '+key)
        values=json.loads('['+match.group(1)+']')
        if name not in values:values.append(name)
        settings=re.sub(pattern,key+': '+json.dumps(values),settings)
    webapp.write_text(settings)
