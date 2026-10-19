"""Build the English resource overlay without modifying Floodgate classes or licenses."""
from pathlib import Path
import argparse,hashlib,json,zipfile

parser=argparse.ArgumentParser()
parser.add_argument('--upstream-jar',type=Path,required=True)
parser.add_argument('--output',type=Path,required=True)
args=parser.parse_args()
if args.output.resolve()==args.upstream_jar.resolve():raise SystemExit('Use a separate output; retain upstream for rollback')
with zipfile.ZipFile(args.upstream_jar) as source,zipfile.ZipFile(args.output,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=9) as target:
 english=source.read('languages/texts/en_US.properties');replacements=[]
 for entry in source.infolist():
  if entry.is_dir():continue
  data=source.read(entry)
  if entry.filename.startswith('languages/texts/') and entry.filename.endswith('.properties'):
   data=english;replacements.append(entry.filename)
  item=zipfile.ZipInfo(entry.filename,(2026,10,20,0,0,0));item.compress_type=zipfile.ZIP_DEFLATED;item.external_attr=0o100644<<16
  target.writestr(item,data)
 metadata=zipfile.ZipInfo('META-INF/WIUT-ENGLISH.json',(2026,10,20,1,18,56));metadata.compress_type=zipfile.ZIP_DEFLATED;metadata.external_attr=0o600<<16
 content=json.dumps({'scope':'Bundled Floodgate text only; no class or descriptor changes','upstream_sha256':hashlib.sha256(args.upstream_jar.read_bytes()).hexdigest(),'resources':replacements},indent=2)
 target.writestr(metadata,content,compress_type=zipfile.ZIP_DEFLATED,compresslevel=9)
with zipfile.ZipFile(args.upstream_jar) as source,zipfile.ZipFile(args.output) as output:
 for name in source.namelist():
  if not name.endswith('/') and not name.startswith('languages/texts/'):
   assert source.read(name)==output.read(name),name
print(args.output.name,hashlib.sha256(args.output.read_bytes()).hexdigest())
