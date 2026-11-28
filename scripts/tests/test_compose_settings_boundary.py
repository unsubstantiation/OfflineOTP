"""Guard removal of the deprecated hidden settings UI, not a native acceptance test."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / 'app/src/main/kotlin/com/fairuniversal/offlineotp'


class ComposeSettingsBoundaryTest(unittest.TestCase):
    def test_settings_has_no_platform_preferences_or_fragment(self):
        activity = (APP / 'Activities/SettingsActivity.kt').read_text()
        for obsolete in ('android.preference.', 'SettingsFragment', 'getFragmentManager(',
                         'findPreference(', 'setOnPreferenceChangeListener(', 'OpenPgpKeyPreference'):
            self.assertNotIn(obsolete, activity)
        self.assertIn('SettingsOverviewComposeView(this, ::dispatchSettingsOverviewAction)', activity)
        changed = activity.split('override fun onSharedPreferenceChanged', 1)[1].split('override fun requiresVaultSession', 1)[0]
        self.assertIn('applyScreenshotPolicyForForeground()', changed)
        self.assertIn('cancelUniqueWork(EncryptedBackupBroadcastReceiver.UNIQUE_WORK_NAME)', changed)
        self.assertIn('publishSettingsOverview()', changed)
        self.assertIn('setAndroidBackupServiceEnabled(false)', changed)

    def test_security_and_provider_actions_still_use_guarded_compose_routes(self):
        activity = (APP / 'Activities/SettingsActivity.kt').read_text()
        for route in ('"authentication" -> showSharedAuthentication()',
                      '"encryption" -> showSharedEncryption()',
                      '"pgpSigningKey" -> selectSharedSigningKey()',
                      '"pgpProvider" -> showSharedPgpProvider()',
                      '"resetKeyStore" -> showSharedKeyStoreReset()'):
            self.assertIn(route, activity)
        for marker in ('credentialsTask?.let { it.setOutcomeCallback(null); it.cancel() }',
                       'if (!signingSelectionCurrent())', 'if (!owned()) return@save',
                       'settings!!.preferenceStore().edit()'):
            self.assertIn(marker, activity)

    def test_notification_permission_no_longer_needs_a_platform_fragment(self):
        helper = (APP / 'Utilities/NotificationHelper.kt').read_text()
        self.assertIn('fun requestNotificationPermission(activity: android.app.Activity)', helper)
        self.assertIn('activity.requestPermissions(', helper)
        self.assertNotIn('android.app.Fragment', helper)
        self.assertIn('ContextCompat.checkSelfPermission(activity, POST_NOTIFICATIONS_PERMISSION)', helper)
