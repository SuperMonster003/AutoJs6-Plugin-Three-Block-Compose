package io.github.supermonster003.autojs6.plugin.three.block.compose.designer

import io.github.supermonster003.autojs6.plugin.three.block.compose.designer.codegen.ComposeAttachStubs
import io.github.supermonster003.autojs6.plugin.three.block.compose.designer.codegen.UiScriptWriter
import io.github.supermonster003.autojs6.plugin.three.block.compose.designer.model.DesignChild
import io.github.supermonster003.autojs6.plugin.three.block.compose.designer.model.DesignDocument
import io.github.supermonster003.autojs6.plugin.three.block.compose.designer.model.DesignNode
import io.github.supermonster003.autojs6.plugin.three.block.compose.designer.model.EditCommand
import io.github.supermonster003.autojs6.plugin.three.block.compose.designer.model.NodeEntry
import io.github.supermonster003.autojs6.plugin.three.block.compose.designer.model.PropValue
import io.github.supermonster003.autojs6.plugin.three.block.compose.designer.model.Severity
import io.github.supermonster003.autojs6.plugin.three.block.compose.designer.model.StateDecl
import io.github.supermonster003.autojs6.plugin.three.block.compose.designer.model.UiDocument
import io.github.supermonster003.autojs6.plugin.three.block.compose.designer.model.UiEditCommand
import io.github.supermonster003.autojs6.plugin.three.block.compose.designer.model.UiEditHistory
import io.github.supermonster003.autojs6.plugin.three.block.compose.designer.model.UiEvents
import io.github.supermonster003.autojs6.plugin.three.block.compose.designer.model.UiValidator
import io.github.supermonster003.autojs6.plugin.three.block.compose.designer.model.idName
import io.github.supermonster003.autojs6.plugin.three.block.compose.designer.parse.TsxUiReader
import io.github.supermonster003.autojs6.plugin.three.block.compose.designer.parse.UiScriptReader
import io.github.supermonster003.autojs6.plugin.three.block.compose.designer.preview.XmlPreviewFrames
import io.github.supermonster003.autojs6.plugin.three.block.compose.designer.preview.XmlPreviewValues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Roadmap P11.8 / 3.6 / D35 / D70: the interfaces `compose.attach(ui.<id>, ...)` puts into `<compose>` elements of a
 * `"ui"` script. They read as compose documents tied to their containers; an unedited script is written back byte for
 * byte and an edit of an interface rewrites only its argument, which reads back to the same interface; a call the
 * designer cannot read stays as written and is listed; a new container comes with its call (JavaScript and TSX).
 */
class ComposeAttachmentTest {
    private val reader = UiScriptReader()
    private val snapshots = TsxSnapshots()

    private fun DesignDocument.text(text: String): DesignNode = nodes().first { it.prop("text") == PropValue.str(text) }

    private fun UiDocument.argument(containerId: String): String = attachment(containerId)!!.source

    @Test
    fun `interfaces are read, tied to their containers and written back byte for byte`() {
        val document = reader.read(SCRIPT)
        assertEquals(listOf("panel", "fixed", "orphan"), document.composeAttachments.map { it.containerId })
        assertEquals(SCRIPT, UiScriptWriter.write(document))
        val panel = document.attachment("panel")!!
        assertEquals(listOf("Column", "Text", "Button", "Text"), panel.document.nodes().map { it.type }.toList())
        assertEquals(listOf("count"), panel.document.states.map { it.name })
        assertTrue(panel.document.mount!!.render)
        assertEquals(SCRIPT.lines().indexOfFirst { it.startsWith("compose.attach(ui.panel") } + 1, panel.line)
        assertEquals(1, panel.column)
        assertEquals("compose.Text({text: \"Fixed\"})", document.argument("fixed"))
        assertFalse(document.attachment("fixed")!!.document.mount!!.render)
        // The second call into ui.panel and the call into a variable stay as written and are listed.
        assertEquals(listOf("panel", null), document.attachmentProblems.map { it.containerId })
        assertTrue(document.attachmentProblems[0].reason, "already has an interface" in document.attachmentProblems[0].reason)
        assertTrue(document.attachmentProblems[1].reason, "needs ui.<id>" in document.attachmentProblems[1].reason)
        assertTrue(UiScriptWriter.write(document).contains("compose.attach(slot, compose.Text({text: \"By variable\"}));"))
    }

    @Test
    fun `the validator lists interfaces without a container and calls it cannot read`() {
        val document = reader.read(SCRIPT)
        val warnings = UiValidator().validate(document).filter { it.severity == Severity.WARNING && "compose" in it.message }
        val panelElement = document.elements().first { it.idName() == "panel" }
        assertEquals(3, warnings.size)
        assertTrue(warnings.any { it.nodeId == null && "compose id=\"orphan\"" in it.message })
        assertTrue(warnings.any { it.nodeId == panelElement.id && "already has an interface" in it.message })
        assertTrue(warnings.any { it.nodeId == null && "needs ui.<id>" in it.message })
    }

    @Test
    fun `an edit rewrites only the argument and reads back to the same interface`() {
        val document = reader.read(SCRIPT)
        val history = UiEditHistory(document)
        val text = document.attachment("panel")!!.document.text("Panel")
        assertTrue(history.execute(UiEditCommand.EditAttachment("panel", EditCommand.SetProp(text.id, "text", PropValue.str("Pan"))), merge = true))
        assertTrue(history.execute(UiEditCommand.EditAttachment("panel", EditCommand.SetProp(text.id, "text", PropValue.str("Panel edited"))), merge = true))
        // Typing merges into one undo step, as in a compose project.
        assertEquals(1, history.undoCount)
        val written = UiScriptWriter.write(history.document)
        val before = document.argument("panel")
        val start = SCRIPT.indexOf(before)
        assertEquals(SCRIPT.substring(0, start), written.substring(0, start))
        assertEquals(SCRIPT.substring(start + before.length), written.substring(written.length - (SCRIPT.length - start - before.length)))
        assertTrue(written, "compose.Text({text: 'Panel edited'})" in written)
        assertFalse(written, "Panel\"" in written)
        // The rewritten argument reads back to the edited interface and is a fixed point.
        val reread = reader.read(written)
        assertEquals(history.document.attachment("panel")!!.document.mount!!.root.withoutIds(), reread.attachment("panel")!!.document.mount!!.root.withoutIds())
        assertEquals(written, UiScriptWriter.write(reread))
        assertEquals(document.argument("fixed"), reread.argument("fixed"))
        // Undo is byte exact.
        assertTrue(history.undo())
        assertEquals(SCRIPT, UiScriptWriter.write(history.document))
    }

    @Test
    fun `edits outside the mount are refused and other containers are untouched`() {
        val document = reader.read(SCRIPT)
        val history = UiEditHistory(document)
        val states = listOf(StateDecl("var", "count", PropValue.num(5.0)))
        assertFalse(history.execute(UiEditCommand.EditAttachment("panel", EditCommand.SetStates(states)), merge = false))
        assertFalse(history.execute(UiEditCommand.EditAttachment("missing", EditCommand.SetProp(1, "text", PropValue.str("x"))), merge = false))
        val fixed = document.attachment("fixed")!!.document
        assertTrue(history.execute(UiEditCommand.EditAttachment("fixed", EditCommand.SetProp(fixed.root!!.id, "text", PropValue.str("Fixed!"))), merge = false))
        val written = UiScriptWriter.write(history.document)
        assertEquals(SCRIPT.replace("compose.Text({text: \"Fixed\"})", "compose.Text({text: 'Fixed!'})"), written)
    }

    @Test
    fun `a container as tall as its interface gets the measured height in the preview request`() {
        val document = reader.read(SCRIPT)
        val panel = document.elements().first { it.idName() == "panel" }
        val fixed = document.elements().first { it.idName() == "fixed" }
        assertTrue(XmlPreviewFrames.takesMeasuredHeight(panel))
        assertFalse(XmlPreviewFrames.takesMeasuredHeight(fixed))
        val request = XmlPreviewFrames.build(document, values = XmlPreviewValues(composeHeights = mapOf("panel" to 96, "fixed" to 40)))
        assertTrue(request.xml, "<compose id=\"panel\" w=\"*\" minHeight=\"96dp\"/>" in request.xml)
        assertTrue(request.xml, "<compose id=\"fixed\" w=\"*\" h=\"120\"/>" in request.xml)
    }

    @Test
    fun `a new JavaScript container comes with its compose attach call after the handlers`() {
        val source = SCRIPT.substringBefore("compose.attach(ui.panel")
        val document = reader.read(source)
        val history = UiEditHistory(document)
        val root = document.root!!
        val added = ComposeAttachStubs.add(document, root.id, root.elementChildren.size, "compose1")
        assertNotNull(added)
        assertTrue(history.execute(UiEditCommand.ReplaceDocument(added!!), merge = false))
        val written = UiScriptWriter.write(history.document)
        val expected = source.replace(
            "        <compose id=\"fixed\" w=\"*\" h=\"120\"/>\n",
            "        <compose id=\"fixed\" w=\"*\" h=\"120\"/>\n        <compose id=\"compose1\" w=\"*\"/>\n",
        ).replace(
            "ui.title.on('click', () => toast('title'));\n",
            "ui.title.on('click', () => toast('title'));\n\n" + JS_STUB,
        )
        assertEquals(expected, written)
        val reread = reader.read(written)
        assertEquals(emptyList<Any>(), reread.attachmentProblems)
        val attachment = reread.attachment("compose1")!!
        assertEquals(added.attachment("compose1")!!.document.mount!!.root.withoutIds(), attachment.document.mount!!.root.withoutIds())
        assertEquals(added.attachment("compose1")!!.line, attachment.line)
        assertEquals(written, UiScriptWriter.write(reread))
        assertTrue(UiValidator().validate(reread).none { "compose" in it.message })
        // Editing the new interface before saving writes the edit; undo removes the container and the call.
        val text = history.document.attachment("compose1")!!.document.text("Compose")
        assertTrue(history.execute(UiEditCommand.EditAttachment("compose1", EditCommand.SetProp(text.id, "text", PropValue.str("Hello"))), merge = false))
        assertEquals(expected.replace("text: 'Compose'", "text: 'Hello'"), UiScriptWriter.write(history.document))
        history.undo()
        history.undo()
        assertEquals(source, UiScriptWriter.write(history.document))
    }

    @Test
    fun `a second container goes after the first call and a stub keeps working`() {
        val source = SCRIPT.substringBefore("compose.attach(ui.panel")
        val document = reader.read(source)
        val root = document.root!!
        val once = ComposeAttachStubs.add(document, root.id, root.elementChildren.size, "compose1")!!
        val twice = ComposeAttachStubs.add(once, root.id, 0, "compose2")!!
        val written = UiScriptWriter.write(twice)
        assertTrue(written.indexOf("<compose id=\"compose2\" w=\"*\"/>") < written.indexOf("<text id=\"title\""))
        val reread = reader.read(written)
        assertEquals(setOf("compose1", "compose2"), reread.composeAttachments.map { it.containerId }.toSet())
        assertEquals(emptyList<Any>(), reread.attachmentProblems)
        assertEquals(written, UiScriptWriter.write(reread))
        // A handler stub added afterwards still goes behind the handlers of the layout.
        val title = twice.elements().first { it.idName() == "title" }
        val event = UiEvents.common.first { it.name == "long_click" }
        val stubbed = UiEditCommand.AddHandlerStub(title.id, event).apply(twice)
        val withStub = UiScriptWriter.write(stubbed)
        assertTrue(withStub.indexOf("ui.title.on('long_click'") < withStub.indexOf("compose.attach("))
        assertEquals(withStub, UiScriptWriter.write(reader.read(withStub)))
    }

    @Test
    fun `no container is added where the call has no place`() {
        val returned = reader.read("\"ui\";\n\nfunction show() {\n    return ui.layout(\n        <vertical>\n            <text text=\"a\"/>\n        </vertical>\n    );\n}\n")
        assertNull(ComposeAttachStubs.add(returned, returned.root!!.id, 0, "compose1"))
        val native = reader.read(NATIVE)
        assertNull(ComposeAttachStubs.add(native, native.root!!.id, 0, "compose1"))
    }

    @Test
    fun `TSX interfaces read, write back and take edits in their own form`() {
        val document = TsxUiReader().read(snapshots.source(TSX), snapshots.tree(TSX))
        assertEquals(snapshots.source(TSX), UiScriptWriter.write(document))
        assertEquals(listOf("panel", "compose1"), document.composeAttachments.map { it.containerId })
        assertEquals(listOf(null), document.attachmentProblems.map { it.containerId })
        assertTrue(UiValidator().validate(document).filter { "compose" in it.message }.size == 1)
        val text = document.attachment("panel")!!.document.text("Panel")
        val edited = UiEditCommand.EditAttachment("panel", EditCommand.SetProp(text.id, "text", PropValue.str("Panel edited"))).apply(document)
        val written = UiScriptWriter.write(edited)
        val argument = document.argument("panel")
        val start = snapshots.source(TSX).indexOf(argument)
        assertEquals(snapshots.source(TSX).substring(0, start), written.substring(0, start))
        assertTrue(written, "<compose.Text text='Panel edited' />" in written)
    }

    @Test
    fun `a new TSX container uses the D68 cast and an arrow, the form the TSX sample reads`() {
        val name = "ui/variable.tsx"
        val document = TsxUiReader().read(snapshots.source(name), snapshots.tree(name))
        val fragment = document.fragments.indexOfFirst { it.stubOffset >= 0 }
        assertTrue(fragment >= 0)
        val root = document.fragments[fragment].root
        val added = ComposeAttachStubs.add(document, root.id, root.elementChildren.size, "compose1")!!
        val written = UiScriptWriter.write(added)
        val call = written.substring(written.indexOf("compose.attach("), written.indexOf("});", written.indexOf("compose.attach(")) + 3)
        val indent = document.fragments[fragment].stubIndent
        assertEquals(TSX_STUB.lines().joinToString("\n") { if (it.isEmpty()) it else indent + it }.trimStart().trimEnd('\n'), call)
        assertTrue(written, "<compose id='compose1' w='*' />" in written)
        // The sample holds the same call as written, and it reads as an interface without problems.
        assertTrue(snapshots.source(TSX).contains(TSX_STUB))
    }

    /** [this] with every node id 0, to compare trees read from different texts. */
    private fun DesignNode.withoutIds(): DesignNode = copy(
        id = 0,
        children = children.map { it.withoutIds() },
        entries = entries.map { entry -> if (entry is NodeEntry.Slot) entry.copy(child = entry.child.withoutIds()) else entry },
    )

    private fun DesignChild.withoutIds(): DesignChild = if (this is DesignChild.Node) copy(node = node.withoutIds()) else this

    private companion object {
        const val TSX = "ui/compose.tsx"

        val SCRIPT = """
            |"ui";
            |
            |var count = compose.state(0);
            |
            |ui.layout(
            |    <vertical padding="16">
            |        <text id="title" text="Header"/>
            |        <compose id="panel" w="*"/>
            |        <compose id="fixed" w="*" h="120"/>
            |    </vertical>
            |);
            |
            |ui.title.on('click', () => toast('title'));
            |
            |compose.attach(ui.panel, function () {
            |    return compose.Column({padding: 16},
            |        compose.Text({text: "Panel"}),
            |        compose.Button({onClick: () => { count.value++; }},
            |            compose.Text({text: "Add"})));
            |});
            |
            |compose.attach(ui.fixed, compose.Text({text: "Fixed"}));
            |
            |compose.attach(ui.orphan, compose.Text({text: "Orphan"}));
            |
            |compose.attach(ui.panel, compose.Text({text: "Twice"}));
            |
            |var slot = ui.findView('panel');
            |compose.attach(slot, compose.Text({text: "By variable"}));
            |""".trimMargin()

        val JS_STUB = """
            |compose.attach(ui.compose1, function () {
            |    return compose.Column({padding: 16, spacing: 8},
            |        compose.Text({text: 'Compose'}));
            |});
            |""".trimMargin()

        val TSX_STUB = """
            |compose.attach((ui as any).compose1, () => {
            |    return (
            |        <compose.Column padding={16} spacing={8}>
            |            <compose.Text text='Compose' />
            |        </compose.Column>
            |    );
            |});
            |""".trimMargin()

        val NATIVE = """
            |"ui";
            |
            |ui.layout(
            |    <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android" android:orientation="vertical">
            |        <TextView android:id="@+id/title" android:text="a"/>
            |    </LinearLayout>
            |);
            |""".trimMargin()
    }
}
