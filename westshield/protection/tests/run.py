"""Run the dictionary and durable counter checks against the built plugin."""
import argparse
from pathlib import Path
import subprocess
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument('--server-root', type=Path, required=True)
parser.add_argument('--java', default='java')
parser.add_argument('--javac', default='javac')
args = parser.parse_args()
source = Path(__file__).resolve().parent
jar = source.parents[1] / 'english/build/WestShieldProtection-1.0.0.jar'
libraries = sorted((args.server_root / 'libraries').rglob('*.jar'))
classpath = ':'.join(map(str, [jar, *libraries]))
with tempfile.TemporaryDirectory(prefix='westshield-protection-unit-') as temporary:
    subprocess.run([args.javac, '--release', '25', '-cp', classpath, '-d', temporary,
                    str(source / 'ProtectionChecks.java')], check=True)
    subprocess.run([args.java, '-cp', temporary + ':' + classpath, 'ProtectionChecks'], check=True)
