# Compose Multiplatform stubs (compile-only)

Hand-written stand-ins for the Compose artifacts `:ui` depends on, used by
[`../compile-ui.sh`](../compile-ui.sh). The sandbox can reach the Kotlin compiler and a JDK but not
Maven Central, so the shared UI sources are type-checked against these declarations instead of the
published jars.

**They are not a runtime and prove nothing about behaviour.** No composable is executed, nothing is
measured, drawn, laid out or recomposed, and the Android target's `androidx` artifacts are not
involved at all. A green gate means:

- `ui/src/commonMain` compiles under `-jvm-target 1.8` against `:core` and no Android/desktop API;
- a file that reaches for `android.*`, `java.awt`, storage, crypto or session types fails loudly;
- the Compose-free projection layer compiles and its JUnit tests pass on a host JVM.

The declarations mirror the real signatures for the subset the screen uses (and only that subset),
so a call site that compiles here should compile against the artifacts — but Gradle is the build of
record. Anything these stubs accept that the real API rejects (for example an argument name or a
default that differs) is a gap to fix here, not evidence the code is correct.

Files are grouped by the package they stand in for, under the real package names, so the imports in
`ui/src/commonMain` are unchanged between this gate and Gradle.
