"""Run the dictionary and durable counter checks against the built plugin."""
import argparse
from pathlib import Path
import subprocess
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument('--server-root', type=Path, required=True)
parser.add_argument('--java', default='java')
parser.add_argument('--javac', default='javac')
parser.add_argument('--steam-directory', type=Path)
args = parser.parse_args()
source = Path(__file__).resolve().parent
import yaml
version = yaml.safe_load((source.parents[1] / 'english/adapters/protection/plugin.yml').read_text())['version']
jar = source.parents[1] / ('english/build/WestShieldProtection-' + str(version) + '.jar')
libraries = sorted((args.server_root / 'libraries').rglob('*.jar'))
classpath = ':'.join(map(str, [jar, *libraries]))
with tempfile.TemporaryDirectory(prefix='westshield-protection-unit-') as temporary:
    subprocess.run([args.javac, '--release', '25', '-cp', classpath, '-d', temporary,
                    str(source / 'ProtectionChecks.java')], check=True)
    subprocess.run([args.java, '-cp', temporary + ':' + classpath, 'ProtectionChecks'], check=True)
    if args.steam_directory:
        subprocess.run([args.javac, '--release', '25', '-cp', classpath, '-d', temporary,
                        str(source / 'SteamChecks.java')], check=True)
        subprocess.run([args.java, '-Xmx512m', '-cp', temporary + ':' + classpath,
                        'SteamChecks', str(args.steam_directory)], check=True)
