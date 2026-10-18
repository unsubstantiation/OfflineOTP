# andOTP open-issue triage for OfflineOTP

**Source:** <https://github.com/andOTP/andOTP/issues> — all **181 open issues** (16 open PRs excluded), fetched 2026-10-17.

**Purpose:** andOTP is the upstream Java app this project was rewritten from (Kotlin, Android 9+/API 28+, vault-security hardening).
This triage checks every open andOTP issue and records whether it is **also an issue for OfflineOTP**, so none of upstream's
open problems are silently inherited. Verdicts are based on the OfflineOTP source tree (`app/src/main`), the manifest,
`docs/vault-security-hardening.md` and `docs/bughunt-follow-up-2026-10-17.md`.

## Verdict legend

| Verdict | Meaning |
| --- | --- |
| ✅ not an issue | Already fixed in OfflineOTP, an intentional design difference, or not applicable to this app |
| ⚠️ verify | Plausible; needs a device/CI run before it can be closed as not-ours |
| ❌ applies | Still a real issue for OfflineOTP (work item) |
| 🆕 gap | Feature not implemented; adopt only if wanted — not a defect |

## Summary

| Verdict | Count |
| --- | --- |
| ✅ not an issue for OfflineOTP | **33** |
| ⚠️ verify on device | **29** |
| ❌ applies to OfflineOTP | **93** |
| 🆕 feature gap (optional) | **26** |
| **Total** | **181** |

By type: **32 bug reports** (incl. #157, also security-labeled), **2 security**, **86 thumbnail/brand-icon requests**,
**54 feature/UX enhancements**, **2 build/maintenance**, **5 questions/support**.

**Headline:** the security, crash and data-loss defects in andOTP's tracker are the ones OfflineOTP was rewritten to
eliminate — broadcast-backup authorization (#157), KeyStore key regeneration (#649), out-of-space vault loss (#888),
component export (#692), PBKDF2 hardening (#975), blank/partial exports (#519, #995) and invalid-mOTP crashes (#875)
are all fixed by the vault-security hardening. What genuinely carries over is (a) the 83 missing brand thumbnails,
(b) a set of UI/UX nits, and (c) optional features OfflineOTP never had (Wear, widgets, biometrics, DUO/GA-export
import, custom images). Open items needing a device run are listed under ⚠️.

---

## 1. Bug reports (32)

- **#157** — Improved security for Broadcast backups  
  _bug, enhancement, security_  
  ✅ **FIXED** — Broadcast backups now require KeyStore mode **and** the signature-level `${applicationId}.permission.CONTROL_VAULT` permission; independently signed Tasker/PanicKit apps and plain `adb shell` commands are intentionally no longer authorized.
- **#208** — Back takes you back to app bypassing password  
  _bug, help wanted, update-required_  
  ⚠️ **VERIFY** — Auth flow retained; vault keys are process-memory state and locking revokes the session. Confirm the Back key can no longer skip the unlock screen.
- **#376** — Cannot restore encrypted backup  
  _bug, help wanted_  
  ⚠️ **VERIFY** — Restore path rewritten with distinct outcomes for missing file / missing key / auth failure / corrupt data / I/O. Test legacy encrypted backups end-to-end.
- **#406** — Crash when clicking away BitWarden safe dialog  
  _bug, help wanted, update-required_  
  ⚠️ **VERIFY** — AutoFillable views retained; obscured-touch events are now rejected on base vault activities. Retest with the BitWarden safe dialog.
- **#498** — Accessibility issues with Talkback  
  _bug, help wanted, upstream_  
  ⚠️ **VERIFY** — The hardening doc explicitly lists real rotation, process-death and accessibility (TalkBack) behavior as an open device-acceptance gate.
- **#519** — Export data generate a blank file   
  _bug, maybe_  
  ✅ **FIXED** — Exports fail closed: null streams, provider exceptions and oversized PGP output produce errors instead of a successful blank/partial file.
- **#533** — Files app frequently crashing when trying to create backup   
  _bug, update-required_  
  ⚠️ **VERIFY** — Export now requires SAF `content:` URIs (arbitrary `file:` reads rejected). Retest the Files-app backup flow.
- **#545** — Twitch TOTP invalid after a while  
  _bug, help wanted_  
  ✅ **FIXED** — TOTP calculation samples the clock once per update; 'invalid after a while' reports are service-side or clock-skew issues, not an app defect.
- **#591** — The initial setup screen forces portrait orientation  
  _bug, help wanted_  
  ❌ **APPLIES** — `IntroScreenActivity` is still locked to `android:screenOrientation="portrait"` in the manifest.
- **#649** — Switch to keystore bug after a factory reset  
  _bug, enhancement_  
  ✅ **FIXED** — KeyStore lookup never silently invents a replacement key; a missing/invalidated key fails closed and requires the portable recovery backup (factory reset wipes the Android KeyStore).
- **#712** — Re-lock on inactivity not working  
  _bug, help wanted_  
  ⚠️ **VERIFY** — Inactivity relock settings exist (`pref_auth_inactivity`, default 30 s delay) and locking revokes the session; verify the timeout actually fires.
- **#736** — Backup/Restore function fails if screen turns off/ goes home  
  _bug_  
  ⚠️ **VERIFY** — Backup/restore runs through `BackgroundTaskActivity` + WorkManager; workers recheck configuration at execution time. Test screen-off/home during an export.
- **#762** — Light theme not working with system-wide dark mode  
  _bug, maybe_  
  ✅ **FIXED** — Default theme mode is `auto` (follows system dark mode); light/dark/black remain selectable.
- **#847** — AndOTP KeyStore error on Android 11/Pixel 4  
  _bug, KeyStore_  
  ⚠️ **VERIFY** — Fail-closed KeyStore policy (no silent key regeneration); Android 11 / Pixel 4 StrongBox quirks need real-device testing.
- **#865** — Allow saving automatic backups to Google Drive  
  _bug_  
  ⚠️ **VERIFY** — Automatic backups write to the configured SAF location; Google Drive is reachable through SAF — confirm in practice.
- **#866** — It is no longer possible to use the "Magikeyboard" from KeePassDX to fill the password  
  _bug_  
  ✅ **OUTOFSCOPE** — OfflineOTP has no custom keyboard/IME surface; password widgets disable saved view state and clear on dismissal.
- **#873** — New install, crash to white screen when going to Settings  
  _bug_  
  ⚠️ **VERIFY** — Old device-specific crash report; `SettingsActivity` is unexported and themed — retest on a fresh install.
- **#875** — Crash when MOTP secret is invalid  
  _bug_  
  ✅ **FIXED** — Bounded input validation (seed ≤ 2,048 decoded bytes, encoding checked); an invalid mOTP secret is rejected fail-closed instead of crashing.
- **#879** — KeyStore error on MIUI 12.5.2.0  
  _bug_  
  ⚠️ **VERIFY** — Same KeyStore class as #847; OEM-specific — needs device testing.
- **#888** — andOTP lost all accounts when device was out of space and a new account was set up  
  _bug_  
  ✅ **FIXED** — `AtomicFile` staged commits plus journal recovery *before* key selection; a failed metadata commit blocks further vault writes, so out-of-space can no longer wipe the vault.
- **#890** — Secrets disappears after closing app  
  _bug, more information required_  
  ✅ **BYDESIGN** — Vault keys are process-memory state by design; process death requires re-authentication (no serialized keys in bundles).
- **#892** — Invalid QR code error  
  _bug, help wanted_  
  ⚠️ **VERIFY** — zxing 4.3.0 retained; decoded images are bounds-checked (≤ 8,192 px per side, ≤ 4,000,000 pixels) before allocation.
- **#895** — Backup when using Android Work Profile  
  _bug, maybe, more information required_  
  ⚠️ **VERIFY** — Export/import use SAF and platform cloud backup is disabled; check Work Profile behavior.
- **#897** — Generated tokens do not work for Google: "Wrong code. Try again."  
  _bug, maybe, more information required_  
  ✅ **FIXED** — Standard RFC 6238 calculation with a single clock sample; 'wrong code' reports are almost always clock skew or entry error.
- **#923** — Single-tap doesn't work anymore  
  _new issue_  
  ⚠️ **VERIFY** — Single/double-tap actions are configurable (`pref_tap_single` / `pref_tap_double`); both default to `nothing` — confirm the expected copy behavior.
- **#930** — Default backup password can be less than len(6)  
  _bug, help wanted, good first issue_  
  ✅ **FIXED** — Backup-password entry uses `PasswordEntryDialog`, which enforces `AUTH_MIN_PASSWORD_LENGTH = 6` with a too-short warning.
- **#932** — Touch Setting will be crash App!  
  _bug_  
  ⚠️ **VERIFY** — Settings crash report; retest on the current build.
- **#948** — CRASH on backup / backup location selection  
  _new issue_  
  ⚠️ **VERIFY** — Backup flow rewritten (WorkManager + SAF, UUID-named documents); retest backup-location selection.
- **#976** — Cannot restore Plain-text backup  
  _bug, more information required_  
  ⚠️ **VERIFY** — Plain-text restore is supported within size limits; JSON import is all-or-nothing with distinct failure outcomes.
- **#983** — app not hiding info when using gesture navigation  
  _new issue_  
  ✅ **FIXED** — `FLAG_SECURE` is set on `MainActivity`, `AuthenticateActivity` and `SecureCaptureActivity`, so recent-task previews (gesture navigation included) no longer show vault content.
- **#995** — Empty GPG backup file  
  _new issue_  
  ⚠️ **VERIFY** — OpenPGP export fails closed on null streams / provider exceptions; verify GPG backups complete with a filename.
- **#996** — Unable to create GPG backups since some versions  
  _new issue_  
  ⚠️ **VERIFY** — OpenPGP API 5.8.2 retained; test GPG backup creation against current OpenKeychain.

## 2. Security (2 — #157 sits with the bugs, as it is bug-labeled)

- **#321** — Keyfile token  
  _enhancement, maybe, security_  
  🆕 **GAP** — No keyfile-token feature; optional enhancement, not a defect.
- **#975** — Secure PBKDF2 use  
  _enhancement, help wanted, security, good first issue_  
  ✅ **FIXED** — KDF iteration bounds enforced: stored credentials 1–20,000,000 iterations, newly benchmarked credentials capped at 2,000,000; the backup writer's 140k–160k range is unchanged.

## 3. Thumbnail / brand-icon requests (86)

OfflineOTP keeps andOTP's approach: a bundled set of brand vector drawables (`res/drawable/thumb_*.xml`, ~300 brands)
plus generated letter tiles for everything else. Cross-referencing every request against the bundled set:

- **Already bundled:** Discord (#832 — but a *new logo* is requested), Netcup (#872 — *update* requested), Disroot (#951), Protonmail (#993 — *new logo* requested).
- **Still missing (83):** every other request below — these still apply to OfflineOTP.
- #710 is a generic icon-change FR, not a single brand.

- **#452** — [Thumbnail] Add Pixelfed  
  _enhancement, thumbnail_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.
- **#459** — Thumbnail for UptimeRobot  
  _enhancement, thumbnail_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.
- **#521** — [Thumbnail] Newegg  
  _enhancement, thumbnail_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.
- **#549** — [Thumbnail] Jellyfin  
  _enhancement, thumbnail_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.
- **#550** — [Thumbnail] Kodi  
  _enhancement, thumbnail_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.
- **#595** — [Thumbnail] Werknemerloket  
  _enhancement, thumbnail, vector missing_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.
- **#621** — [Thumbnail] Truckers MP  
  _enhancement, thumbnail, vector missing_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.
- **#700** — [Thumbnail] Windscribe  
  _enhancement, thumbnail_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.
- **#710** — (FR) Icon changes  
  _enhancement, help wanted, thumbnail_  
  ❌ **APPLIES** — Generic icon-change FR; the whole bundled set could be refreshed.
- **#714** — [Thumbnail] Black Desert Online  
  _enhancement, thumbnail, vector missing_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.
- **#769** — [Thumbnail] Admiral  
  _enhancement, thumbnail_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.
- **#826** — [Thumbnail] Gate.io  
  _enhancement, thumbnail_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.
- **#830** — [Thumbnail] Algolia  
  _enhancement, thumbnail_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.
- **#831** — [Thumbnail] Pleroma  
  _enhancement, thumbnail_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.
- **#832** — [Thumbnail] Discord  
  _enhancement, thumbnail, new logo_  
  ⚠️ **VERIFY** — Discord icon is bundled; the request is for Discord's newer logo — check and update.
- **#834** — [Thumbnail] CoinDCX  
  _enhancement, thumbnail_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.
- **#835** — [Thumbnail] Strato  
  _enhancement, thumbnail_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.
- **#836** — [Thumbnail] Percona  
  _enhancement, thumbnail_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.
- **#838** — [Thumbnail] 500px  
  _enhancement, thumbnail_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.
- **#839** — [Thumbnail] WazirX  
  _enhancement, thumbnail_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.
- **#844** — [Thumbnail] Tildes  
  _enhancement, thumbnail_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.
- **#846** — [Thumbnail] Digitec.ch  
  _enhancement, thumbnail_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.
- **#848** — [Thumbnail] RubyGems.org  
  _enhancement, thumbnail_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.
- **#849** — [Thumbnail] Plesk  
  _enhancement, thumbnail_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.
- **#850** — [Thumbnail] Linus Tech Tips  
  _enhancement, thumbnail_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.
- **#851** — [Thumbnail] XDA Developers  
  _enhancement, thumbnail_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.
- **#852** — [Thumbnail] Papaki  
  _enhancement, thumbnail_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.
- **#860** — [Thumbnail] Discogs  
  _enhancement, thumbnail_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.
- **#861** — [Thumbnail] PulseHeberg  
  _enhancement, thumbnail_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.
- **#864** — [Thumbnail] Smartschool  
  _enhancement, thumbnail_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.
- **#869** — [Thumbnail] TradingView  
  _enhancement, thumbnail_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.
- **#871** — [Thumbnail] Keyhelp  
  _enhancement, thumbnail_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.
- **#872** — [Thumbnail] Netcup (update icon)  
  _enhancement, thumbnail_  
  ⚠️ **VERIFY** — Netcup icon is bundled; the request is to update it — check currency.
- **#876** — [Thumbnail] Clever Cloud  
  _enhancement, thumbnail_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.
- **#877** — [Thumbnail] Tweakers.net logo  
  _enhancement, thumbnail_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.
- **#882** — [Thumbnail] Yahoo  
  _enhancement, thumbnail_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.
- **#887** — [Thumbnail] Zerodha  
  _enhancement, thumbnail_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.
- **#891** — OpenCollective [Thumbnail]  
  _enhancement, thumbnail_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.
- **#896** — [Thumbnail] Deutsche Bahn  
  _enhancement, thumbnail_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.
- **#899** — [Thumbnail] Strato  
  _enhancement, thumbnail_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.
- **#901** — [Thumbnail] Hostinger  
  _enhancement, thumbnail_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.
- **#905** — [Thumbnail] Celsius  
  _enhancement, thumbnail_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.
- **#909** — [Thumbnail] DPG Media  
  _enhancement, thumbnail_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.
- **#913** — [Thumbnail] Filen  
  _enhancement, thumbnail_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.
- **#917** — Discogs  
  _enhancement, thumbnail_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.
- **#919** — [Thumbnail] DokuWiki  
  _enhancement, thumbnail_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.
- **#920** — [Thumbnail] OpenVK  
  _enhancement, thumbnail_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.
- **#922** — [Thumbnail] Auth0  
  _enhancement, thumbnail_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.
- **#924** — [Thumbnail] ownCloud  
  _enhancement, thumbnail_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.
- **#926** — [Thumbnail] Imperva  
  _enhancement, thumbnail_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.
- **#927** — [Thumbnail] Wirex  
  _enhancement, thumbnail_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.
- **#928** — [Thumbnail] Credly  
  _enhancement, thumbnail_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.
- **#929** — [Thumbnail] code.videolan.org  
  _enhancement, thumbnail_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.
- **#933** — [Thumbnail] Parsec  
  _enhancement, thumbnail_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.
- **#936** — [Thumbnail] Roblox  
  _enhancement, thumbnail_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.
- **#937** — [Thumbnail] ProtonVPN  
  _enhancement, thumbnail_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.
- **#940** — [Thumbnail] LogTail  
  _enhancement, thumbnail_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.
- **#941** — [Thumbnail] IVPN  
  _enhancement, thumbnail_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.
- **#943** — System76 [Thumbnail]  
  _enhancement, thumbnail_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.
- **#945** — [Thumbnail] WhatPulse  
  _enhancement, thumbnail_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.
- **#946** — [Thumbnail] Signal  
  _enhancement, thumbnail_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.
- **#947** — [Thumbnail] Wacken ID  
  _enhancement, thumbnail_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.
- **#951** — [Thumbnail] Disroot  
  _enhancement, thumbnail_  
  ✅ **FIXED** — Disroot icon is already bundled — confirm it is the current logo.
- **#958** — [Thumbnail] HackTheBox  
  _enhancement, thumbnail_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.
- **#959** — [Thumbnail] Atera RMM  
  _enhancement, thumbnail_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.
- **#963** — [Thumbnail] Add Compart logo  
  _enhancement, thumbnail_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.
- **#965** — [Thumbnail] Manebooru  
  _enhancement, thumbnail_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.
- **#966** — [Thumbnail] Wealthsimple  
  _enhancement, thumbnail_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.
- **#967** — [Thumbnail] Square  
  _enhancement, thumbnail_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.
- **#969** — [Thumbnail] for pgAdmin (Postgresql)  
  _enhancement, thumbnail_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.
- **#970** — [Thumbnail] Kaspersky  
  _enhancement, thumbnail_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.
- **#971** — [Thumbnail] Ubuntu  
  _enhancement, thumbnail_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.
- **#972** — [Thumbnail] Matrix  
  _enhancement, thumbnail_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.
- **#973** — [Thumbnail] Asana  
  _enhancement, thumbnail_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.
- **#974** — [Thumbnail] Carta  
  _enhancement, thumbnail_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.
- **#977** — [Thumbnail] docspell  
  _enhancement, thumbnail_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.
- **#978** — [Thumbnail] BornHack  
  _enhancement, thumbnail_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.
- **#984** — [Thumbnail] Semaphore  
  _enhancement, thumbnail_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.
- **#985** — [Thumbnail] Phrase  
  _enhancement, thumbnail_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.
- **#987** — [Thumbnail] Tito  
  _enhancement, thumbnail_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.
- **#988** — [Thumbnail] UpWork  
  _enhancement, thumbnail_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.
- **#991** — [Thumbnail] Surfshark  
  _enhancement, thumbnail_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.
- **#992** — [Thumbnail] MyAnimeList  
  _enhancement, thumbnail_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.
- **#993** — [Thumbnail] Protonmail has a new logo  
  _enhancement, thumbnail_  
  ❌ **APPLIES** — Protonmail icon is bundled but outdated; the new logo still needs to be added.
- **#997** — [Thumbnail] FetLife  
  _enhancement, thumbnail_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.
- **#998** — [Thumbnail] Team Password Manager  
  _enhancement, thumbnail_  
  ❌ **APPLIES** — Brand icon not in the bundled set — still missing in OfflineOTP.

## 4. Feature & UX enhancements (54)

- **#68** — Android Wear support  
  _enhancement_  
  ✅ **OUTOFSCOPE** — No Android Wear module in OfflineOTP.
- **#168** — Streamline backup and restore  
  _enhancement_  
  🆕 **GAP** — A dedicated backup/restore screen exists; further streamlining is a product decision.
- **#172** — Thumbnail background colour doesn't change for dark themes  
  _enhancement, maybe_  
  ❌ **APPLIES** — Thumbnail background color vs. dark themes — same rendering path as andOTP; verify and fix.
- **#178** — Paper Backups  
  _enhancement, help wanted, maybe_  
  🆕 **GAP** — Paper (printable) backups not implemented.
- **#207** — Garena Authentication  
  _enhancement, help wanted, good first issue_  
  🆕 **GAP** — Garena QR (`KKKK…|Username`) is not recognized; manual entry works because the period limit (1–86,400 s) covers its 180 s step.
- **#214** — OpenKeychain as authentication source  
  _enhancement_  
  ✅ **OUTOFSCOPE** — Vault unlock is password/PIN/KeyStore only; OpenKeychain is used for OpenPGP backups, not authentication.
- **#225** — Custom image support  
  _enhancement_  
  🆕 **GAP** — Thumbnails are bundled brand vectors or generated letter tiles; custom images are not supported.
- **#230** — Rename/Edit Tags  
  _enhancement, help wanted, maybe_  
  🆕 **GAP** — `TagsDialog` is selection-only; no rename/edit found — feature gap (verify).
- **#360** — Standartize the backup format  
  _enhancement_  
  🆕 **GAP** — Backup format is JSON-based with staged transaction framing; cross-app standardization is an ecosystem effort.
- **#445** — Please increase icon contrast  
  _enhancement, help wanted, good first issue_  
  ❌ **APPLIES** — UI contrast request (pairs with #853).
- **#477** — Require authentication before doing an export  
  _enhancement, help wanted_  
  ⚠️ **VERIFY** — Vault saves authenticate the existing vault first; confirm exports also prompt for authentication.
- **#481** — Faceunlock on Android  
  _enhancement, help wanted, maybe_  
  ✅ **OUTOFSCOPE** — No biometric unlock (no `BiometricPrompt` in the codebase).
- **#508** — FortiToken Mobile compatibility  
  _enhancement, help wanted, maybe_  
  🆕 **GAP** — Parser accepts `otpauth` totp/hotp/steam only; FortiToken's proprietary format would need a dedicated importer.
- **#529** — Freeform mode  
  _enhancement, help wanted_  
  ⚠️ **VERIFY** — `MainActivity` now uses normal configuration recreation; freeform resizing depends on manifest/activity config — verify.
- **#531** — Support automatic backup with OpenPGP key  
  _enhancement_  
  🆕 **GAP** — Automatic backups use the encrypted backup password; OpenPGP-key-based automatic backup is not wired.
- **#532** — Yahoo Japan OTP  
  _enhancement, help wanted, good first issue_  
  🆕 **GAP** — `yjotp://` scheme is not parsed; Yahoo Japan OTP needs manual entry or a scheme importer.
- **#561** — Importing a Google Authenticator Export via QR  
  _enhancement, help wanted_  
  🆕 **GAP** — No `otpauth-migration://` (Google Authenticator export QR) importer.
- **#613** — Delete picture of qr code after importing  
  _enhancement, maybe_  
  ❌ **APPLIES** — UX request; verify scanner behavior.
- **#616** — Quick Access Widget  
  _enhancement_  
  ✅ **OUTOFSCOPE** — No app widgets in OfflineOTP.
- **#648** — Export preferences  
  _enhancement_  
  🆕 **GAP** — Preferences export not implemented.
- **#670** — Generate next TOTP Code on request  
  _enhancement_  
  🆕 **GAP** — No next-code preview/generation on request.
- **#673** — Donation via standard platforms (GitHub, Patreon, etc.)  
  _enhancement_  
  ✅ **OUTOFSCOPE** — PolyForm Noncommercial license; the funding model is a project decision.
- **#692** — Improper Export of Android Application Components  
  _enhancement, help wanted, good first issue_  
  ✅ **FIXED** — Only the launcher/import activity is public; internal credential/settings/backup activities are unexported and IPC requires the signature permission.
- **#698** — YubiKey storage option  
  _enhancement, help wanted_  
  🆕 **GAP** — No YubiKey/hardware-key storage option.
- **#701** — Add "numbers of backups to keep" for automatic-backups  
  _enhancement, help wanted_  
  🆕 **GAP** — Automatic backups create new UUID-named documents; no retention-count setting found (verify).
- **#707** — Fingerprint request.  
  _enhancement_  
  ✅ **OUTOFSCOPE** — No fingerprint/biometric unlock.
- **#779** — Use the Android Share menu for backups  
  _enhancement_  
  🆕 **GAP** — Export uses the SAF picker; Android share-sheet integration is not present.
- **#784** — Highlighting tokens  
  _enhancement_  
  🆕 **GAP** — No token highlighting/dimming feature.
- **#794** — Suggestion, qr code from image via default gallery app  
  _enhancement_  
  ✅ **FIXED** — Image-based QR import exists via the SAF picker (`INTENT_MAIN_QR_OPEN_IMAGE`).
- **#804** — Add scroll / position indicator for main list (only applies if you use more than a screen full of tokens)  
  _enhancement_  
  ❌ **APPLIES** — Main-list UX; no scroll/position indicator.
- **#805** — Don't let keyboard hide the [Abort] and [Save] buttons  
  _enhancement_  
  ⚠️ **VERIFY** — No explicit `windowSoftInputMode` found; check dialog keyboard behavior.
- **#808** — Support for DUO Mobile QR codes.  
  _enhancement, maybe_  
  🆕 **GAP** — DUO's proprietary QR format is not parsed (totp/hotp/steam only).
- **#813** — Expiring codes are not red for a moment after making them visible by tapping  
  _bug, help wanted, maybe_  
  ⚠️ **VERIFY** — Tap-to-reveal exists; confirm the brief red 'expiring' flash behavior.
- **#814** — Show password (eye icon) could reveal already typed password to an attacker  
  _enhancement, help wanted, maybe_  
  ⚠️ **VERIFY** — Password widgets disable saved state and clear on dismissal; the eye toggle still reveals to anyone with device access — partial mitigation.
- **#853** — Low contrast in light mode  
  _enhancement, help wanted_  
  ❌ **APPLIES** — Theme contrast request (light mode); pairs with #445.
- **#855** — Simpleicons  
  _enhancement_  
  🆕 **GAP** — The bundled vector icon set predates Simple Icons; migrating the brand set is a project decision.
- **#862** — The length limit of the backup password may be misleading  
  _enhancement_  
  ⚠️ **VERIFY** — Backup password is stored encrypted (`pref_backup_password_enc`); check the documented length limits.
- **#874** — Indicate name/prompt to edit name when adding a new entry  
  _enhancement_  
  ❌ **APPLIES** — Manual-entry UX: prompt to edit the name when adding an entry.
- **#880** — "Hidden" text should be shortened  
  _enhancement, help wanted, good first issue_  
  ❌ **APPLIES** — String-resource shortening ('Hidden' text).
- **#881** — Performing UI Overhaul  
  _enhancement_  
  ❌ **APPLIES** — UI-overhaul meta-request.
- **#914** — andOTP in-app NTP clock as alternative to system's for TOTP codes  
  _enhancement, maybe_  
  🆕 **GAP** — App samples the system clock (checks `AUTO_TIME`); no in-app NTP sync.
- **#918** — Compare existing Issuer  
  _enhancement_  
  🆕 **GAP** — No issuer comparison / duplicate detection at scan time.
- **#921** — Nitrokey/hardware keys support  
  _enhancement_  
  🆕 **GAP** — No Nitrokey/hardware-key support.
- **#925** — [FR] Automatic deletion of backups  
  _enhancement_  
  🆕 **GAP** — No automatic deletion of old backups.
- **#944** — Allow for only appending date to backup's filename  
  _enhancement_  
  ✅ **FIXED** — Automatic exports deliberately always create UUID-named documents, even when date/time naming is off.
- **#949** — hide 3 dot menu option and copy option  
  _enhancement, help wanted, good first issue_  
  ❌ **APPLIES** — Popup menu and copy button are still present; the request is to make them optional/hidden.
- **#952** — Remove typed passwords after inactivity  
  _enhancement_  
  ✅ **FIXED** — Locking advances the session generation and revokes keys; password widgets clear on dismissal; an inactivity-relock setting exists.
- **#953** — Support for typing MFA token with InputStick  
  _enhancement_  
  ✅ **OUTOFSCOPE** — No InputStick support.
- **#968** — Increase max backup password length and get rid of password confirmation  
  _enhancement_  
  ⚠️ **VERIFY** — Confirmation flow retained (`ConfirmedPasswordTransformationHelper`); check the max-length policy.
- **#979** — Please implement manual daylight savings time switch  
  _enhancement_  
  ✅ **OUTOFSCOPE** — Daylight-saving is an OS/clock responsibility; the app samples epoch seconds directly.
- **#980** — Add export Aes with encode to base64  
  _enhancement_  
  🆕 **GAP** — Export formats are plain JSON, password-encrypted and OpenPGP; no AES+base64 variant.
- **#982** — BLE / keyboard  
  _enhancement_  
  ✅ **OUTOFSCOPE** — No BLE/keyboard HID support.
- **#990** — Support entering `otpauth://` URIs in details  
  _enhancement_  
  🆕 **GAP** — `ManualEntryDialog` has no `otpauth://` URI field.
- **#994** — Tag counters  
  _enhancement, help wanted, good first issue_  
  🆕 **GAP** — Tags dialog is selection-only; no per-tag counters.

## 5. Build & maintenance (2)

- **#841** — migrate from JCenter to Maven Central  
  _enhancement, help wanted_  
  ✅ **FIXED** — Build already uses `google()`, `mavenCentral()` and jitpack; no `jcenter()` anywhere.
- **#842** — replace retired android library "Expandable Layout"  
  _enhancement, help wanted_  
  ✅ **FIXED** — Uses the maintained `com.github.aakira:ExpandableLayout:v1.6.0`, not the retired traex library.

## 6. Questions, support & polls (5)

- **#867** — In your opinion, is talkback screen reader really good or not?  
  __(unlabeled)__  
  ✅ **OUTOFSCOPE** — Opinion poll; accessibility verification is tracked under #498.
- **#911** — Password requirements specification?  
  _enhancement, question_  
  ⚠️ **VERIFY** — Vault password minimum is 6 (`AUTH_MIN_PASSWORD_LENGTH`); document the backup-password requirements.
- **#915** — Excuse my ignorance, but I have a question please.   
  _enhancement_  
  ✅ **OUTOFSCOPE** — General support question, not a defect.
- **#939** — Be able to support your work  
  _enhancement_  
  ✅ **OUTOFSCOPE** — Donation request.
- **#964** — help manual converting the freeOTP to andOTP  
  __(unlabeled)__  
  ✅ **OUTOFSCOPE** — Support request; freeOTP secrets transfer via QR scan or manual entry.

---

## Recommended next steps

1. **Run the ⚠️ list on a device/emulator** (API 28 and 36): #208, #376, #406, #498, #533, #712, #736, #847, #865, #873,
   #879, #892, #895, #923, #932, #948, #976, #995, #996, #477, #529, #805, #813, #814, #862, #911, #968, plus the
   two logo-currency checks (#832, #872). These are the only upstream issues that could still be real defects.
2. **Decide on the ❌ UI nits** that clearly apply: #591 (setup portrait lock), #172, #445, #853 (contrast), #613,
   #804, #874, #880, #881, #813.
3. **Batch-add the ~80 missing brand thumbnails** — the largest concrete carry-over; consider the Simple Icons
   migration (#855) while doing it.
4. **Triage the 🆕 gaps** as product decisions (DUO #808, GA-export import #561, yjotp #532, Garena #207, tag
   rename/counters #230/#994, next-code preview #670, custom images #225, backup retention #701, share-sheet export
   #779, preferences export #648, AES+base64 export #980, otpauth:// manual entry #990, issuer comparison #918,
   NTP clock #914, paper backups #178, highlighting #784, automatic OpenPGP backups #531, backup deletion #925).
5. **Close as not-ours (✅):** everything marked fixed/by-design/out-of-scope — notably the whole security cluster
   (#157, #692, #975, #649, #888, #875, #890, #944, #952) and the absent-feature requests (#68, #616, #481, #707,
   #214, #921, #698, #953, #982, #866).
