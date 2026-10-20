#!/usr/bin/env bash
#
# Non-authoritative sandbox check for converted Kotlin sources.
#
# Compiles the listed Kotlin sources plus the platform stubs in ./stubs against a locally installed
# Kotlin compiler, then runs ./Checks.kt on a desktop JVM. It exists because an environment without
# the Android SDK, AndroidX artifacts and their dependency graph cannot run the Gradle unit-test,
# lint or instrumentation gates. It is NOT that gate: it says nothing about Android runtime
# behaviour, resources, manifest, packaging, lint or the other app sources. Read README.md first.
set -euo pipefail

SANDBOX="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SANDBOX/../.." && pwd)"
KOTLIN_APP="$ROOT/app/src/main/kotlin/org/shadowice/flocke/andotp"
OUT="${KOTLIN_SANDBOX_OUT:-$ROOT/reports/kotlin-sandbox/out}"

KOTLINC="${KOTLINC:-$(command -v kotlinc-jvm || command -v kotlinc || true)}"
JAVA_BIN="${JAVA_BIN:-$(command -v java || true)}"
if [[ -z "$KOTLINC" || -z "$JAVA_BIN" ]]; then
    echo "kotlinc and java are required; set KOTLINC=/path/to/kotlinc-jvm and JAVA_BIN=/path/to/java" >&2
    exit 2
fi

# The kotlin-stdlib jar sits next to the compiler in a normal distribution.
STDLIB="${KOTLIN_STDLIB:-}"
if [[ -z "$STDLIB" ]]; then
    KOTLINC_REAL="$(readlink -f "$KOTLINC")"
    for candidate in "$(dirname "$KOTLINC_REAL")/../lib/kotlin-stdlib.jar" "$(dirname "$KOTLINC")/../lib/kotlin-stdlib.jar"; do
        if [[ -f "$candidate" ]]; then
            STDLIB="$(cd "$(dirname "$candidate")" && pwd)/$(basename "$candidate")"
            break
        fi
    done
fi
if [[ -z "$STDLIB" || ! -f "$STDLIB" ]]; then
    echo "kotlin-stdlib.jar not found; set KOTLIN_STDLIB=/path/to/kotlin-stdlib.jar" >&2
    exit 2
fi

# The app sources covered by this check. Every entry is compiled and its JVM-visible shapes are
# asserted in Checks.kt. The fdroid flavor's AboutFragment is compiled as the representative
# flavor of the about screens; the play flavor differs only by having no extras and is pinned by
# the Python source guards instead.
APP_SOURCES=(
    "$KOTLIN_APP/Preferences/NumberPickerPreference.kt"
    "$KOTLIN_APP/Preferences/PasswordEncryptedPreference.kt"
    "$KOTLIN_APP/Dialogs/HideableDialog.kt"
    "$KOTLIN_APP/Dialogs/PasswordEntryDialog.kt"
    "$KOTLIN_APP/Dialogs/TagsDialog.kt"
    "$KOTLIN_APP/View/TagsAdapter.kt"
    "$KOTLIN_APP/View/EntryActionDialogs.kt"
    "$KOTLIN_APP/View/EntryActionTarget.kt"
    "$KOTLIN_APP/View/EntryListPresentation.kt"
    "$KOTLIN_APP/View/EntryViewHolder.kt"
    "$KOTLIN_APP/View/EntriesCardAdapter.kt"
    "$KOTLIN_APP/Dialogs/ManualEntryDialog.kt"
    "$KOTLIN_APP/Preferences/CredentialsPreference.kt"
    "$KOTLIN_APP/View/ThumbnailSelectionAdapter.kt"
    "$KOTLIN_APP/View/AutoFillable/AutoFillableTextInputEditText.kt"
    "$KOTLIN_APP/Utilities/EntryThumbnail.kt"
    "$KOTLIN_APP/Utilities/LetterBitmap.kt"
    "$KOTLIN_APP/Database/EntryJsonResult.kt"
    "$KOTLIN_APP/Database/EntryJson.kt"
    "$KOTLIN_APP/Database/Entry.kt"
    "$KOTLIN_APP/Database/EntryList.kt"
    "$KOTLIN_APP/Utilities/DatabaseHelper.kt"
    "$KOTLIN_APP/Utilities/EncryptionHelper.kt"
    "$KOTLIN_APP/Utilities/KeyStoreHelper.kt"
    "$KOTLIN_APP/Utilities/Settings.kt"
    "$KOTLIN_APP/Utilities/LocaleContextHelper.kt"
    "$KOTLIN_APP/Utilities/Constants.kt"
    "$KOTLIN_APP/Utilities/FileHelper.kt"
    "$KOTLIN_APP/Utilities/StorageAccessHelper.kt"
    "$KOTLIN_APP/Utilities/BackupHelper.kt"
    "$KOTLIN_APP/Utilities/NotificationHelper.kt"
    "$KOTLIN_APP/Utilities/ScanQRCodeFromFile.kt"
    "$KOTLIN_APP/Utilities/InputPolicy.kt"
    "$KOTLIN_APP/Utilities/AuthenticationResultPolicy.kt"
    "$KOTLIN_APP/Utilities/OpenPgpRecipientIds.kt"
    "$KOTLIN_APP/Utilities/OpenPgpPolicy.kt"
    "$KOTLIN_APP/Utilities/PlainTextBackupDecoder.kt"
    "$KOTLIN_APP/Utilities/OtpParseResult.kt"
    "$KOTLIN_APP/Utilities/OtpUriParser.kt"
    "$KOTLIN_APP/Utilities/SessionContracts.kt"
    "$KOTLIN_APP/Utilities/VaultSession.kt"
    "$KOTLIN_APP/Utilities/VaultContracts.kt"
    "$KOTLIN_APP/Tasks/TaskContracts.kt"
    "$KOTLIN_APP/Tasks/AuthenticationOutcome.kt"
    "$KOTLIN_APP/Tasks/UiBasedBackgroundTask.kt"
    "$KOTLIN_APP/Tasks/BackupTaskResult.kt"
    "$KOTLIN_APP/Tasks/AuthenticationTask.kt"
    "$KOTLIN_APP/Tasks/GenericBackupTask.kt"
    "$KOTLIN_APP/Tasks/GenericRestoreTask.kt"
    "$KOTLIN_APP/Tasks/PGPBackupTask.kt"
    "$KOTLIN_APP/Tasks/PGPRestoreTask.kt"
    "$KOTLIN_APP/Tasks/EncryptedBackupTask.kt"
    "$KOTLIN_APP/Tasks/EncryptedRestoreTask.kt"
    "$KOTLIN_APP/Tasks/PlainTextRestoreTask.kt"
    "$KOTLIN_APP/Tasks/ChangeEncryptionTask.kt"
    "$KOTLIN_APP/Tasks/ChangeCredentialsTask.kt"
    "$KOTLIN_APP/Tasks/FinishIntroTask.kt"
    "$KOTLIN_APP/Utilities/Tools.kt"
    "$KOTLIN_APP/Utilities/ScreenTimerController.kt"
    "$KOTLIN_APP/Utilities/TokenCalculator.kt"
    "$KOTLIN_APP/Utilities/BoundedInputReader.kt"
    "$KOTLIN_APP/Utilities/BoundedOutput.kt"
    "$KOTLIN_APP/Utilities/EncryptedBackupHeader.kt"
    "$KOTLIN_APP/Utilities/EditorActionHelper.kt"
    "$KOTLIN_APP/Utilities/HandlerTimerScheduler.kt"
    "$KOTLIN_APP/Utilities/SecretKeyWrapper.kt"
    "$KOTLIN_APP/Utilities/DimensionConverter.kt"
    "$KOTLIN_APP/View/SimpleDoubleClickListener.kt"
    "$KOTLIN_APP/View/TapSequenceController.kt"
    "$KOTLIN_APP/View/EntryIndexGuard.kt"
    "$KOTLIN_APP/View/ItemTouchHelper/ItemTouchHelperAdapter.kt"
    "$KOTLIN_APP/View/ItemTouchHelper/ItemTouchHelperViewHolder.kt"
    "$KOTLIN_APP/View/ItemTouchHelper/SimpleItemTouchHelperCallback.kt"
    "$KOTLIN_APP/Utilities/UIHelper.kt"
    "$KOTLIN_APP/Utilities/ConfirmedPasswordTransformationHelper.kt"
    "$KOTLIN_APP/Utilities/EntryPersistence.kt"
    "$KOTLIN_APP/Utilities/VaultRestoreOperation.kt"
    "$KOTLIN_APP/Receivers/BackupBroadcastReceiver.kt"
    "$KOTLIN_APP/Receivers/EncryptedBackupBroadcastReceiver.kt"
    "$KOTLIN_APP/Tasks/EncryptedBackupWorker.kt"
    "$KOTLIN_APP/Utilities/BackupAgent.kt"
    "$KOTLIN_APP/Activities/ThemedActivity.kt"
    "$KOTLIN_APP/Activities/BaseActivity.kt"
    "$KOTLIN_APP/Activities/BackgroundTaskActivity.kt"
    "$KOTLIN_APP/Activities/AuthenticateActivity.kt"
    "$KOTLIN_APP/Activities/BackupActivity.kt"
    "$KOTLIN_APP/Activities/MainActivity.kt"
    "$KOTLIN_APP/Activities/IntroScreenActivity.kt"
    "$KOTLIN_APP/Activities/SettingsActivity.kt"
    "$KOTLIN_APP/Activities/PanicResponderActivity.kt"
    "$KOTLIN_APP/Activities/SecureCaptureActivity.kt"
    "$KOTLIN_APP/Activities/AboutActivity.kt"
    "$KOTLIN_APP/Fragments/BaseAboutFragment.kt"
    "$ROOT/app/src/fdroid/kotlin/org/shadowice/flocke/andotp/Fragments/AboutFragment.kt"
)

STUBS=("$SANDBOX"/stubs/android/*.kt
    "$SANDBOX"/stubs/androidx/recyclerview/widget/*.kt
    "$SANDBOX"/stubs/androidx/work/*.kt
    "$SANDBOX"/stubs/androidx/documentfile/provider/*.kt
    "$SANDBOX"/stubs/androidx/appcompat/app/*.kt
    "$SANDBOX"/stubs/androidx/appcompat/widget/*.kt
    "$SANDBOX"/stubs/androidx/fragment/app/*.kt
    "$SANDBOX"/stubs/androidx/viewpager/widget/*.kt
    "$SANDBOX"/stubs/androidx/appcompat/content/res/*.kt
    "$SANDBOX"/stubs/androidx/core/app/*.kt
    "$SANDBOX"/stubs/androidx/core/content/*.kt
    "$SANDBOX"/stubs/androidx/lifecycle/*.kt
    "$SANDBOX"/stubs/androidx/core/view/*.kt
    "$SANDBOX"/stubs/androidx/core/content/res/*.kt
    "$SANDBOX"/stubs/androidx/drawerlayout/widget/*.kt
    "$SANDBOX"/stubs/androidx/cardview/widget/*.kt
    "$SANDBOX"/stubs/com/google/zxing/integration/android/*.kt
    "$SANDBOX"/stubs/com/leinardi/android/speeddial/*.kt
    "$SANDBOX"/stubs/com/github/aakira/expandablelayout/*.kt
    "$SANDBOX"/stubs/me/zhanghai/android/materialprogressbar/*.kt
    "$SANDBOX"/stubs/com/heinrichreimersoftware/materialintro/app/*.kt
    "$SANDBOX"/stubs/com/heinrichreimersoftware/materialintro/slide/*.kt
    "$SANDBOX"/stubs/com/google/android/material/snackbar/*.kt
    "$SANDBOX"/stubs/com/google/android/material/switchmaterial/*.kt
    "$SANDBOX"/stubs/org/openintents/openpgp/*.kt
    "$SANDBOX"/stubs/org/openintents/openpgp/util/*.kt
    "$SANDBOX"/stubs/com/google/android/material/textfield/*.kt
    "$SANDBOX"/stubs/com/google/android/material/tabs/*.kt
    "$SANDBOX"/stubs/com/journeyapps/barcodescanner/*.kt
    "$SANDBOX"/stubs/com/google/zxing/*.kt
    "$SANDBOX"/stubs/com/google/zxing/common/*.kt
    "$SANDBOX"/stubs/com/google/zxing/qrcode/*.kt
    "$SANDBOX"/stubs/com/mikepenz/aboutlibraries/*.kt
    "$SANDBOX/stubs/org/shadowice/flocke/andotp/R.kt"
    "$SANDBOX"/stubs/org/json/*.kt
    "$SANDBOX"/stubs/org/apache/commons/codec/binary/*.kt
    "$SANDBOX"/stubs/org/apache/commons/codec/digest/*.kt
)

# No Java source is left in the app: the last two (Utilities/Constants and Utilities/FileHelper) are
# compiled from Kotlin like everything else, so kotlinc reads no Java and produces class files for
# every declaration it checks. FileHelper runs over the AtomicFile stand-in, which refuses every
# operation, so the file transaction fails loudly here instead of touching the working directory.

rm -rf "$OUT"
mkdir -p "$OUT"

echo "kotlinc: $KOTLINC"
echo "java:    $JAVA_BIN"
echo "stdlib:  $STDLIB"
echo "output:  $OUT"

"$KOTLINC" -jvm-target 1.8 -nowarn "${APP_SOURCES[@]}" "${STUBS[@]}" "$SANDBOX/Checks.kt" -d "$OUT"
"$JAVA_BIN" -cp "$OUT:$STDLIB" ChecksKt
