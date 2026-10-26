"""Import an owner's local Steam dictionaries; never include the dictionary data in Git/releases."""
import argparse
import gzip
import hashlib
import json
from pathlib import Path
import re
import unicodedata

parser = argparse.ArgumentParser()
parser.add_argument('--steam-resource', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
args.output.mkdir(parents=True, exist_ok=False)

neutral = {'gay','gays','lesbian','lesbians','bisexual','homosexual','homosexuals','queer','sex','sexual','sexuality','penis','vagina','breast','breasts','black','blacks','brown','jew','jews','jewish','lesbienne','гей','геи','лесбиянка','лесбиянки','пенис','вагина','секс','ам','он','оно','она','херес'}
explicit = {'чмо','лох','гондон','гандон','уебан','уёбан','сучь'}
folds = str.maketrans(dict(zip('013457!@$аеєорсхуіїј', 'oieastiasae eop cxyiij'.replace(' ', ''))))

def native(value):
    return ''.join(c for c in unicodedata.normalize('NFKD', value).lower()
                   if unicodedata.category(c) != 'Cf' and not '\u0300' <= c <= '\u036f')

def canonical(value):
    return ''.join(c for c in native(value).translate(folds) if c.isalnum() or unicodedata.category(c) in {'Mn','Mc'})

words, patterns, sources, languages = set(), set(), {}, set()
for path in sorted(args.steam_resource.glob('filter_*.txt.gz')):
    kind, language = path.name.removeprefix('filter_').removesuffix('.txt.gz').split('_', 1)
    if kind not in {'profanity', 'banned'} or language == 'legalcn':
        continue
    raw = path.read_bytes()
    lines = [line.strip() for line in gzip.decompress(raw).decode('utf-8-sig').splitlines() if line.strip()]
    sources[path.name] = {'sha256': hashlib.sha256(raw).hexdigest(), 'entries': len(lines)}
    languages.add(language)
    for line in lines:
        if line.lower() in neutral:
            continue
        if re.search(r'[\\\[\]()?*+|{}^$]', line):
            patterns.add(line)
        elif not (len(line) < 3 and line.isascii()):
            words.add(line)

# Select only clean entries that can affect these rules, rather than retaining 800k unrelated words.
expression = re.compile('|'.join('(?:' + ''.join(native(c) if ord(c) >= 128 else c for c in rule) + ')' for rule in patterns), re.IGNORECASE)
blocked = {canonical(word) for word in words}
allow = set(neutral)
scanned = 0
for path in sorted(args.steam_resource.glob('filter_clean_*.txt.gz')):
    raw = path.read_bytes()
    lines = gzip.decompress(raw).decode('utf-8-sig').splitlines()
    sources[path.name] = {'sha256': hashlib.sha256(raw).hexdigest(), 'entries': len(lines)}
    for word in lines:
        word = word.strip()
        scanned += 1
        if word and word.lower() not in explicit and (canonical(word) in blocked or expression.fullmatch(native(word))):
            allow.add(word)

files = {}
for filename, values in [('words.txt', words), ('patterns.txt', patterns), ('allow-words.txt', allow)]:
    data = ('\n'.join(sorted(values)) + '\n').encode()
    (args.output / filename).write_bytes(data)
    files[filename] = {'sha256': hashlib.sha256(data).hexdigest(), 'entries': len(values)}
manifest = {'version': 1, 'source': 'Owner-installed Steam client resource dictionaries',
            'languages': sorted(languages), 'files': files, 'source_files': sources,
            'clean_entries_scanned': scanned,
            'adaptations': ['No legal/political dictionaries', 'Neutral identity/anatomy terms excluded',
                            'Only relevant clean-word exceptions retained', 'Explicit server insults override clean exceptions'],
            'redistribution': 'Dictionary data stays in private server configuration, outside source/release artifacts.'}
(args.output / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
print(json.dumps({'languages': len(languages), 'files': files, 'clean_entries_scanned': scanned}, indent=2))
