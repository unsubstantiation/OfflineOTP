"""Source-level coverage guards for the legacy reports summarized in issue #39.

These assertions complement executable JVM and instrumentation tests; they do not replace vendor,
TalkBack, cloud-provider, or third-party OpenPGP application testing.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / "app/src/main/kotlin/org/shadowice/flocke/andotp"
TEST = ROOT / "app/src/test/kotlin/org/shadowice/flocke/andotp"
ANDROID_TEST = ROOT / "app/src/androidTest/kotlin/org/shadowice/flocke/andotp"


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


class Issue39RegressionCoverageTest(unittest.TestCase):
    def test_authentication_cancellation_stays_fail_closed(self):
        main = read(APP / "Activities/MainActivity.kt")
        policy = read(TEST / "Utilities/AuthenticationResultPolicyTest.kt")
        self.assertIn("AuthenticationResultPolicy.accepts", main)
        self.assertIn("VaultSession.lock(SessionLockReason.AUTHENTICATION)", main)
        self.assertIn("finishAndRemoveTask()", main)
        self.assertIn("canceledAuthenticationCannotUnlock", policy)
        self.assertIn("unrelatedOrStaleResultsCannotUnlock", policy)

    def test_stale_card_callbacks_guard_indexes_before_access(self):
        adapter = read(APP / "View/EntriesCardAdapter.kt")
        guards = read(TEST / "View/EntryIndexGuardTest.kt")
        hide = adapter[adapter.index("private fun hideEntry("):adapter.index("private fun showActionDialog(")]
        self.assertLess(hide.index("EntryIndexGuard.isValid"), hide.index("entries.getEntry(realIndex)"))
        update = adapter[adapter.index("private fun updateLastUsedAndFrequency("):adapter.index("/** Swaps two entries")]
        self.assertLess(update.index("EntryIndexGuard.isValid"), update.index("ownedEntries[realIndex]"))
        self.assertIn("acceptsOnlyIndexesInsideTheCurrentList", guards)

    def test_talkback_speed_dial_scope_focus_and_labels_are_pinned(self):
        main = read(APP / "Activities/MainActivity.kt")
        device_test = read(ANDROID_TEST / "MainActivityAccessibilityDeviceTest.kt")
        self.assertIn("performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS", main)
        self.assertIn("IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS", main)
        self.assertIn("R.string.button_close_menu", main)
        holder = read(APP / "View/EntryViewHolder.kt")
        self.assertIn("button_card_copy_format", holder)
        self.assertIn("button_card_options_format", holder)
        self.assertIn("openingAndClosingSpeedDialUpdatesAccessibilityScopeAndLabels", device_test)
        self.assertIn("TalkBack traversal itself still requires a manual", device_test)

    def test_encrypted_backup_has_host_crypto_and_fake_saf_round_trips(self):
        crypto = read(TEST / "Utilities/EncryptionHelperTest.kt")
        sandbox = read(ROOT / "scripts/kotlin-sandbox/Checks.kt")
        self.assertIn("aesGcmUsesTheNistVector", crypto)
        self.assertIn("encryptedBackupFrameRoundTripsCurrentAndLegacyFormats", crypto)
        self.assertIn("encrypted backups round-trip through the SAF writer", sandbox)
        self.assertIn("wrong passwords/tampering", sandbox)

    def test_backup_location_uses_persisted_content_tree_and_provider_failures_are_handled(self):
        settings = read(APP / "Activities/SettingsActivity.kt")
        backup = read(APP / "Activities/BackupActivity.kt")
        helper = read(APP / "Utilities/BackupHelper.kt")
        self.assertIn("Intent.ACTION_OPEN_DOCUMENT_TREE", settings)
        self.assertIn("takePersistableUriPermission", settings)
        self.assertIn('"content" == treeUri.getScheme()', settings)
        self.assertIn("DocumentFile.fromTreeUri", helper)
        self.assertIn("createFile(backupMimeType(type)", helper)
        self.assertIn("catch (error: ActivityNotFoundException)", settings)
        self.assertIn("catch (error: ActivityNotFoundException)", backup)
        self.assertIn("catch (error: SecurityException)", backup)
        sandbox = read(ROOT / "scripts/kotlin-sandbox/Checks.kt")
        self.assertIn("encrypted backups round-trip through the SAF writer", sandbox)
        self.assertIn("automatic backups create unique documents under a selected SAF tree", sandbox)

    def test_relock_timeout_is_deadline_based_and_covers_pause(self):
        timer = read(APP / "Utilities/ScreenTimerController.kt")
        tests = read(TEST / "Utilities/ScreenTimerControllerTest.kt")
        main = read(APP / "Activities/MainActivity.kt")
        self.assertIn("suspendForeground", timer)
        self.assertIn("inactivityRelockExpired", main)
        self.assertIn("backgroundTimeCountsTowardsTheInactivityDeadline", tests)
        self.assertIn("elapsedRealtime", timer)
        self.assertIn("pause", tests)

    def test_backup_picker_result_is_deferred_until_vault_is_unlocked(self):
        main = read(APP / "Activities/MainActivity.kt")
        backup = read(APP / "Activities/BackupActivity.kt")
        self.assertIn("pendingBackupActionCode = actionCode", main)
        self.assertIn('uri.getScheme() != "content"', main)
        self.assertIn("VaultSession.getKey() == null) return", main)
        self.assertIn("pendingResumeRequestCode", backup)
        self.assertIn("forwardDeferredAction(activeCode, activeUri, activeActionReplace)", backup)
        self.assertIn("tryResumePendingPickerAction()", backup)

    def test_keystore_alias_loss_is_instrumented_without_regeneration(self):
        device_test = read(ANDROID_TEST / "ApplicationTest.kt")
        helper = read(APP / "Utilities/KeyStoreHelper.kt")
        self.assertIn("androidKeyStoreWrappingKeyCanBeLoadedAndMissingAliasesFailClosed", device_test)
        self.assertIn("loadAsymmetricKeyPair(context, alias)", device_test)
        self.assertIn("if (store.containsAlias(alias)) store.deleteEntry(alias)", device_test)
        self.assertIn("fun loadAsymmetricKeyPair", helper)

    def test_reported_qr_image_is_decoded_with_real_device_libraries(self):
        device_test = read(ANDROID_TEST / "QrCodeImportDeviceTest.kt")
        self.assertIn("otpauth://totp/CCP:123456?secret=3KJPWL4V4MSKDPD6", device_test)
        self.assertIn("QRCodeWriter()", device_test)
        self.assertIn("ScanQRCodeFromFile.scanQRImage", device_test)
        self.assertIn("malformedImageIsRejectedWithoutCrashing", device_test)

    def test_intro_orientation_and_settings_tap_have_device_regressions(self):
        intro_test = read(ANDROID_TEST / "IntroScreenLaunchTest.kt")
        settings_test = read(ANDROID_TEST / "ActivityReachabilityDeviceTest.kt")
        self.assertIn("introScreenDoesNotForceAnOrientation", intro_test)
        self.assertIn("ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED", intro_test)
        self.assertIn("performItemClick", settings_test)
        self.assertIn("android.R.id.list", settings_test)
        self.assertIn("touching the Settings option finished the screen", settings_test)

    def test_entry_tap_policy_and_plaintext_restore_failures_are_covered(self):
        taps = read(TEST / "View/TapSequenceControllerTest.kt")
        decoder = read(TEST / "Utilities/PlainTextBackupDecoderTest.kt")
        task = read(APP / "Tasks/PlainTextRestoreTask.kt")
        backup = read(APP / "Activities/BackupActivity.kt")
        self.assertIn("singleClickRunsAfterTheDoubleTapWindow", taps)
        self.assertIn("twoClicksOnTheSameTargetCancelThePendingSingle", taps)
        self.assertIn("rejectsMalformedUtf8InsteadOfReplacingSecretBytes", decoder)
        self.assertIn("PlainTextBackupDecoder.decode(bytes)", task)
        self.assertIn("backup_toast_plaintext_invalid", task)
        self.assertIn("handleRestoreTaskResult", backup)
        task_host = read(APP / "Activities/BackgroundTaskActivity.kt")
        outcome = task_host[task_host.index("private fun handleTaskOutcome"):task_host.index("private fun checkBackgroundTask")]
        self.assertLess(outcome.index("setupUiForTaskState(false)"), outcome.index("onTaskResult"),
                        "restore progress must stop before the failure result is displayed")

    def test_openpgp_readiness_and_empty_output_fail_closed(self):
        tests = read(TEST / "Utilities/OpenPgpPolicyTest.kt")
        policy = read(APP / "Utilities/OpenPgpPolicy.kt")
        backup = read(APP / "Activities/BackupActivity.kt")
        self.assertIn("providerMustBeConfiguredAndBoundBeforeEitherActionCanRun", tests)
        self.assertIn("emptyProviderOutputIsNotAValidEncryptedBackup", tests)
        self.assertIn("OpenPgpOutputPolicy.hasContent(encryptedBackup)", backup)
        self.assertIn("backup_toast_openpgp_empty", backup)
        self.assertIn("isNullOrBlank", policy)

    def test_back_key_and_inactivity_relock_have_device_regressions(self):
        flow = read(ANDROID_TEST / "AuthenticationFlowDeviceTest.kt")
        # #208: a cancelled prompt must fail closed; the positive case must still unlock.
        self.assertIn("aCancelledUnlockPromptCannotRevealTheVault", flow)
        self.assertIn("prompt!!.onBackPressed()", flow)
        self.assertIn("Lifecycle.State.DESTROYED", flow)
        self.assertIn("theConfiguredPinStillUnlocksTheVault", flow)
        # #712: background time must count towards the elapsed-realtime deadline.
        self.assertIn("backgroundTimeCountsTowardsTheInactivityRelock", flow)
        self.assertIn("moveToState(Lifecycle.State.CREATED)", flow)
        self.assertIn("SystemClock.sleep(1_600)", flow)
        self.assertIn("assertNull(\"the idle-expired session still held a vault key\"", flow)

    def test_obscured_touch_rejection_has_a_device_regression(self):
        device_test = read(ANDROID_TEST / "ObscuredTouchDeviceTest.kt")
        base = read(APP / "Activities/BaseActivity.kt")
        self.assertIn("MotionEvent.FLAG_WINDOW_IS_OBSCURED", base)
        self.assertIn("MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED", base)
        self.assertIn("aFullyObscuredTapIsRejected", device_test)
        self.assertIn("aPartiallyObscuredTapIsRejected", device_test)
        self.assertIn("aNormalTapIsDeliveredAndClicks", device_test)
        self.assertIn("InputDevice.SOURCE_TOUCHSCREEN", device_test)
        # The flag-word overload is the only public way to build an obscured MotionEvent.
        self.assertIn("MotionEvent.obtain(now, now, action, 1, properties, coordinates", device_test)

    def test_encrypted_and_plaintext_restore_have_device_regressions(self):
        restore = read(ANDROID_TEST / "BackupRestoreDeviceTest.kt")
        for needle in (
            "currentEncryptedBackupIsRestored",
            "legacyEncryptedBackupIsRestored",
            "aWrongPasswordIsRejected",
            "aTruncatedEncryptedBackupIsRejected",
            "aMissingDocumentFailsWithoutCrashing",
            "aLegacyPlaintextBackupIsRestored",
            "malformedPlaintextIsRejected",
        ):
            self.assertIn(needle, restore)
        self.assertIn("EncryptedRestoreTask(", restore)
        self.assertIn("PlainTextRestoreTask(", restore)
        self.assertIn("backup_toast_import_decryption_failed", restore)
        self.assertIn("backup_toast_plaintext_invalid", restore)

    def test_screen_off_teardown_policy_is_shared_with_a_host_test(self):
        policy = read(APP / "Utilities/BackupScreenOffPolicy.kt")
        backup = read(APP / "Activities/BackupActivity.kt")
        host_test = read(TEST / "Utilities/BackupScreenOffPolicyTest.kt")
        self.assertIn("BackupScreenOffPolicy.shouldFinish(", backup)
        self.assertIn("pickerPending", policy)
        self.assertIn("everyPendingOperationBlocksTeardown", host_test)
        self.assertIn("aRunningTaskKeepsTheScreenAliveEvenWhenNothingElseIsPending", host_test)



if __name__ == "__main__":
    unittest.main()
