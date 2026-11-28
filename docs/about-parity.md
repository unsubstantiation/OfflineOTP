# About content and navigation

`shared/about/content.json` owns common default-language attribution, project links
and trademark disclaimers. Generate/check committed output with:

```sh
python3 scripts/generate-about.py
python3 scripts/generate-about.py --check
```

Outputs are `ui-foundation/commonMain/.../vault/AboutInfo.kt` and Android's default
`strings_about.xml`. Android translations are not overwritten. iOS hosts now call
the shared Kotlin About export; no duplicate Swift link dictionary is generated.

Shared Compose About content lives in `:ui-foundation`, not a native SwiftUI view
or the JVM-only model layer. Desktop provides a direct About action. Android keeps
host integration for generated dependency/license content, flavor donation links
and special-feature activation. Platform dependency inventories are not interchangeable.

Displayed versions come from embedded AKST build metadata, not the launch clock.
External links open only after an explicit user action; the browser owns network
access. A common page does not establish native browser dispatch/accessibility
behavior on every platform. See [current verification](shared-ui-migration-status.md).
