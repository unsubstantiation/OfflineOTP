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
