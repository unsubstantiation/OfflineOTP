"""Source reachability guards, not proof of rendering, SDK behavior or device acceptance.

The executable VaultToolsTest covers dispatch and stale-session guards. These checks
cover host wiring and catalog omissions that a model-only test cannot detect.
"""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
ANDROID = ROOT / 'app/src/main/kotlin/com/fairuniversal/offlineotp/Activities'
MODEL = ROOT / 'ui-model/src/jvmHostMain/kotlin/com/fairuniversal/offlineotp/ui/vault'
FOUNDATION = ROOT / 'ui-foundation/src/commonMain/kotlin/com/fairuniversal/offlineotp/ui/vault'


def swift_cases(text):
    return {key for case in re.findall(r'\bcase\s+([^:]+):', text)
            for key in re.findall(r'"([\w]+)"', case)}


class UiFeatureReachabilityTest(unittest.TestCase):
    def test_every_optional_no_argument_vault_action_has_a_visible_route(self):
        constructor = (MODEL / 'VaultActions.kt').read_text().split(') {', 1)[0]
        optional = set(re.findall(r'val (on\w+): \(\(\) -> Unit\)\?', constructor))
        tools = set(re.findall(r'-> actions\.(on\w+)', (MODEL / 'VaultTools.kt').read_text()))
        # Add is primary; the tag-rule control lives in the filter header, not Tools.
        self.assertEqual(optional, tools | {'onAddEntry', 'onCycleTagRule'})
        screen = (ROOT / 'ui/src/jvmHostMain/kotlin/com/fairuniversal/offlineotp/ui/vault/VaultScreen.kt').read_text()
        toolbar = (ROOT / 'ui/src/jvmHostMain/kotlin/com/fairuniversal/offlineotp/ui/vault/VaultToolsBar.kt').read_text()
        components = (ROOT / 'ui/src/jvmHostMain/kotlin/com/fairuniversal/offlineotp/ui/vault/VaultComponents.kt').read_text()
        self.assertIn('VaultTagChips(', screen)
        self.assertIn('actions.onCycleTagRule', components)
        self.assertIn('onAdd = actions.onAddEntry', toolbar)

    def test_android_alternate_tools_offer_the_same_secondary_callbacks_as_main(self):
        main = (ANDROID / 'MainActivity.kt').read_text()
        tools = main.split('private fun openVaultToolsDialog()', 1)[1].split('private fun launchBackupTool', 1)[0]
        frame = main.split('view.render(published.state, VaultActions(', 1)[1].split(').guarded', 1)[0]
        secondary = set(re.findall(r'-> actions\.(on\w+)', (MODEL / 'VaultTools.kt').read_text()))
        provided = lambda text: set(re.findall(r'\b(on\w+)\s*=', text)) & secondary
        self.assertEqual(provided(frame), provided(tools))
        for callback, destination in (('onOpenSettings', 'SettingsActivity::class.java'),
                                      ('onHardwareOath', 'HardwareKeyActivity::class.java')):
            self.assertRegex(tools, callback + r'\s*=\s*\{[^\n]*' + re.escape(destination))
        self.assertIn('VaultSession.isCurrent(generation)', tools)

    def test_android_settings_catalog_entries_have_dispatch_handlers(self):
        settings = (ANDROID / 'SettingsActivity.kt').read_text()
        overview = settings.split('PortableToolItem(', 1)[1].split('private fun dispatchSettingsOverviewAction', 1)[0]
        keys = set(re.findall(r'PortableToolItem\("(\w+)"', overview)) | {'autoLock'}
        handler = settings.split('private fun dispatchSettingsOverviewAction', 1)[1].split('private fun showSharedCardDisplay', 1)[0]
        handled = set(re.findall(r'"(\w+)"\s*->', handler))
        self.assertGreaterEqual(len(keys), 35)
        self.assertEqual(set(), keys - handled)

    def test_desktop_supplies_all_presenter_secondary_features(self):
        presenter = (MODEL / 'VaultPresenter.kt').read_text().split('fun actions(', 1)[1].split('): VaultActions', 1)[0]
        supplied = set(re.findall(r'(\w+): \(\(\) -> Unit\)\?', presenter))
        desktop = (ROOT / 'desktop-compose/src/main/kotlin/com/fairuniversal/offlineotp/desktop/compose/ComposeMain.kt').read_text()
        frame = desktop.split('actions = presenter.actions(', 1)[1].split('appearance = presenter.appearance', 1)[0]
        self.assertEqual(set(), supplied - set(re.findall(r'\b(\w+)\s*=', frame)))

    def test_ios_top_level_tool_actions_have_host_dispatch_cases(self):
        host = (ROOT / 'ios/App/Sources/ComposeVaultHost.swift').read_text()
        catalogs = host.split('private func settingsSurface()', 1)[1].split('private func editorSurface()', 1)[0]
        keys = set(re.findall(r'row\("(\w+)"', catalogs))
        # Explicit informational rows are deliberately noninteractive, not missing features.
        information = {'info', 'info2', 'openpgpUnavailable', 'openpgpNotice'}
        handler = host.split('private func surfaceAction(', 1)[1].split('func setOpenPgpData(', 1)[0]
        self.assertGreaterEqual(len(keys), 30)
        self.assertEqual(set(), keys - information - swift_cases(handler))
        self.assertIn('backupSurface().sections + settingsSurface().sections', host)
        self.assertIn('interactive: itemRow.kind != "info"', host)
        self.assertIn('_ = surfaceAction(action)', host)

    def test_nested_import_and_backup_features_remain_visible(self):
        desktop = (ROOT / 'desktop-compose/src/main/kotlin/com/fairuniversal/offlineotp/desktop/compose/ComposeMain.kt').read_text()
        for route in ('CameraCaptureDialog(', 'onSnapshot =', 'onMigrateAndSnapshot =',
                      'onEnableSnapshots =', 'HardwareCopyDialog(', 'HardwareOathDialog('):
            self.assertIn(route, desktop)
        host = (ROOT / 'ios/App/Sources/ComposeVaultHost.swift').read_text()
        for key in ('vaultSnapshot', 'migrateVaultSnapshot', 'automaticBackup', 'automaticChooseSigningKey',
                    'hardwareEnrollNFC', 'hardwareEnrollUSB', 'hardwareSeedNFC', 'hardwareSeedUSB'):
            self.assertIn('row("' + key + '"', host)
            self.assertIn(key, swift_cases(host.split('private func surfaceAction(', 1)[1]))

    def test_informational_tools_show_details_without_becoming_buttons(self):
        renderer = (FOUNDATION / 'SharedVaultToolsDialog.kt').read_text()
        info = renderer.split('if (!item.interactive)', 1)[1].split('} else if', 1)[0]
        for value in ('item.label', 'item.detail', 'item.value'):
            self.assertIn('text = ' + value, info)
        self.assertNotIn('onSelectTool(', info)
