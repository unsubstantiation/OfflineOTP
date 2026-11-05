import SwiftUI
import OfflineOTPKit

/// Adding and editing an entry.
///
/// The form collects the same values as the two existing dialogs and hands them to `EntryDraft`,
/// which is the port of the shared `EntryDraft` in `:core`: the same kinds are offered, a blank
/// timing field means the same thing, an unparsable number is refused with the same sentence, and a
/// new entry gets the same usage stamp. Nothing here writes the vault itself — the sheet builds an
/// entry and the store persists it, so a failed write keeps the form open instead of losing typing.
///
/// Two deliberate limits, both recorded in `docs/ios-client-plan.md`: SSH keys and the structured
/// credential kinds cannot be created here (their seeds are framed material this build does not
/// produce), and an existing entry must be revealed before it can be edited, the rule both other
/// clients apply.
struct EntryEditorView: View {
    @ObservedObject var store: VaultStore
    /// nil to add, or the stored position being replaced.
    let index: Int?

    @Environment(\.dismiss) private var dismiss

    @State private var kind: EntryKind = .totp
    @State private var issuer = ""
    @State private var label = ""
    @State private var secret = ""
    @State private var digits = "6"
    @State private var timing = "30"
    @State private var algorithm: OtpHashAlgorithm = .sha1
    @State private var pin = ""
    @State private var tags = ""
    @State private var errorText: String?
    @State private var loaded = false

    private var existing: Entry? {
        guard let index, store.entries.indices.contains(index) else { return nil }
        return store.entries[index]
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Picker("Type", selection: $kind) {
                        ForEach(EntryDraft.offerableKinds, id: \.self) { kind in
                            Text(kindTitle(kind)).tag(kind)
                        }
                    }
                    TextField("Issuer", text: $issuer)
                    TextField(kind == .note ? "Title" : "Label", text: $label)
                }

                Section {
                    if kind == .note {
                        TextEditor(text: $secret)
                            .frame(minHeight: 120)
                            .textInputAutocapitalization(.sentences)
                    } else {
                        TextField(kind == .motp ? "Hex seed" : "Secret (Base32)", text: $secret)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                    }
                } header: {
                    Text(kind == .note ? "Note" : "Secret")
                } footer: {
                    Text(secretHint)
                }

                if kind == .totp || kind == .steam || kind == .hotp {
                    Section {
                        TextField("Digits", text: $digits)
                            .keyboardType(.numberPad)
                        if kind == .hotp {
                            TextField("Counter", text: $timing)
                                .keyboardType(.numberPad)
                        } else {
                            TextField("Period (seconds)", text: $timing)
                                .keyboardType(.numberPad)
                        }
                        Picker("Algorithm", selection: $algorithm) {
                            ForEach(OtpHashAlgorithm.allCases, id: \.self) { algorithm in
                                Text(algorithm.rawValue).tag(algorithm)
                            }
                        }
                    } header: {
                        Text("Parameters")
                    } footer: {
                        if kind == .steam {
                            Text("Steam codes use five characters and a period of 30 seconds by "
                                 + "default.")
                        }
                    }
                }

                if kind == .motp {
                    Section {
                        TextField("PIN", text: $pin)
                            .keyboardType(.numberPad)
                    } footer: {
                        Text("mOTP rolls over every 10 seconds, whatever period the entry stores. "
                             + "Without a PIN the entry shows PINREQ instead of a code.")
                    }
                }

                Section {
                    TextField("Tags, separated by commas", text: $tags)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                } footer: {
                    Text("Up to \(InputPolicy.maxTags) tags of at most \(InputPolicy.maxTagChars) "
                         + "characters each — the same limits the other clients apply.")
                }

                if let errorText {
                    Section {
                        Text(errorText)
                            .foregroundColor(ForestPalette.warning)
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .background(ForestPalette.background)
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save", action: save)
                }
            }
            .onAppear(perform: prefill)
        }
        .preferredColorScheme(.dark)
    }

    private var title: String {
        if existing != nil { return "Edit entry" }
        return kind == .note ? "Add secure note" : "Add entry"
    }

    private var secretHint: String {
        switch kind {
        case .note: return "The note is stored encrypted with the rest of the vault."
        case .motp: return "The mOTP seed is hexadecimal text, exactly as the other clients store it."
        default: return "Spaces and line breaks are ignored, so a wrapped secret can be pasted."
        }
    }

    private func kindTitle(_ kind: EntryKind) -> String {
        switch kind {
        case .totp: return "Time-based (TOTP)"
        case .hotp: return "Counter-based (HOTP)"
        case .motp: return "mOTP"
        case .steam: return "Steam"
        case .note: return "Secure note"
        case .sshKey: return "SSH key"
        case .password, .passwordArchive: return "Password archive"
        case .email: return "Email account"
        }
    }

    /// Fills the form from the entry being edited. The secret is shown because this is the only way
    /// to keep it while changing anything else — the desktop form does the same, and an empty field
    /// on save keeps whatever the entry already held.
    private func prefill() {
        guard !loaded else { return }
        loaded = true
        guard let existing, let existingKind = existing.kind,
              EntryDraft.offerableKinds.contains(existingKind) else { return }
        kind = existingKind
        issuer = existing.issuer
        label = existing.label
        secret = editableSecret(of: existing, kind: existingKind)
        digits = String(existing.digits)
        switch existingKind {
        case .hotp:
            timing = String(existing.counter)
        case .totp, .steam:
            timing = String(existing.period)
        default:
            timing = String(existing.period)
        }
        algorithm = existing.algorithm
        pin = existing.pin
        tags = existing.tags.joined(separator: ", ")
    }

    /// The seed as this form takes it back in: Base32 for the OTP kinds, the raw text for mOTP and
    /// for a note.
    private func editableSecret(of entry: Entry, kind: EntryKind) -> String {
        switch kind {
        case .note: return entry.noteText ?? ""
        case .motp: return entry.rawSecretText ?? ""
        default: return entry.secretBase32 ?? ""
        }
    }

    private func save() {
        do {
            // An empty secret field on an edit keeps the seed the entry already holds — the rule
            // `EntryDialog.enteredSecret` applies on the desktop, so clearing the field cannot lose
            // a working entry's seed.
            let typedSecret: String
            if secret.isEmpty, let existing, let existingKind = existing.kind,
               existingKind == kind {
                typedSecret = editableSecret(of: existing, kind: existingKind)
            } else {
                typedSecret = secret
            }
            let built = try EntryDraft.create(
                kind: kind,
                issuer: issuer.trimmingCharacters(in: .whitespacesAndNewlines),
                label: label,
                secret: typedSecret,
                digits: digits,
                timing: timing,
                algorithm: algorithm,
                pin: pin,
                tags: splitTags(tags),
                newEntry: existing == nil)

            if let index, let existing {
                let replacement = EntryDraft.preservingMetadata(of: existing, in: built)
                guard store.update(at: index, with: replacement) else {
                    errorText = store.message ?? "The entry could not be saved."
                    return
                }
            } else {
                guard store.add(built) else {
                    errorText = store.message ?? "The entry could not be added."
                    return
                }
            }
            dismiss()
        } catch let draftError as EntryDraftError {
            errorText = draftError.message
        } catch let entryError as EntryError {
            errorText = message(for: entryError)
        } catch {
            errorText = "The entry could not be built."
        }
    }

    private func splitTags(_ text: String) -> [String] {
        text.split(separator: ",").map(String.init)
    }

    /// The loader's verdict, in words a person can act on. Every branch names what is wrong with the
    /// value that was typed rather than naming an internal error.
    private func message(for error: EntryError) -> String {
        switch error {
        case .invalidSecret, .missingSecret:
            return "The secret is not valid Base32 for this kind of entry."
        case .missingCounter:
            return "A counter-based entry needs a counter."
        case .invalidParameters:
            return "Digits must be 1–9, the period 1–\(InputPolicy.maxPeriodSeconds) seconds, and a "
                + "Steam entry exactly 5 digits."
        case .invalidNote:
            return "A note needs 1–\(InputPolicy.maxNoteCharacters) characters."
        case .tooManyTags:
            return "Use at most \(InputPolicy.maxTags) tags."
        case .unsupportedKind:
            return "That kind of entry is not supported."
        case .notAnObject:
            return "The entry could not be built."
        case .notAnOtpEntry:
            return "That entry has no code to generate."
        }
    }
}
