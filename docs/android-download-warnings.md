# Investigating Android download warnings

A browser or antivirus warning is a reason to stop and investigate, not proof that a file is either
malicious or clean. Do not tell people to disable protections or install the blocked file. Preserve
the warning and review the exact artifact before distributing it further.

## Identify the detection

1. Record the release or workflow URL, the exact filename, the warning text and a screenshot with
   personal information removed. A warning about an **uncommon download** is different from an
   explicit **malware detection**. A ZIP and the APK inside it are different files with different
   hashes; determine which one was flagged.
2. On Windows, check **Windows Security → Virus & threat protection → Protection history** for a
   detection, its threat name, affected file/path and time. If another antivirus is active, check
   its quarantine/history instead. Firefox's Downloads panel may also provide details. Firefox
   uses Google Safe Browsing for potentially malicious download warnings; a Firefox warning alone
   does not establish that Defender has detected the file.
3. If an accessible copy already exists, record its SHA-256 without running or installing it. For
   example, in PowerShell, use `Get-FileHash -Algorithm SHA256 -LiteralPath 'C:\path\to\file.zip'`
   with the actual path. Do not restore a quarantined file or bypass a block merely to obtain it.

## Compare the artifact and build

- For a release asset, compare the hash with the corresponding asset's `digest` in the GitHub
  Releases API and any published checksum. For an Actions artifact ZIP, the digest from the workflow
  run's `artifacts` API refers to the **ZIP**, not the APK inside. Verify the contained APK against
  its `SHA256SUMS.txt` separately if it is safely available.
- The Android workflow now includes the checked-out commit, workflow run URL, decoded packaged
  manifest and signing-certificate details with its debug APK. Earlier artifacts do not necessarily
  contain this information. The workflow verifies the APK signature and checks a limited set of
  packaged-manifest invariants, including a permission allowlist that excludes `INTERNET`.
- With trusted Android SDK tools, `apksigner verify --print-certs file.apk` checks the signature and
  prints its certificate; `apkanalyzer manifest print file.apk` displays the packaged manifest.
  These tools inspect a file without installing it. Compare the certificate fingerprint and commit
  with a trusted record; a valid signature by itself does not identify a trusted publisher.
- A checksum next to a download, GitHub's recorded digest, and self-reported build metadata help
  identify bytes and trace an upload. They are not independent proof of safety or an attestation of
  the entire build. A compromised account or build input could affect both a file and its metadata.
  No `INTERNET` permission does not rule out malicious behavior or data leaving through other apps.
  Review the relevant source, dependencies, build inputs and scanner results as well.

The current Android workflow produces a **debuggable** `.dev` APK signed with a temporary debug
key. It is for testing, not a production release. Future runs can have different signing keys, so
an installed debug build may have to be uninstalled to change builds; uninstalling can erase its
vault. Export a portable backup and verify a restore before switching. A production APK needs a
non-debuggable release build and a securely managed, stable release signing key; Android will not
update an existing installation if its signing certificate differs. Android signing does not
promise a favorable antivirus or browser verdict. See the
[Android app signing guide](https://developer.android.com/studio/publish/app-signing).

## Request a vendor review

If Microsoft Defender is the detector, submit the exact affected file or hash to
[Microsoft Security Intelligence](https://www.microsoft.com/en-us/wdsi/filesubmission) for analysis,
including the threat name, hash, release URL and build provenance. Describe it as a **suspected**
false positive only after investigating; keep the submission ID and outcome. If a different product
flagged it, use that vendor's submission process. For a Firefox/Safe Browsing warning, use the
[Firefox download-protection guidance](https://support.mozilla.org/en-US/kb/where-find-and-manage-downloaded-files-firefox)
and the hosting provider's review process. The owner of a separately controlled download website
can also use [Google Search Console's security-issues review](https://support.google.com/webmasters/answer/9044101).

Only submit the public build under investigation, never an OTP vault, exported backup, signing key,
account credentials or private logs. Scanner results can change, and even no detections do not prove
a file is safe. Do not republish or declare the warning resolved until the exact artifact and vendor
verdict have been investigated.

## Investigation: v2026-10-29 draft ZIP

This record applies only to the draft `OfflineOTP-android-debug.zip` with SHA-256
`b3a675fb7197d059969acfd7d8d91cdc8185eea47916ddd2be4e119cbf554aa4`, not later builds.
The user reported a Firefox malware warning. GitHub's recorded digest for this draft asset matched
its Android Actions artifact from [run 40967999372](https://github.com/unsubstantiation/OfflineOTP/actions/runs/40967999372),
built from commit `0086701f39f1cae1ad302b2902fe91ce7b2b4fa1`.

A [read-only investigation run](https://github.com/unsubstantiation/OfflineOTP/actions/runs/40993381448)
retrieved that Actions artifact from GitHub, checked its ZIP digest against the draft asset's digest,
and inspected it without installing or executing the app. The contained APK's SHA-256 was
`bd1682e2f0e369fb421e04aa2b5b78ca567919c003f76da19143b7687c39f78f`. Its signature verified,
and the signer certificate's SHA-256 was
`3b9bdd459954654e962a174b2ca086b67267f49c328ecf3c15cbae741a6c4e40`; these matched the metadata
bundled with the same artifact, not an independent trusted publisher record. The packaged manifest
identified the app as debuggable, disabled platform backup, and declared no `INTERNET` permission.

ClamAV 1.5.4, with signature database 28168 dated 2026-10-29, scanned the ZIP and APK with **no
reported detections**. The engine also detected the standard harmless EICAR test pattern. No file was
submitted to an external scanning service. This single scan and the manifest checks cannot prove the
APK is safe or determine why Firefox/Google Safe Browsing flagged it. The warning remains unresolved;
keep the release in draft pending further review.
