#!/usr/bin/env python3
"""Small deterministic framing/dispatch corpus, no crypto dependency or real credentials."""
import json
import struct
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
GOLDEN = ROOT / 'desktop/src/test/resources/golden'
MAX = 16 * 1024 * 1024
salt = bytes(range(16))

def vault(algorithm=1, memory=32, iterations=1, lanes=1):
    return b'OTPVLT\r\n' + bytes([1, 1, algorithm, 0]) + struct.pack('>iii', memory, iterations, lanes) + salt

def backup(memory=32, iterations=1, lanes=1):
    return b'OOTP\x01' + struct.pack('>iiB', memory, iterations, lanes) + salt

headers = []
def row(name, family, prefix, size, error=None, **expected):
    headers.append(dict(name=name, family=family, prefixHex=prefix.hex(), totalBytes=size, error=error, **expected))

def good(name, family, prefix, size, argon=True, memory=32, iterations=1, lanes=1):
    row(name, family, prefix, size, kdf='ARGON2ID' if argon else 'PBKDF2_SHA1', memory=memory,
        iterations=iterations, lanes=lanes, saltHex=(salt if family == 'vault' or argon else salt[:12]).hex(),
        offset=40 if family == 'vault' else 30 if argon else 16,
        keyBytes=32 if argon or family == 'backup' else 16,
        derivedBytes=64 if argon and family == 'vault' else 32)

good('vault-argon', 'vault', vault(), 70)
good('vault-pbkdf2', 'vault', vault(2, 0, 1, 0), 70, False, 0, 1, 0)
good('backup-argon', 'backup', backup(), 60)
good('backup-pbkdf2', 'backup', struct.pack('>i', 1) + salt[:12], 46, False, 0, 1, 0)
for family, base, minimum in [('vault', vault(), 69), ('backup', backup(), 58)]:
    for size in [0, minimum - 1, MAX + 1]: row(f'{family}-size-{size}', family, base, size, 'SIZE')
    row(f'{family}-prefix-truncated', family, base[:15], minimum, 'SIZE')
    version = bytearray(base); version[8 if family == 'vault' else 4] = 2
    row(f'{family}-version', family, version, minimum, 'VERSION')
    for memory, rounds, lanes in [(0,1,1),(7,1,1),(262145,1,1),(32,0,1),(32,65,1),(32,1,0),(256,1,17),(262144,5,1),(-1,1,1),(32,-1,1),(2147483647,2147483647,16)]:
        row(f'{family}-bounds-{memory}-{rounds}-{lanes}', family,
            vault(memory=memory, iterations=rounds, lanes=lanes) if family == 'vault' else backup(memory, rounds, lanes), minimum, 'PARAMETERS')
    for memory, rounds, lanes in [(8,1,1),(128,64,16),(262144,4,1)]:
        good(f'{family}-edge-{memory}-{rounds}-{lanes}', family,
             vault(memory=memory, iterations=rounds, lanes=lanes) if family == 'vault' else backup(memory,rounds,lanes), minimum,
             memory=memory, iterations=rounds, lanes=lanes)
for index in [9,11]:
    value=bytearray(vault()); value[index]=1 if index==11 else 2
    row(f'vault-reserved-{index}', 'vault', value, 70, 'VERSION')
value=bytearray(vault()); value[10]=3
row('vault-unknown-kdf', 'vault', value, 70, 'PARAMETERS')
for iterations in [0,-1,1000001,20000000,2147483647]:
    row(f'backup-pbkdf2-{iterations}', 'backup', struct.pack('>i',iterations)+salt[:12],44,'PARAMETERS')
for iterations in [0,-1,20000001,2147483647]:
    row(f'vault-pbkdf2-{iterations}', 'vault', vault(2,0,iterations,0),69,'PARAMETERS')
good('backup-pbkdf2-ceiling','backup',struct.pack('>i',1000000)+salt[:12],44,False,0,1000000,0)
good('vault-pbkdf2-ceiling','vault',vault(2,0,20000000,0),69,False,0,20000000,0)
for memory,lanes in [(1,0),(0,1)]: row('vault-pbkdf2-reserved-'+str(memory)+str(lanes),'vault',vault(2,memory,1,lanes),69,'PARAMETERS')
routes=[]
def route(name, prefix, size, fmt, variant='', expected=None):
    routes.append(dict(name=name,prefixHex=prefix.hex(),totalBytes=size,format=fmt,variant=variant,route=expected))
for family,prefix,size,auto in [('vault',vault(),70,'VAULT_COPY'),('argon',backup(),60,'OFFLINEOTP'),('pbkdf2',struct.pack('>i',1)+salt[:12],46,'OFFLINEOTP'),('linscp',b'LSCPVLT2',70,'LINSCP'),('plain',b'[]',2,None)]:
    route(f'{family}-auto',prefix,size,'auto',expected=auto)
    for fmt,var,want in [('vault-copy','', 'VAULT_COPY' if family=='vault' else None),('offlineotp','','OFFLINEOTP' if family in ['argon','pbkdf2'] else None),('andotp','encrypted','ANDOTP_PBKDF2' if family=='pbkdf2' else None),('andotp','plain','PLAIN' if family not in ['vault','linscp'] else None),('andotp','pre063','ANDOTP_PRE063' if family=='pbkdf2' else None),('andotp','bad',None),('bad','',None)]:
        route(f'{family}-{fmt}-{var}',prefix,size,fmt,var,want)
route('pre063-explicit',b'nonce-is-not-a-magic',70,'andotp','pre063','ANDOTP_PRE063')
route('pre063-not-auto',b'nonce-is-not-a-magic',70,'auto')
for size in [0,MAX+1]: route('size-'+str(size),b'[]',size,'andotp','plain')
text=json.dumps(dict(description='Synthetic protocol metadata only; no derivation is performed at upper bounds.',headers=headers,routes=routes),indent=2)+'\n'
path=GOLDEN/'portable-vault-protocol.json'
if '--check' in sys.argv:
    if not path.exists() or path.read_text()!=text: raise SystemExit('Protocol corpus is stale; regenerate it')
else: path.write_text(text)
