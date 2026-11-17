# About content and navigation

The desktop About action is a direct button above the main content, not the sole item in a Help
submenu. It remains available on the locked screen. Opening or closing About does not change
vault state. The scrollable Compose presentation lives in `:ui`, not the desktop host.

## Common source

`shared/about/content.json` owns the common default-language About text and fixed project URLs.
Run `python3 scripts/generate-about.py` after changes. Committed outputs are:

- `ui-model/.../vault/AboutInfo.kt`: toolkit-free content and link model, used by desktop and
  Android's existing external-link callbacks.
- `app/src/main/res/values/strings_about.xml`: Android default text; translated resource sets
  are not overwritten.
- `ios/App/Sources/AboutInfo.swift`: the same content for native SwiftUI.

`python3 scripts/generate-about.py --check` and the Python test suite reject stale outputs.
No build-time network request is needed to generate or display About. Version labels continue to
come from embedded build metadata (AKST), not the clock at the time the app is opened.

All clients expose website, releases, changelog, custom noncommercial license, source, issues,
upstream author credits, original app, contributors, support/contributing and all four thumbnail
trademark disclaimers. Desktop and iOS now explain the upstream MIT attribution separately from
the current application's custom license. External links open only after an explicit user tap;
the browser, not the vault, accesses the network. Desktop displays the URL if browser dispatch
fails; Android retains its clipboard fallback.

## Platform boundaries retained deliberately

Android keeps its native, localized About view, generated Libraries tab, seven-tap special-feature
activation and F-Droid donation extras. Replacing these with an English-only Compose dialog would
lose existing functionality. Common content is shared, while layout and platform callbacks remain
native. iOS uses generated common content with SwiftUI rather than JVM UI code. iOS also retains
its platform cryptography/network labels. Platform dependency inventories are not interchangeable:
this change does not pretend Android's generated Libraries list describes desktop or iOS.

## Validation

The sandbox compiles Kotlin UI/host wiring and executes JVM tests, including existing Android
About link, package-version fallback, donation and special-feature tap regressions. Python checks
cover generated-output freshness, content coverage and navigation wiring. These checks do not
execute Compose or SwiftUI. Native testing remains required for scrolling at small window sizes,
keyboard/screen-reader navigation, external browser dispatch, Android translations and Xcode builds.
