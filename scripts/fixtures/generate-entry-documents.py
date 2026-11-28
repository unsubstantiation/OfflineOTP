#!/usr/bin/env python3
"""Deterministic synthetic entry adapter corpus; no production credentials or private keys."""
import base64
import json
from pathlib import Path
import struct
import sys

ROOT = Path(__file__).resolve().parents[2]

def text(value):
    encoded = value.encode('utf-8')
    return struct.pack('>I', len(encoded)) + encoded

def b32(value):
    return base64.b32encode(value).decode('ascii')

def compact(value):
    return json.dumps(value, ensure_ascii=False, separators=(',', ':'))

connection = dict(host='imap.example.invalid', port=993, encryption=0, auth_type=1,
                  user='synthetic@example.invalid', password='synthetic-only')
email = dict(fairmail_account=dict(connection, name='Synthetic mail', protocol=0),
             fairmail_identities=[dict(connection, host='smtp.example.invalid', port=465,
                                      email='synthetic@example.invalid')])
payloads = {
    'TOTP': b'synthetic-otp-seed', 'HOTP': b'synthetic-hotp-seed',
    'MOTP': b'abcdef0123456789', 'STEAM': b'synthetic-steam-seed',
    'NOTE': 'Synthetic note 😀'.encode('utf-8'),
    'SSH_KEY': text('ssh-ed25519 SYNTHETIC test-only') + text('synthetic private text, not a key'),
    'PASSWORD': b'PWD1' + struct.pack('>I', 3) + b''.join(text(k) + text(v) for k, v in
                  [('url', 'https://example.invalid'), ('username', 'synthetic'), ('password', 'synthetic-only')]),
    'PASSWORD_ARCHIVE': b'synthetic archive test bytes', 'EMAIL': compact(email).encode('utf-8'),
}
valid = []
for kind, payload in payloads.items():
    row = dict(secret=b32(payload), issuer='Synthetic 😀', label=kind + ' example', digits=6,
               type=kind, algorithm='SHA256', thumbnail='Default', last_used=9007199254740993,
               used_frequency=7, tags=['ordered', 'duplicate', 'duplicate'], hidden_label='Synthetic hidden',
               details_visible=True, extension_null=None,
               extension_object={'future': [True, None, {'large_integer': 9007199254740995, 'fraction': 1.25}]})
    if kind == 'TOTP': row['period'] = 45
    if kind == 'HOTP': row.update(counter=9007199254740993, digits=8, algorithm='SHA512')
    if kind == 'STEAM': row.update(digits=5, period=60)
    if kind == 'MOTP': row['pin'] = '1234'
    if kind == 'SSH_KEY': row.update(ssh_host='host.example.invalid', ssh_port=2222,
                                     ssh_username='synthetic', ssh_password='synthetic-only')
    valid.append(dict(kind=kind, document=compact([row])))
base = dict(secret=b32(b'synthetic'), label='synthetic')
invalid = [dict(name='missing-secret', document='[{"label":"missing"}]'),
           dict(name='unknown-kind', document=compact([dict(base, type='FUTURE')])),
           dict(name='fractional-digits', document=compact([dict(base, digits=6.5)])),
           dict(name='counter-overflow', document=compact([dict(base, type='HOTP', counter=2**63)])),
           dict(name='invalid-tag-type', document=compact([dict(base, tags=[None])])),
           dict(name='valid-prefix-invalid-tail', document=compact([base, {}])),
           dict(name='duplicate-field', document='[{"secret":"ON4W45DIMV2GSYY=","label":"x","label":"y"}]'),
           dict(name='trailing-document', document=compact([base]) + '[]')]
result = dict(description='Independent synthetic framed payloads for all nine entry kinds. Regenerate with scripts/fixtures/generate-entry-documents.py.',
              valid=valid, invalid=invalid)
destination = ROOT / 'desktop/src/test/resources/golden/portable-entry-documents.json'
encoded = json.dumps(result, ensure_ascii=False, indent=2) + '\n'
if '--check' in sys.argv:
    if destination.read_text() != encoded:
        raise SystemExit('Portable entry fixtures are stale; regenerate them.')
else:
    destination.write_text(encoded)
