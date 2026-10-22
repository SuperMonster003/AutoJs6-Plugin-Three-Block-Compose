package io.github.supermonster003.autojs6.plugin.three.block.compose.designer

import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.supermonster003.autojs6.plugin.three.block.compose.designer.preview.UiPreviewClient
import io.github.supermonster003.autojs6.plugin.three.block.compose.designer.storage.ProjectStore
import io.github.supermonster003.autojs6.plugin.three.block.compose.designer.ui.DesignerActivity
import org.autojs.plugin.ui.preview.UiPreviewContract
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Roadmap P11.8 / 3.6 / D70: the interface `compose.attach(ui.<id>, ...)` puts into a `<compose>` element. On the XML
 * canvas the designer draws it inside the bounds the host reports for the element, and the element takes the height
 * the interface needs (Q26); a tap on one of its nodes selects it in the Compose drawer, whose edits go into the
 * layout's undo history and are saved by rewriting the arguments of that call only, while the state panel changes the
 * preview only. The palette adds a `<compose>` element with a `compose.attach` call in one undo step. In the
 * interactive preview (API 33+) the host runs the script and its Compose renderer draws the interface in the canvas:
 * a tap on it reaches the script's handler. Skipped without the host's preview service.
 */
@RunWith(AndroidJUnit4::class)
internal class UiComposeLinkDeviceTest {
    @get:Rule val rule = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val store = ProjectStore(context)

    @Before fun clean() {
        assumeTrue("the host with the preview service is not installed", UiPreviewClient.availability(context) == null)
        remove()
    }

    @After fun remove() { store.list().filter { it.name.startsWith("ui-compose-test") }.forEach { store.delete(it.name) } }

    private val head = "\"ui\";\nvar label = compose.state('Idle');\nui.layout(\n" +
        "    <vertical id=\"root\" padding=\"16\">\n" +
        "        <text id=\"title\" text=\"Header\"/>\n" +
        "        <compose id=\"panel\" w=\"*\"/>\n" +
        "        <text id=\"after\" text=\"After\"/>\n" +
        "    </vertical>\n);\n"
    private val attach = "compose.attach(ui.panel, function () {\n" +
        "    return compose.Column({padding: 16, spacing: 8},\n" +
        "        compose.Text({text: \"Panel text\"}),\n" +
        "        compose.Text({text: label.value}));\n" +
        "});\n"
    private val tail = "ui.title.on(\"click\", () => toast(\"title\"));\n"

    private fun ActivityScenario<DesignerActivity>.activity(): DesignerActivity {
        lateinit var activity: DesignerActivity
        onActivity { activity = it }
        return activity
    }

    private fun ActivityScenario<DesignerActivity>.readyClient(): UiPreviewClient {
        val client = requireNotNull(activity().currentPreviewClient)
        rule.waitUntil(30_000) { client.status is UiPreviewClient.Status.Ready || client.status is UiPreviewClient.Status.Unavailable }
        assertTrue(client.status.toString(), client.status is UiPreviewClient.Status.Ready)
        return client
    }

    /** [rule.waitUntil] whose failure says what [describe] tells. */
    private fun waitFor(timeoutMillis: Long, describe: () -> String, condition: () -> Boolean) {
        try {
            rule.waitUntil(timeoutMillis, condition)
        } catch (timeout: ComposeTimeoutException) {
            throw AssertionError(describe(), timeout)
        }
    }

    /** A tap injected through the system (Compose's touch injection reflects on a hidden API that API 37 removed). */
    private fun tapOnScreen(x: Float, y: Float) {
        val automation = instrumentation.uiAutomation
        val now = SystemClock.uptimeMillis()
        MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }.let { automation.injectInputEvent(it, true); it.recycle() }
        MotionEvent.obtain(now, now + 40, MotionEvent.ACTION_UP, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }.let { automation.injectInputEvent(it, true); it.recycle() }
    }

    private fun inElement(id: String): SemanticsMatcher = hasAnyAncestor(hasTestTag("designer-xml-compose-$id"))

    private fun shown(text: String, id: String = "panel") = rule.onAllNodes(hasText(text) and inElement(id)).fetchSemanticsNodes().isNotEmpty()

    private fun boundsOf(tag: String): Rect = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    @Test
    fun theCanvasDrawsTheInterfaceInItsElementAndTheDrawerEditsOnlyItsCall() {
        val script = head + attach + tail
        val name = store.create("ui-compose-test-link", script).name
        ActivityScenario.launch<DesignerActivity>(DesignerActivity.intent(context, name)).use { scenario ->
            val client = scenario.readyClient()
            val session = requireNotNull(scenario.activity().currentUiSession)
            val panelId = session.document.elements().first { it.attr("id")?.value == "panel" }.id
            // A cold host starts its first script engine slowly (an x86 emulator took 18 s).
            rule.waitUntil(90_000) { session.previewFrames > 0 && client.bounds.isNotEmpty() }
            waitFor(30_000, { "problems: ${session.previewProblems}" }) { shown("Panel text") && shown("Idle") }

            // The interface sits in the element's bounds (zoom 1: canvas pixels are root pixels), as wide as it, and the
            // next preview gives the element the interface's height, so the element below starts under the interface.
            val origin = boundsOf("designer-xml-overlay").topLeft
            fun hostBounds(id: String) = client.bounds.filter { it.id == id && it.visibility == 0 }.minBy { it.instance }
            waitFor(30_000, { "panel ${hostBounds("panel")}, interface ${boundsOf("designer-xml-compose-panel")}, heights ${session.composeHeights}" }) {
                val drawn = boundsOf("designer-xml-compose-panel")
                val panel = hostBounds("panel")
                "panel" in session.composeHeights && panel.bottom - panel.top >= drawn.height - 2 && hostBounds("after").top >= drawn.bottom - origin.y - 2
            }
            val drawn = boundsOf("designer-xml-compose-panel")
            val panel = hostBounds("panel")
            assertTrue("$drawn in $panel", abs(drawn.left - origin.x - panel.left) <= 1f && abs(drawn.top - origin.y - panel.top) <= 1f)
            assertTrue("$drawn in $panel", abs(drawn.width - (panel.right - panel.left)) <= 1f)
            val text = rule.onNode(hasText("Panel text") and inElement("panel")).fetchSemanticsNode().boundsInRoot
            assertTrue("$text in $drawn", drawn.contains(text.topLeft) && drawn.contains(text.bottomRight - androidx.compose.ui.geometry.Offset(1f, 1f)))

            // A tap on a node of the interface selects it in the Compose drawer, together with its element.
            val node = rule.onNode(hasText("Panel text") and inElement("panel")).fetchSemanticsNode()
            tapOnScreen(node.positionOnScreen.x + node.size.width / 2f, node.positionOnScreen.y + node.size.height / 2f)
            rule.waitUntil(5_000) { session.attachmentId == "panel" }
            val compose = requireNotNull(session.attachmentSession)
            assertEquals("Text", compose.selected?.type)
            assertEquals(panelId, session.selectedId)
            rule.onNodeWithTag("designer-compose-drawer").assertIsDisplayed()

            // The inspector edits the node: the canvas follows, and one undo of the layout's history takes it back.
            rule.onNodeWithTag("designer-tab-inspector").performClick()
            rule.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("designer-inspector")) and hasText("Panel text")).performScrollTo().performTextReplacement("Edited")
            rule.waitUntil(5_000) { shown("Edited") }
            assertFalse(shown("Panel text"))
            rule.onNodeWithTag("designer-undo").performClick()
            rule.waitUntil(5_000) { shown("Panel text") }
            assertEquals(script, session.script())
            rule.onNodeWithTag("designer-redo").performClick()
            rule.waitUntil(5_000) { shown("Edited") }

            // The state panel changes the preview value only.
            rule.onNodeWithTag("designer-tab-state").performClick()
            rule.onNodeWithTag("designer-state-value-label").performScrollTo().performTextClearance()
            rule.onNodeWithTag("designer-state-value-label").performTextInput("Busy")
            rule.waitUntil(5_000) { shown("Busy") }
            assertFalse(session.script().contains("Busy"))

            // Back to the layout's panels.
            rule.onNodeWithTag("designer-compose-back").performClick()
            rule.waitUntil(5_000) { session.attachmentId == null }
            rule.onNodeWithTag("designer-tab-bindings").assertIsDisplayed()

            rule.onNodeWithTag("designer-save").performClick()
            rule.waitForIdle()
        }
        // Only the arguments of the call are rewritten: the layout, the declarations and the handler keep their text.
        val saved = store.read(name)
        assertTrue(saved, saved.startsWith(head + "compose.attach(ui.panel, "))
        assertTrue(saved, saved.endsWith("});\n" + tail))
        val call = saved.removePrefix(head).removeSuffix(tail)
        assertTrue(call, call.contains("'Edited'") && !call.contains("Panel text") && call.contains("label.value") && !call.contains("Busy"))
        assertFalse(store.draftFile(name).exists())
    }

    @Test
    fun thePaletteAddsAComposeElementWithItsCallInOneUndoStep() {
        val plain = "\"ui\";\nui.layout(\n" +
            "    <vertical id=\"root\" padding=\"16\">\n" +
            "        <text id=\"title\" text=\"Header\"/>\n" +
            "    </vertical>\n);\n" +
            "ui.title.on(\"click\", () => toast(\"title\"));\n"
        val expected = plain.replace(
            "        <text id=\"title\" text=\"Header\"/>\n",
            "        <text id=\"title\" text=\"Header\"/>\n        <compose id=\"compose1\" w=\"*\"/>\n",
        ) + "\n" + "compose.attach(ui.compose1, function () {\n" +
            "    return compose.Column({padding: 16, spacing: 8},\n" +
            "        compose.Text({text: 'Compose'}));\n" +
            "});\n"
        val name = store.create("ui-compose-test-add", plain).name
        ActivityScenario.launch<DesignerActivity>(DesignerActivity.intent(context, name)).use { scenario ->
            val client = scenario.readyClient()
            val session = requireNotNull(scenario.activity().currentUiSession)
            rule.waitUntil(90_000) { session.previewFrames > 0 && client.bounds.isNotEmpty() }
            rule.onNodeWithTag("designer-add").performClick()
            rule.onNodeWithTag("designer-palette").performScrollToNode(hasTestTag("designer-palette-compose"))
            rule.onNodeWithTag("designer-palette-compose").performClick()
            rule.waitUntil(5_000) { session.attachmentId == "compose1" }
            rule.onNodeWithTag("designer-compose-drawer").assertIsDisplayed()
            assertEquals(expected, session.script())
            waitFor(60_000, { "problems: ${session.previewProblems}" }) { shown("Compose", "compose1") }

            // One undo removes the element and its call, one redo brings both back.
            rule.onNodeWithTag("designer-undo").performClick()
            rule.waitUntil(5_000) { session.document.composeAttachments.isEmpty() }
            assertNull(session.attachmentId)
            assertEquals(plain, session.script())
            rule.onNodeWithTag("designer-redo").performClick()
            rule.waitUntil(5_000) { session.document.attachment("compose1") != null }
            assertEquals(expected, session.script())
            waitFor(60_000, { "problems: ${session.previewProblems}" }) { shown("Compose", "compose1") }

            rule.onNodeWithTag("designer-save").performClick()
            rule.waitForIdle()
        }
        assertEquals(expected, store.read(name))
    }

    @Test
    fun theInteractivePreviewHasTheHostDrawTheInterface() {
        assumeTrue("the interactive preview needs API 33", Build.VERSION.SDK_INT >= UiPreviewContract.MIN_API_INTERACTIVE)
        context.getSharedPreferences("designer", android.content.Context.MODE_PRIVATE).edit().remove("ui-interactive-confirmed").commit()
        val script = "\"ui\";\nui.layout(\n" +
            "    <vertical>\n" +
            "        <compose id=\"panel\" w=\"*\" h=\"*\"/>\n" +
            "    </vertical>\n);\n" +
            "console.log(\"started\");\n" +
            "compose.attach(ui.panel, function () {\n" +
            "    return compose.Button({onClick: function () { console.log(\"compose clicked\"); }, modifier: compose.modifier().fillMaxSize()},\n" +
            "        compose.Text({text: 'Tap'}));\n" +
            "});\n"
        val name = store.create("ui-compose-test-live", script).name
        ActivityScenario.launch<DesignerActivity>(DesignerActivity.intent(context, name)).use { scenario ->
            val client = scenario.readyClient()
            assumeTrue("the installed AutoJs6 does not offer runScript (preview contract v2)", client.scriptRunSupported)
            val session = requireNotNull(scenario.activity().currentUiSession)
            rule.onNodeWithTag("designer-interact").performClick()
            rule.onNodeWithTag("designer-ui-interact-confirm").performClick()
            // A host that has just started (first run on a new emulator) takes long to start its first script engine.
            rule.waitUntil(60_000) { rule.onAllNodesWithTag("designer-ui-live", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            val describe = { "console: ${client.consoleLines.map { it.text }}, error: ${client.scriptError}" }
            waitFor(60_000, describe) { client.consoleLines.any { it.text.contains("started") } }
            // The designer draws nothing over the live surface: what the tap reaches is the host's Compose renderer.
            assertTrue(rule.onAllNodesWithTag("designer-xml-compose-panel").fetchSemanticsNodes().isEmpty())
            waitFor(20_000, describe) {
                if (client.consoleLines.none { it.text.contains("compose clicked") }) {
                    val bounds = rule.onNodeWithTag("designer-xml-surface-live").fetchSemanticsNode().boundsInWindow
                    val location = IntArray(2)
                    scenario.onActivity { it.window.decorView.getLocationOnScreen(location) }
                    tapOnScreen(location[0] + bounds.center.x, location[1] + bounds.center.y)
                }
                SystemClock.sleep(500)
                client.consoleLines.any { it.text.contains("compose clicked") }
            }
            assertNull(describe(), client.scriptError)
            rule.onNodeWithTag("designer-interact").performClick()
            rule.waitUntil(15_000) { !client.running }
            assertFalse(session.dirty)
        }
        assertEquals(script, store.read(name))
    }
}
