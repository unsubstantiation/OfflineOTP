fn main() {
    // UniFFI scaffolding is generated from the reviewed UDL contract. The same UDL
    // generates the committed Kotlin/Swift bindings, so the ABI cannot drift from
    // the Rust implementation.
    uniffi::generate_scaffolding("src/offlineotp_openpgp.udl").expect("UniFFI scaffolding");
}
