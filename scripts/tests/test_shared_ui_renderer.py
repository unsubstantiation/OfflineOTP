"""Chunk-9 ratchet. Compiler/stub gates and these scans do not establish visual/native parity."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
FOUNDATION = 'ui-foundation/src/commonMain/kotlin/com/fairuniversal/offlineotp/ui/vault/'
CONTRACT = 'ui-contract/src/commonMain/kotlin/com/fairuniversal/offlineotp/ui/portable/'
UI = 'ui/src/jvmHostMain/kotlin/com/fairuniversal/offlineotp/ui/vault/'


def read(path):
    return (ROOT / path).read_text()


class SharedUiRendererTest(unittest.TestCase):
    def test_portable_render_contract_has_no_engine_toolkit_or_native_types(self):
        for name in ['VaultRenderContract.kt', 'PortableCardPresentation.kt']:
            source = read(CONTRACT + name)
            for forbidden in ['import java.', 'import android.', 'import androidx.', 'import platform.',
                              'Database.Entry', 'SecretKey', 'JSONObject', 'rememberSaveable', 'Serializable']:
                self.assertNotIn(forbidden, source)
        contract = read(CONTRACT + 'VaultRenderContract.kt')
        self.assertIn('VaultCardContent(redacted)', contract)
        self.assertIn('previousToken?.takeIf { tokenVisible }', contract)
        self.assertIn('if (tokenVisible) value else', contract)

    def test_single_production_list_card_and_code_assembly(self):
        renderer = read(FOUNDATION + 'SharedVaultRenderer.kt')
        self.assertIn('SharedVaultEntryCard(', renderer)
        self.assertIn('SharedVaultCodeArea(', renderer)
        self.assertIn('detectDragGesturesAfterLongPress(', renderer)
        for path in [UI + 'VaultScreen.kt', FOUNDATION + 'PortableVaultScreen.kt']:
            source = read(path)
            self.assertIn('SharedVaultList(', source)
            self.assertNotIn('LazyColumn(', source)
            self.assertNotIn('detectDragGesturesAfterLongPress(', source)
        self.assertIn('SharedVaultRenderedCard(', read(UI + 'VaultComponents.kt'))
        self.assertIn('SharedVaultRenderedCard(', read(FOUNDATION + 'PortableVaultScreen.kt'))
        for root in ['ui-foundation/src/commonMain/kotlin', 'ui/src/jvmHostMain/kotlin']:
            for file in (ROOT / root).rglob('*.kt'):
                if file.name in ['SharedVaultComponents.kt', 'SharedVaultRenderer.kt']:
                    continue
                for retired in ['SharedVaultEntryCard(', 'SharedVaultCodeArea(']:
                    self.assertNotIn(retired, file.read_text(), str(file))

    def test_only_jvm_host_source_sets_depend_on_the_jvm_engine(self):
        for module, portable in [('ui', 'ui-foundation'), ('ui-model', 'ui-contract')]:
            build = read(module + '/build.gradle')
            common = build.split('commonMain.dependencies {', 1)[1].split('}', 1)[0]
            self.assertIn("api project(':" + portable + "')", common)
            self.assertNotIn("project(':core')", common)
            self.assertNotIn("project(':ui-model')", common)
            self.assertIn('jvmHostMain {', build)
            self.assertIn('androidMain.dependsOn jvmHostMain', build)
            self.assertIn('desktopMain.dependsOn jvmHostMain', build)
            self.assertFalse(list((ROOT / module / 'src/commonMain').rglob('*.kt')))
            self.assertTrue(list((ROOT / module / 'src/jvmHostMain').rglob('*.kt')))

    def test_both_hosts_wait_for_adoption_and_do_not_optimistically_retarget_a_drag(self):
        renderer = read(FOUNDATION + 'SharedVaultRenderer.kt')
        for source in ['VaultDragSession()', 'drag.adopt(order)', 'drag.request(direction, order)',
                       'LaunchedEffect(filterKey)', 'onDragCancel = { endDrag() }']:
            self.assertIn(source, renderer)
        policy = read(CONTRACT + 'VaultRenderContract.kt')
        self.assertIn('if (current == awaiting) return false', policy)
        self.assertIn('if (current != expected)', policy)
        self.assertIn('order.distinct().size != order.size', policy)
        portable = read(FOUNDATION + 'PortableVaultScreen.kt')
        self.assertIn('key = { it.stableId }', portable)
        self.assertNotIn('draggingIndex = neighborIndex', portable)

    def test_apple_frame_and_surface_delivery_fail_closed_after_replacement_and_teardown(self):
        bridge = read('ui-foundation/src/iosMain/kotlin/com/fairuniversal/offlineotp/ui/vault/PortableVaultViewController.kt')
        for expected in ['VaultFrameDelivery()', 'frameTicket = delivery.publish()', 'delivery.current(ticket)',
                         'if (current()) vaultActions.card(index, action)', 'delivery.close()',
                         'delivery.invalidate()', 'vault = PortableVaultState.EMPTY', 'surfaceCurrent()',
                         'epoch == surfaceEpoch', 'if (surfaceCurrent()) surfaceActions.change(key, value)']:
            self.assertIn(expected, bridge)
        native = read('ios/App/Sources/ComposeVaultHost.swift')
        for expected in ['stableId: identity', 'displayedEntries[position] == store.entries[position]',
                         'presentation.clearFrame()', 'guard active, foreground, store.isUnlocked else',
                         'cardIdentities = [:]; displayedEntries = [:]']:
            self.assertIn(expected, native)

    def test_compiler_gate_builds_renderer_without_core_or_model_before_adapters(self):
        gate = read('scripts/kotlin-sandbox/compile-ui.sh')
        self.assertIn('-module-name ui-contract', gate)
        self.assertIn('-module-name ui-foundation -cp "$OUT/contract:$STDLIB"', gate)
        self.assertIn('ui-model/src/jvmHostMain/kotlin', gate)
        self.assertIn('ui/src/jvmHostMain/kotlin', gate)
        self.assertIn('ui-foundation/src/iosMain/kotlin', gate)
