//! Unified OpenPGP backend for OfflineOTP (Android, Debian, Windows, macOS, iOS).
//!
//! The foreign-language contract is `src/offlineotp_openpgp.udl`; UniFFI generates
//! the Kotlin/JNA and Swift bindings from that same file. Cryptography is rpgp
//! (`pgp` 0.21): exports are binary SEIPDv1 + AES-256 messages, and imports accept
//! standard binary or ASCII-armored OpenPGP messages. No plaintext fallback exists.

use std::io::{Cursor, Read};

use pgp::composed::{Deserializable, Message, MessageBuilder, SignedPublicKey, SignedSecretKey};
use pgp::crypto::sym::SymmetricKeyAlgorithm;
use pgp::packet::{Signature, SignatureType};
use pgp::types::{KeyDetails, Password, Timestamp};
use rand::thread_rng;
use zeroize::Zeroize;

/// Matches `BoundedInputReader.MAX_DOCUMENT_BYTES` and the Swift/Kotlin codecs.
const MAX_DOCUMENT_BYTES: usize = 16 * 1024 * 1024;
/// Encryption input headroom, matching `OpenPgpBackupCodec.seal`.
const MAX_PLAINTEXT_BYTES: usize = MAX_DOCUMENT_BYTES - 64;
/// A backup is encrypted to a small, explicit recipient set.
const MAX_RECIPIENTS: usize = 64;
/// Compression is streaming, but nested layers are still bounded.
const MAX_COMPRESSION_LAYERS: usize = 16;

#[derive(Debug, thiserror::Error)]
pub enum OpenPgpError {
    #[error("invalid recipient key: {reason}")]
    InvalidRecipientKey { reason: String },
    #[error("invalid secret key: {reason}")]
    InvalidSecretKey { reason: String },
    #[error("invalid message: {reason}")]
    InvalidMessage { reason: String },
    #[error("decryption failed: {reason}")]
    DecryptionFailed { reason: String },
    #[error("empty output")]
    EmptyOutput,
    #[error("too large")]
    TooLarge,
    #[error("internal: {reason}")]
    Internal { reason: String },
}

fn recipient_error(reason: impl Into<String>) -> OpenPgpError {
    OpenPgpError::InvalidRecipientKey {
        reason: reason.into(),
    }
}

fn secret_error(reason: impl Into<String>) -> OpenPgpError {
    OpenPgpError::InvalidSecretKey {
        reason: reason.into(),
    }
}

fn message_error(reason: impl Into<String>) -> OpenPgpError {
    OpenPgpError::InvalidMessage {
        reason: reason.into(),
    }
}

fn decryption_error(reason: impl Into<String>) -> OpenPgpError {
    OpenPgpError::DecryptionFailed {
        reason: reason.into(),
    }
}

fn parse_public_key(block: &[u8]) -> Result<SignedPublicKey, OpenPgpError> {
    if block.is_empty() {
        return Err(recipient_error("empty recipient block"));
    }
    if block.len() > MAX_DOCUMENT_BYTES {
        return Err(OpenPgpError::TooLarge);
    }
    let (cert, _headers) = SignedPublicKey::from_reader_single(Cursor::new(block))
        .map_err(|e| recipient_error(format!("cannot parse recipient certificate: {e}")))?;
    cert.verify_bindings()
        .map_err(|e| recipient_error(format!("recipient binding verification failed: {e}")))?;
    Ok(cert)
}

fn parse_secret_key(block: &[u8]) -> Result<SignedSecretKey, OpenPgpError> {
    if block.is_empty() {
        return Err(secret_error("empty secret key block"));
    }
    if block.len() > MAX_DOCUMENT_BYTES {
        return Err(OpenPgpError::TooLarge);
    }
    let (key, _headers) = SignedSecretKey::from_reader_single(Cursor::new(block))
        .map_err(|e| secret_error(format!("cannot parse secret key: {e}")))?;
    key.verify_bindings()
        .map_err(|e| secret_error(format!("secret key binding verification failed: {e}")))?;
    Ok(key)
}

fn has_revocation(signatures: &[Signature], kind: SignatureType) -> bool {
    signatures.iter().any(|sig| sig.typ() == Some(kind))
}

/// Uses the newest self-signature that carries a key-expiration subpacket. A zero
/// duration means "no expiration" in OpenPGP, not "expired at creation".
fn expiration_elapsed(created: Timestamp, signatures: &[Signature], now: Timestamp) -> bool {
    let newest = signatures
        .iter()
        .filter_map(|sig| sig.created().map(|created| (created, sig)))
        .max_by_key(|(created, _)| *created);
    let Some((_, sig)) = newest else {
        return false;
    };
    let Some(expiration) = sig.key_expiration_time() else {
        return false;
    };
    expiration.as_secs() != 0
        && created.as_secs().saturating_add(expiration.as_secs()) <= now.as_secs()
}

fn primary_is_usable(cert: &SignedPublicKey, now: Timestamp) -> bool {
    if has_revocation(
        &cert.details.revocation_signatures,
        SignatureType::KeyRevocation,
    ) {
        return false;
    }
    let mut signatures: Vec<&Signature> = cert.details.direct_signatures.iter().collect();
    for user in &cert.details.users {
        signatures.extend(user.signatures.iter());
    }
    let owned: Vec<Signature> = signatures.into_iter().cloned().collect();
    !expiration_elapsed(cert.primary_key.created_at(), &owned, now)
}

fn subkey_is_usable(subkey: &pgp::composed::SignedPublicSubKey, now: Timestamp) -> bool {
    subkey.key.algorithm().can_encrypt()
        && !has_revocation(&subkey.signatures, SignatureType::SubkeyRevocation)
        && !expiration_elapsed(subkey.key.created_at(), &subkey.signatures, now)
}

/// Encrypts `plaintext` to every usable public certificate in `recipient_key_blocks`.
///
/// The result is a standard binary OpenPGP message (SEIPDv1, AES-256). Each
/// recipient certificate must contain at least one usable encryption key; revoked
/// or expired keys are never selected. Invalid input is rejected before any
/// ciphertext is produced.
pub fn encrypt(
    mut plaintext: Vec<u8>,
    recipient_key_blocks: Vec<Vec<u8>>,
) -> Result<Vec<u8>, OpenPgpError> {
    if recipient_key_blocks.is_empty() {
        return Err(recipient_error("at least one recipient required"));
    }
    if recipient_key_blocks.len() > MAX_RECIPIENTS {
        return Err(recipient_error("too many recipients"));
    }
    if recipient_key_blocks.iter().any(|block| block.is_empty()) {
        return Err(recipient_error("recipient key block is empty"));
    }
    if plaintext.is_empty() {
        return Err(OpenPgpError::EmptyOutput);
    }
    if plaintext.len() > MAX_PLAINTEXT_BYTES {
        return Err(OpenPgpError::TooLarge);
    }

    let mut certificates = Vec::with_capacity(recipient_key_blocks.len());
    for block in &recipient_key_blocks {
        certificates.push(parse_public_key(block)?);
    }

    let mut rng = thread_rng();
    let mut builder = MessageBuilder::from_bytes("", plaintext.clone())
        .seipd_v1(&mut rng, SymmetricKeyAlgorithm::AES256);
    let now = Timestamp::now();
    for cert in &certificates {
        if !primary_is_usable(cert, now) {
            return Err(recipient_error(
                "recipient primary key is revoked or expired",
            ));
        }
        let mut recipients = 0usize;
        for subkey in &cert.public_subkeys {
            if subkey_is_usable(subkey, now) {
                builder.encrypt_to_key(&mut rng, subkey).map_err(|e| {
                    recipient_error(format!("cannot encrypt to recipient subkey: {e}"))
                })?;
                recipients += 1;
            }
        }
        if recipients == 0 {
            if !cert.primary_key.algorithm().can_encrypt() {
                return Err(recipient_error(
                    "recipient certificate has no usable encryption key",
                ));
            }
            builder.encrypt_to_key(&mut rng, cert).map_err(|e| {
                recipient_error(format!("cannot encrypt to recipient primary key: {e}"))
            })?;
        }
    }

    let mut output = builder
        .to_vec(&mut rng)
        .map_err(|e| OpenPgpError::Internal {
            reason: format!("OpenPGP encryption failed: {e}"),
        })?;
    plaintext.zeroize();
    if output.is_empty() {
        return Err(OpenPgpError::EmptyOutput);
    }
    if output.len() > MAX_DOCUMENT_BYTES {
        output.zeroize();
        return Err(OpenPgpError::TooLarge);
    }
    Ok(output)
}

/// Decrypts a binary or ASCII-armored OpenPGP message with `secret_key_block`.
///
/// `passphrase` unlocks a passphrase-protected secret key; it is not a symmetric
/// message password. The message must actually be encrypted, authentication must
/// succeed, and the decrypted document is read through the same 16 MiB ceiling as
/// the password-backup path. Blank output is rejected.
pub fn decrypt(
    message: Vec<u8>,
    mut secret_key_block: Vec<u8>,
    mut passphrase: Option<String>,
) -> Result<Vec<u8>, OpenPgpError> {
    if message.is_empty() {
        return Err(message_error("empty message"));
    }
    if message.len() > MAX_DOCUMENT_BYTES {
        return Err(OpenPgpError::TooLarge);
    }
    if secret_key_block.is_empty() {
        return Err(secret_error("missing secret key"));
    }

    let secret_key = parse_secret_key(&secret_key_block)?;
    secret_key_block.zeroize();
    let password = match passphrase.as_deref() {
        Some(value) if !value.is_empty() => Password::from(value),
        _ => Password::empty(),
    };
    if let Some(value) = passphrase.as_mut() {
        value.zeroize();
    }

    // Try binary packets first, then ASCII armor. Both are standard OpenPGP
    // encodings; neither path accepts a plaintext marker or an unencrypted file.
    let parsed = Message::from_bytes(Cursor::new(message.as_slice()))
        .or_else(|_| {
            Message::from_armor(Cursor::new(message.as_slice())).map(|(msg, _headers)| msg)
        })
        .map_err(|e| message_error(format!("cannot parse OpenPGP message: {e}")))?;
    if matches!(parsed, Message::Literal { .. } | Message::Compressed { .. }) {
        return Err(message_error("message is not encrypted"));
    }

    let mut decrypted = parsed.decrypt(&password, &secret_key).map_err(|e| {
        decryption_error(format!(
            "wrong key, wrong passphrase, or failed authentication: {e}"
        ))
    })?;

    let mut layers = 0usize;
    while decrypted.is_compressed() {
        if layers >= MAX_COMPRESSION_LAYERS {
            return Err(OpenPgpError::TooLarge);
        }
        decrypted = decrypted
            .decompress()
            .map_err(|e| decryption_error(format!("cannot decompress OpenPGP message: {e}")))?;
        layers += 1;
    }

    // Read manually rather than the unbounded as_data_vec convenience: a bomb must hit the
    // document ceiling instead of allocating without bound. Reaching EOF also
    // drives rpgp's SEIPDv1 MDC / trailing-data checks.
    let mut output = Vec::new();
    let mut buffer = [0u8; 8192];
    loop {
        let read = decrypted
            .read(&mut buffer)
            .map_err(|e| decryption_error(format!("OpenPGP authentication or read failed: {e}")))?;
        if read == 0 {
            break;
        }
        if output.len().saturating_add(read) > MAX_DOCUMENT_BYTES {
            output.zeroize();
            return Err(OpenPgpError::TooLarge);
        }
        output.extend_from_slice(&buffer[..read]);
    }
    if output.is_empty() || output.iter().all(|byte| byte.is_ascii_whitespace()) {
        output.zeroize();
        return Err(OpenPgpError::EmptyOutput);
    }
    Ok(output)
}

/// The rpgp crate version this backend was built against.
pub fn rpgp_version() -> String {
    "pgp 0.21.0".to_string()
}

/// The wrapper crate version.
pub fn crate_version() -> String {
    env!("CARGO_PKG_VERSION").to_string()
}

uniffi::include_scaffolding!("offlineotp_openpgp");

#[cfg(test)]
mod tests {
    use super::*;
    use pgp::composed::{
        ArmorOptions, EncryptionCaps, KeyType, SecretKeyParamsBuilder, SubkeyParamsBuilder,
    };
    use pgp::crypto::ecc_curve::ECCCurve;
    use pgp::ser::Serialize;
    use pgp::types::CompressionAlgorithm;

    fn generate_test_key(uid: &str, passphrase: Option<&str>) -> SignedSecretKey {
        let mut encryptkey = SubkeyParamsBuilder::default();
        encryptkey
            .key_type(KeyType::ECDH(ECCCurve::Curve25519Legacy))
            .can_sign(false)
            .can_encrypt(EncryptionCaps::All)
            .can_authenticate(false)
            .passphrase(passphrase.map(str::to_string));

        let mut key_params = SecretKeyParamsBuilder::default();
        key_params
            .key_type(KeyType::Ed25519Legacy)
            .can_certify(true)
            .can_sign(true)
            .can_encrypt(EncryptionCaps::None)
            .primary_user_id(uid.into())
            .passphrase(passphrase.map(str::to_string))
            .subkeys(vec![encryptkey.build().expect("encryption subkey")]);

        key_params
            .build()
            .expect("key params")
            .generate(thread_rng())
            .expect("generate key")
    }

    fn pub_key_bytes(ssk: &SignedSecretKey) -> Vec<u8> {
        let pk = ssk.to_public_key();
        let mut buf = Vec::new();
        pk.to_writer(&mut buf).expect("serialize public key");
        buf
    }

    fn sec_key_bytes(ssk: &SignedSecretKey) -> Vec<u8> {
        let mut buf = Vec::new();
        ssk.to_writer(&mut buf).expect("serialize secret key");
        buf
    }

    fn sample_document() -> Vec<u8> {
        br#"[{"type":"TOTP","secret":"JBSWY3DPEHPK3PXP","issuer":"Example","label":"alice@example.com","digits":6,"algorithm":"SHA1","period":30}]"#.to_vec()
    }

    #[test]
    fn round_trip_single_recipient() {
        let ssk = generate_test_key("roundtrip@example.com", None);
        let plaintext = sample_document();
        let encrypted = encrypt(plaintext.clone(), vec![pub_key_bytes(&ssk)]).expect("encrypt");
        assert!(!encrypted.starts_with(b"OFFLINEOTP"));
        assert_ne!(encrypted, plaintext);
        assert!(encrypted.len() > plaintext.len());
        let decrypted = decrypt(encrypted, sec_key_bytes(&ssk), None).expect("decrypt");
        assert_eq!(decrypted, plaintext);
    }

    #[test]
    fn round_trip_two_recipients() {
        let alice = generate_test_key("alice@example.com", None);
        let bob = generate_test_key("bob@example.com", None);
        let plaintext = b"hello multi-recipient".to_vec();
        let encrypted = encrypt(
            plaintext.clone(),
            vec![pub_key_bytes(&alice), pub_key_bytes(&bob)],
        )
        .expect("encrypt multi");
        assert_eq!(
            decrypt(encrypted.clone(), sec_key_bytes(&alice), None).expect("alice"),
            plaintext
        );
        assert_eq!(
            decrypt(encrypted, sec_key_bytes(&bob), None).expect("bob"),
            plaintext
        );
    }

    #[test]
    fn armored_keys_and_message_round_trip() {
        let ssk = generate_test_key("armored@example.com", None);
        let public = ssk
            .to_public_key()
            .to_armored_string(ArmorOptions::default().into())
            .expect("armored public key");
        let secret = ssk
            .to_armored_string(ArmorOptions::default().into())
            .expect("armored secret key");
        let plaintext = sample_document();
        let mut builder = MessageBuilder::from_bytes("", plaintext.clone())
            .seipd_v1(thread_rng(), SymmetricKeyAlgorithm::AES256);
        builder
            .encrypt_to_key(thread_rng(), &ssk.to_public_key().public_subkeys[0])
            .expect("recipient");
        let armored = builder
            .to_armored_string(thread_rng(), ArmorOptions::default())
            .expect("armored message");
        let encrypted = encrypt(plaintext.clone(), vec![public.into_bytes()]).expect("encrypt");
        assert_eq!(
            decrypt(armored.into_bytes(), secret.clone().into_bytes(), None)
                .expect("armored decrypt"),
            plaintext
        );
        assert_eq!(
            decrypt(encrypted, secret.into_bytes(), None).expect("binary decrypt"),
            plaintext
        );
    }

    #[test]
    fn compressed_message_round_trips() {
        let ssk = generate_test_key("compressed@example.com", None);
        let plaintext = sample_document();
        let mut base = MessageBuilder::from_bytes("", plaintext.clone());
        base.compression(CompressionAlgorithm::ZIP);
        let mut builder = base.seipd_v1(thread_rng(), SymmetricKeyAlgorithm::AES256);
        builder
            .encrypt_to_key(thread_rng(), &ssk.to_public_key().public_subkeys[0])
            .expect("recipient");
        let encrypted = builder.to_vec(thread_rng()).expect("compress encrypt");
        assert_eq!(
            decrypt(encrypted, sec_key_bytes(&ssk), None).expect("decompress decrypt"),
            plaintext
        );
    }

    #[test]
    fn rejects_empty_recipient_set() {
        let err = encrypt(b"hi".to_vec(), vec![]).unwrap_err();
        assert!(matches!(err, OpenPgpError::InvalidRecipientKey { .. }));
    }

    #[test]
    fn rejects_empty_recipient_block_and_invalid_key() {
        assert!(matches!(
            encrypt(b"hi".to_vec(), vec![vec![]]).unwrap_err(),
            OpenPgpError::InvalidRecipientKey { .. }
        ));
        assert!(matches!(
            encrypt(b"hi".to_vec(), vec![b"not a key".to_vec()]).unwrap_err(),
            OpenPgpError::InvalidRecipientKey { .. }
        ));
    }

    #[test]
    fn rejects_wrong_secret_key() {
        let recipient = generate_test_key("recipient@example.com", None);
        let stranger = generate_test_key("stranger@example.com", None);
        let encrypted =
            encrypt(b"secret".to_vec(), vec![pub_key_bytes(&recipient)]).expect("encrypt");
        assert!(decrypt(encrypted, sec_key_bytes(&stranger), None).is_err());
    }

    #[test]
    fn passphrase_protected_key_requires_the_right_passphrase() {
        let ssk = generate_test_key("protected@example.com", Some("correct horse"));
        let encrypted =
            encrypt(b"passphrase test".to_vec(), vec![pub_key_bytes(&ssk)]).expect("encrypt");
        let secret = sec_key_bytes(&ssk);
        assert!(decrypt(encrypted.clone(), secret.clone(), None).is_err());
        assert!(decrypt(encrypted.clone(), secret.clone(), Some("wrong".into())).is_err());
        assert_eq!(
            decrypt(encrypted, secret, Some("correct horse".into())).expect("correct passphrase"),
            b"passphrase test"
        );
    }

    #[test]
    fn rejects_plaintext_and_marker_files() {
        let ssk = generate_test_key("plaintext@example.com", None);
        let secret = sec_key_bytes(&ssk);
        assert!(decrypt(b"just plaintext".to_vec(), secret.clone(), None).is_err());
        let mut marker = b"OFFLINEOTP-STUB-PGP:".to_vec();
        marker.extend_from_slice(b"just plaintext");
        assert!(decrypt(marker, secret, None).is_err());
    }

    #[test]
    fn rejects_tampered_ciphertext() {
        let ssk = generate_test_key("tamper@example.com", None);
        let mut encrypted = encrypt(sample_document(), vec![pub_key_bytes(&ssk)]).expect("encrypt");
        let last = encrypted.len() - 1;
        encrypted[last] ^= 0x01;
        assert!(decrypt(encrypted, sec_key_bytes(&ssk), None).is_err());
    }

    #[test]
    fn rejects_blank_output() {
        let ssk = generate_test_key("blank@example.com", None);
        let encrypted =
            encrypt(b"   \n\t".to_vec(), vec![pub_key_bytes(&ssk)]).expect("encrypt blank");
        assert!(matches!(
            decrypt(encrypted, sec_key_bytes(&ssk), None).unwrap_err(),
            OpenPgpError::EmptyOutput
        ));
    }

    #[test]
    fn enforces_document_bounds() {
        let ssk = generate_test_key("bounds@example.com", None);
        let public = pub_key_bytes(&ssk);
        assert!(matches!(
            encrypt(vec![], vec![public.clone()]).unwrap_err(),
            OpenPgpError::EmptyOutput
        ));
        assert!(matches!(
            encrypt(vec![b'a'; MAX_PLAINTEXT_BYTES + 1], vec![public]).unwrap_err(),
            OpenPgpError::TooLarge
        ));
        assert!(matches!(
            decrypt(
                vec![b'a'; MAX_DOCUMENT_BYTES + 1],
                sec_key_bytes(&ssk),
                None
            )
            .unwrap_err(),
            OpenPgpError::TooLarge
        ));
    }

    #[test]
    fn version_strings() {
        assert_eq!(rpgp_version(), "pgp 0.21.0");
        assert_eq!(crate_version(), "0.1.0");
    }
}
