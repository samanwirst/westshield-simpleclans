"""Run geometry/storage checks with the exact compiled plugin and supplied server APIs."""
from pathlib import Path
import argparse
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument('--server-root', type=Path, required=True)
args = parser.parse_args()
base = Path(__file__).resolve().parent
classes = base / 'test-classes'
classes.mkdir(exist_ok=True)
classpath = [base/'WestShieldClanTerritories-1.0.4.jar', *sorted((args.server_root/'libraries').rglob('*.jar'))]
subprocess.run(['javac','--release','25','-encoding','UTF-8','-cp',':'.join(map(str,classpath)),
                '-d',str(classes),*map(str,(base/'tests').rglob('GeometryChecks.java'))],check=True)
subprocess.run(['java','-cp',':'.join(map(str,[classes,*classpath])),
                'com.westshield.clans.territory.GeometryChecks'],check=True)
