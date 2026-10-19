"""Build the published WIUT adapters against an installed Purpur 26.2 server."""
from pathlib import Path
import argparse,hashlib,subprocess,zipfile
import yaml

parser=argparse.ArgumentParser()
parser.add_argument('--server-root',type=Path,required=True)
parser.add_argument('--dependency-directory',type=Path,action='append',default=[])
parser.add_argument('--only',choices=['WiutCreative','WiutWorldRules','WiutHeads','WiutEnglish'])
args=parser.parse_args()
BASE=Path(__file__).resolve().parent;OUT=BASE/'build';OUT.mkdir(exist_ok=True)
runtime=args.server_root.resolve()
libraries=[*sorted((runtime/'libraries').rglob('*.jar')),*sorted((runtime/'plugins').glob('*.jar'))]
libraries.extend(sorted((runtime/'versions').rglob('purpur-*.jar')))
for directory in args.dependency_directory:libraries.extend(sorted(directory.glob('*.jar')))
for source in sorted((BASE/'adapters').iterdir()):
 descriptor=yaml.safe_load((source/'plugin.yml').read_text())
 name=descriptor['name'];version=descriptor['version']
 if args.only and args.only!=name:continue
 classes=OUT/(name+'-classes');classes.mkdir(exist_ok=True)
 subprocess.run(['javac','--release','25','-encoding','UTF-8','-cp',':'.join(map(str,libraries)),
   '-d',str(classes),*map(str,sorted(source.rglob('*.java')))],check=True)
 entries={str(p.relative_to(classes)):p.read_bytes() for p in classes.rglob('*.class')}
 for resource in ['plugin.yml','config.yml']:
  if (source/resource).exists():entries[resource]=(source/resource).read_bytes()
 if name=='WiutEnglish':
  dictionary=runtime/'plugins/Geyser-Spigot/locales/en_us.json'
  entries['english-dictionary.sha256']=(hashlib.sha256(dictionary.read_bytes()).hexdigest()+'\n').encode()
 output=OUT/(name+'-'+str(version)+'.jar')
 with zipfile.ZipFile(output,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=9) as target:
  for entry,data in sorted(entries.items()):
   item=zipfile.ZipInfo(entry,(2026,10,20,0,0,0));item.compress_type=zipfile.ZIP_DEFLATED;item.external_attr=0o100644<<16
   target.writestr(item,data)
 print(output.name,hashlib.sha256(output.read_bytes()).hexdigest())
