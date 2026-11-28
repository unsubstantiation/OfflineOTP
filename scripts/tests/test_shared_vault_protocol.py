"""Chunk 3 source/fixture guards, not a substitute for native build/device gates."""
from pathlib import Path
import json
import subprocess
import sys
import unittest

ROOT = Path(__file__).resolve().parents[2]

def source(path):
    return (ROOT / path).read_text()

class SharedVaultProtocolTest(unittest.TestCase):
    def test_protocol_corpus_is_reproducible_and_covers_all_read_routes_and_bounds(self):
        subprocess.run([sys.executable, str(ROOT / 'scripts/fixtures/generate-vault-protocol.py'), '--check'], check=True)
        corpus = json.loads(source('desktop/src/test/resources/golden/portable-vault-protocol.json'))
        self.assertGreater(len(corpus['headers']), 50)
        self.assertEqual({'VAULT_COPY', 'OFFLINEOTP', 'ANDOTP_PBKDF2', 'ANDOTP_PRE063', 'PLAIN', 'LINSCP'}, {row['route'] for row in corpus['routes'] if row['route']})
        self.assertEqual({'SIZE', 'VERSION', 'PARAMETERS'}, {row['error'] for row in corpus['headers'] if row['error']})
        self.assertTrue(any(row['name'] == 'vault-pbkdf2-ceiling' for row in corpus['headers']))
        self.assertTrue(any(row['name'] == 'backup-pbkdf2-ceiling' for row in corpus['headers']))

    def test_jvm_framing_and_dispatch_are_shared_not_reimplemented(self):
        base = 'core/src/main/kotlin/com/fairuniversal/offlineotp/Utilities/'
        vault = source(base + 'VaultContainer.kt'); backup = source(base + 'EncryptedBackupHeader.kt')
        codec = source(base + 'EncryptedBackupCodec.kt')
        for marker in ['protocol.vaultHeader', 'protocol.makeVaultHeader', 'protocol.isVault']:
            self.assertIn(marker, vault)
        self.assertNotIn('ByteBuffer', vault); self.assertNotIn('checkedHeader', vault)
        self.assertIn('VaultProtocol().backupHeader', backup); self.assertIn('VaultProtocol().makeBackupHeader', backup)
        self.assertNotIn('ByteBuffer', backup)
        self.assertIn('VaultProtocol().select(data, data.size, format, andOtpVariant)', codec)
        self.assertIn('VaultProtocol().select(data, data.size, "auto", "")', codec)
        self.assertIn('KdfPolicy.validArgon', source(base + 'Argon2id.kt'))

    def test_apple_production_never_silently_uses_the_standalone_oracle(self):
        store = source('ios/App/Sources/VaultStore.swift')
        self.assertIn('self.protocolPolicy = SharedVaultProtocolAdapter()', store)
        self.assertIn('documents: documents, protocolPolicy: protocolPolicy, persistence: SharedPersistenceAdapter())', store)
        self.assertEqual(2, store.count('documents: entryDocuments, protocolPolicy: protocolPolicy, interchange: interchange)'))
        self.assertIn('plain: document, protocolPolicy: SharedVaultProtocolAdapter()', source('ios/App/Sources/SessionAutomaticBackup.swift'))
        adapter = source('ios/App/Sources/SharedVaultProtocolAdapter.swift')
        self.assertNotIn('NativeVaultProtocolAdapter', adapter)
        self.assertIn('source.prefix(40)', adapter); self.assertIn('source.prefix(30)', adapter)
        for marker in ['policy.vaultHeader', 'policy.backupHeader', 'policy.makeVaultHeader', 'policy.makeBackupHeader', 'policy.select']:
            self.assertIn(marker, adapter)
        vault = source('ios/Sources/OfflineOTPKit/PasswordVault.swift')
        for line in vault.splitlines():
            if any('VaultContainer.' + name + '(' in line for name in ['header', 'fromSettings', 'openWithKey', 'open', 'seal']):
                self.assertIn('protocolPolicy: protocolPolicy', line)

    def test_shared_policy_has_no_crypto_or_secret_api_and_redacts_descriptors(self):
        policy = source('shared-domain/src/commonMain/kotlin/com/fairuniversal/offlineotp/domain/VaultProtocol.kt')
        for forbidden in ['import java.', 'import platform.', 'SecretKey', 'fun derive', 'fun encrypt', 'fun decrypt', 'password:']:
            self.assertNotIn(forbidden, policy)
        self.assertIn('ProtocolHeader(redacted)', policy)
        self.assertIn('memoryKiB.toLong() * iterations', policy)
        self.assertIn('MAX_BACKUP_PBKDF2 = 1_000_000', policy)
        self.assertIn('MAX_VAULT_PBKDF2 = 20_000_000', policy)

    def test_native_bridge_and_android_regressions_are_bundled(self):
        project = source('ios/project.yml')
        self.assertIn('../desktop/src/test/resources/golden/portable-vault-protocol.json', project)
        self.assertIn('SharedVaultProtocolAdapter()', source('ios/App/Tests/SharedVaultProtocolAdapterTests.swift'))
        android = source('app/src/androidTest/kotlin/com/fairuniversal/offlineotp/BackupRestoreDeviceTest.kt')
        self.assertIn('portableVaultProtocolCorpusAndCryptoProvidersAgreeOnAndroid', android)
        self.assertIn('golden/portable-vault-protocol.json', android)
        self.assertIn('golden/vault-container.json', android)
