package io.github.supermonster003.autojs6.plugin.three.block.compose.designer.codegen

import io.github.supermonster003.autojs6.plugin.three.block.compose.designer.model.ComposeAttachment
import io.github.supermonster003.autojs6.plugin.three.block.compose.designer.model.DesignChild
import io.github.supermonster003.autojs6.plugin.three.block.compose.designer.model.DesignDocument
import io.github.supermonster003.autojs6.plugin.three.block.compose.designer.model.Dialect
import io.github.supermonster003.autojs6.plugin.three.block.compose.designer.model.HostKind
import io.github.supermonster003.autojs6.plugin.three.block.compose.designer.model.MountDecl
import io.github.supermonster003.autojs6.plugin.three.block.compose.designer.model.NodeEntry
import io.github.supermonster003.autojs6.plugin.three.block.compose.designer.model.NodeTemplates
import io.github.supermonster003.autojs6.plugin.three.block.compose.designer.model.PropValue
import io.github.supermonster003.autojs6.plugin.three.block.compose.designer.model.Segment
import io.github.supermonster003.autojs6.plugin.three.block.compose.designer.model.UiDocument
import io.github.supermonster003.autojs6.plugin.three.block.compose.designer.model.UiEditCommand
import io.github.supermonster003.autojs6.plugin.three.block.compose.designer.model.UiSegment
import io.github.supermonster003.autojs6.plugin.three.block.compose.designer.model.XmlAttr
import io.github.supermonster003.autojs6.plugin.three.block.compose.designer.model.XmlNode

/**
 * A new `<compose>` container with the `compose.attach(ui.<id>, ...)` call that fills it (roadmap P11.8 / D70): the
 * element goes into the layout like a palette element, the call after the statement that lays the layout out, where
 * handler stubs go (D52). The call is written as JavaScript (`ui.<id>`, a function) or as TSX (`(ui as any).<id>`, an
 * arrow, D68); its argument is the attachment the Compose drawer edits at once, so nothing is read back.
 */
internal object ComposeAttachStubs {
    const val TAG = "compose"

    /**
     * The document with a `<compose id="[containerId]">` element as the [index]-th element child of [parentId] and its
     * `compose.attach` call, or null when the layout's statement leaves no place for the call (as for handler stubs)
     * or the parent is native Android XML, which has no `<compose>`.
     */
    fun add(document: UiDocument, parentId: Int, index: Int, containerId: String): UiDocument? {
        if (document.element(parentId) == null || document.isNative(parentId)) return null
        val fragmentIndex = document.fragmentOf(parentId)
        val fragment = document.fragments.getOrNull(fragmentIndex)?.takeIf { it.stubOffset >= 0 } ?: return null
        val segmentIndex = if (fragment.file != null) {
            fragment.stubSegment
        } else {
            document.segments.indexOfFirst { it is UiSegment.Layout && it.fragment.root.id == fragment.root.id }.takeIf { it >= 0 }?.plus(1) ?: return null
        }
        val opaque = document.segments.getOrNull(segmentIndex) as? UiSegment.Opaque ?: return null
        val offset = fragment.stubOffset
        if (offset !in 0..opaque.source.length) return null
        val withElement = UiEditCommand.InsertElement(parentId, index, container(containerId)).apply(document)
        val tsx = document.dialect == Dialect.TSX
        val indent = fragment.stubIndent
        val call = "\n\n" + indent + "compose.attach(" + (if (tsx) "(ui as any)." else "ui.") + containerId + ", "
        val states = document.composeAttachments.firstOrNull()?.document?.states.orEmpty()
        val tree = DesignDocument(states.map { Segment.State(it) } + Segment.Mount(mount(tsx)), 1, document.dialect).allocated()
        val source = if (tsx) TsxWriter.render(tree.mount!!, indent) else ScriptWriter.render(tree.mount!!, indent)
        val before = opaque.source.substring(0, offset) + call
        val line = UiScriptWriter.write(withElement.copy(segments = withElement.segments.take(segmentIndex))).count { it == '\n' } +
            before.count { it == '\n' } + 1
        val inserted = listOf(
            UiSegment.Opaque(before),
            UiSegment.Compose(ComposeAttachment(containerId, source, tree, line = line, column = indent.length + 1)),
            UiSegment.Opaque(");" + opaque.source.substring(offset)),
        )
        val segments = withElement.segments.take(segmentIndex) + inserted + withElement.segments.drop(segmentIndex + 1)
        // Stub points of layout files after the call move along: two more segments, and into the tail when past the offset.
        val files = withElement.files.map { file ->
            when {
                file.stubSegment > segmentIndex -> file.copy(stubSegment = file.stubSegment + 2)
                file.stubSegment == segmentIndex && file.stubOffset > offset -> file.copy(stubSegment = segmentIndex + 2, stubOffset = file.stubOffset - offset + 2)
                else -> file
            }
        }
        return withElement.copy(segments = segments, files = files)
    }

    /** The container element: as wide as its parent, as tall as the interface. */
    fun container(containerId: String): XmlNode.Element =
        XmlNode.Element(0, TAG, attrs = listOf(XmlAttr.literal("id", containerId), XmlAttr.literal("w", "*")))

    /** The interface of a new call: a column with one text. */
    private fun mount(tsx: Boolean): MountDecl {
        val text = NodeTemplates.create("Text").copy(entries = listOf(NodeEntry.Prop("text", PropValue.str("Compose"))))
        val column = NodeTemplates.create("Column").copy(children = listOf(DesignChild.Node(text)))
        return MountDecl(HostKind.MOUNT, column, render = true, renderArrow = tsx)
    }

    /** The document with its nodes numbered from its next id. */
    private fun DesignDocument.allocated(): DesignDocument {
        val (child, next) = allocate(DesignChild.Node(root!!))
        return withMount(mount!!.copy(root = (child as DesignChild.Node).node)).copy(nextId = next)
    }
}
