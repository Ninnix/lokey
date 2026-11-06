#!/usr/bin/env python3
"""Create a local upload key without putting credentials in Git or shell arguments."""
import argparse
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import tempfile

root=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--directory',type=Path,default=root/'.local/signing')
parser.add_argument('--name',default='Ninnix')
args=parser.parse_args()
directory=args.directory.resolve()
directory.mkdir(mode=0o700,parents=True,exist_ok=True)
paths=[directory/name for name in ['upload.p12','release.properties','upload-certificate.pem']]
if any(path.exists() for path in paths):parser.error('Signing files already exist; refusing to replace an update key.')
keytool=Path(os.environ.get('JAVA_HOME',''))/'bin/keytool'
if not keytool.is_file():keytool=shutil.which('keytool')
if not keytool:parser.error('Set JAVA_HOME to a JDK or put keytool on PATH.')
os.chmod(directory,0o700)
password=secrets.token_urlsafe(48)
env=os.environ.copy();env['LOKEY_KEYSTORE_PASSWORD']=password
with tempfile.TemporaryDirectory(prefix='.create-',dir=directory) as temp:
    temp=Path(temp);store=temp/'upload.p12';certificate=temp/'upload-certificate.pem'
    subprocess.run([str(keytool),'-genkeypair','-noprompt','-keystore',str(store),'-storetype','PKCS12',
        '-alias','lokey-upload','-keyalg','RSA','-keysize','4096','-validity','10000',
        '-dname','CN='+args.name+', OU=Lokey','-storepass:env','LOKEY_KEYSTORE_PASSWORD',
        '-keypass:env','LOKEY_KEYSTORE_PASSWORD'],env=env,check=True)
    subprocess.run([str(keytool),'-exportcert','-rfc','-keystore',str(store),'-alias','lokey-upload',
        '-storepass:env','LOKEY_KEYSTORE_PASSWORD','-file',str(certificate)],env=env,check=True)
    try:location=str(paths[0].relative_to(root))
    except ValueError:location=str(paths[0])
    location=location.replace('\\','\\\\').replace('\n','\\n').replace('\r','\\r')
    properties=temp/'release.properties'
    properties.write_text('storeFile='+location+'\nstorePassword='+password+'\nkeyAlias=lokey-upload\nkeyPassword='+password+'\n')
    for source,destination in [(store,paths[0]),(properties,paths[1]),(certificate,paths[2])]:
        os.chmod(source,0o600);source.rename(destination)
print('Upload key created in',directory)
print('Back up this entire directory securely before distributing a release. Passwords were not printed.')
