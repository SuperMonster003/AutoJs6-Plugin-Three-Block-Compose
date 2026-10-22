package io.github.supermonster003.autojs6.plugin.three.block.compose.designer.preview

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import io.github.supermonster003.autojs6.plugin.three.block.compose.designer.model.DesignDocument
import io.github.supermonster003.autojs6.plugin.three.block.compose.designer.model.JsLiteral
import io.github.supermonster003.autojs6.plugin.three.block.compose.renderer.LocalNodeDecorator
import io.github.supermonster003.autojs6.plugin.three.block.compose.renderer.RenderNode
import io.github.supermonster003.autojs6.plugin.three.block.compose.renderer.RendererWindowContext
import io.github.supermonster003.autojs6.plugin.three.block.compose.renderer.ThemeMapper
import org.autojs.plugin.compose.api.model.ThemeSpec
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * The interface `compose.attach` puts into a `<compose>` element (roadmap P11.8), drawn on the canvas of a `"ui"`
 * project by the real renderer, as [DesignCanvas] draws a compose project: from the top left of the element's
 * bounds on the host's surface ([container], canvas pixels), as wide as the element. An element as tall as its
 * interface ([fitsContent]) is at least as tall as the drawing and [onHeight] reports the height the interface takes
 * (dp of the logical screen), which the next preview request gives the element; one with a height of its own clips
 * the drawing to it. Node bounds go to [bounds] (whose [CanvasBounds.origin] the canvas sets) for hit tests and the
 * selection. Window components are not opened and no component takes focus: the canvas overlay owns the pointer.
 */
@Composable
internal fun AttachedInterface(
    containerId: String,
    document: DesignDocument,
    images: ImageSource?,
    theme: ThemeSpec,
    zoom: Float,
    container: Rect,
    fitsContent: Boolean,
    bounds: CanvasBounds,
    values: Map<String, JsLiteral>,
    onHeight: (Int) -> Unit,
) {
    val controllers = remember { CanvasControllers() }
    DisposableEffect(controllers) { onDispose { controllers.dispose() } }
    val generation = remember { LongArray(1) }
    val preview = remember(document, images, values) { PreviewFrames.build(document, ++generation[0], images = images, values = values) }
    val live = remember { LiveFrame() }
    val rendered = remember(preview) {
        controllers.bind(preview)
        preview.frame?.let(live::update)
    }
    LaunchedEffect(preview) {
        controllers.accept(preview)
        bounds.retain(preview.frame?.nodes?.keys ?: emptySet())
    }
    val decorator = remember(bounds) {
        { id: Int, mapped: Modifier -> Modifier.onGloballyPositioned { bounds.record(id, it.boundsInRoot()) }.then(mapped) }
    }
    val baseDensity = LocalDensity.current
    val zoomed = remember(baseDensity, zoom) { Density(baseDensity.density * zoom, baseDensity.fontScale) }
    val width = with(baseDensity) { container.width.toDp() }
    val height = with(baseDensity) { container.height.toDp() }
    val placement = Modifier
        .offset { IntOffset(container.left.roundToInt(), container.top.roundToInt()) }
        .requiredWidth(width)
        .let { if (fitsContent) it.heightIn(min = height) else it.requiredHeight(height).clipToBounds() }
        .testTag("designer-xml-compose-$containerId")
    Box(placement) {
        Box(Modifier.fillMaxWidth().wrapContentHeight().onSizeChanged { if (fitsContent) onHeight(ceil(it.height / zoomed.density).toInt()) }) {
            CompositionLocalProvider(LocalDensity provides zoomed) {
                ThemeMapper.Content(theme) {
                    CompositionLocalProvider(LocalNodeDecorator provides decorator) {
                        RendererWindowContext {
                            Box(Modifier.fillMaxWidth().focusProperties { onEnter = { cancelFocusChange() } }.focusGroup()) {
                                rendered?.let { frame ->
                                    RenderNode(frame, frame.rootId, controllers.commands, controllers.fields, controllers.snackbar, controllers.emit)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
