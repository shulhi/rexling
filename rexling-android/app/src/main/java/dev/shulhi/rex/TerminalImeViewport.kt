package dev.shulhi.rex

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

internal data class TerminalKeyboardFocus(val top: Float,val cursorBottom: Float,val contentBottom: Float)

/** Keep the viewport anchored while the cursor is hidden during a repaint or scrollback. */
internal class TerminalImePanState {
    private var lastFocus: TerminalKeyboardFocus?=null
    private var previousHeight=-1
    private var previousCoverage=-1
    private var previousOcclusion=-1
    private var offset=0
    private var manualOffset by mutableStateOf<Float?>(null)

    fun position(focus: TerminalKeyboardFocus?,visibleHeight: Int,covered: Int,topOcclusion: Int=0): Int {
        if(focus!=null) lastFocus=focus
        if(visibleHeight!=previousHeight || covered!=previousCoverage || topOcclusion!=previousOcclusion) {
            previousHeight=visibleHeight
            previousCoverage=covered
            previousOcclusion=topOcclusion
            offset=terminalImePan(lastFocus,visibleHeight,covered,topOcclusion)
        } else if(focus!=null && (covered>0 || topOcclusion>0)) {
            // Keep short output below the header. For longer output, only move when the cursor
            // leaves the visible viewport, because status lines can change the content heuristic.
            offset=when {
                topOcclusion>0 && focus.contentBottom<=visibleHeight-topOcclusion->-topOcclusion
                focus.top<offset+topOcclusion->floor(focus.top).toInt()-topOcclusion
                focus.cursorBottom>offset+visibleHeight->ceil(focus.cursorBottom-visibleHeight).toInt()
                else->offset
            }.coerceIn(-topOcclusion,covered)
        }
        if(covered==0 && topOcclusion==0) followCursor()
        return manualOffset?.coerceIn(-topOcclusion.toFloat(),offset.toFloat())?.roundToInt() ?: offset
    }

    /** Move only the visible part of the pane and return any drag left for terminal scrollback. */
    fun consumeDrag(pixels: Float): Float {
        val top=-previousOcclusion.coerceAtLeast(0).toFloat()
        val end=offset.toFloat()
        val before=manualOffset?.coerceIn(top,end) ?: end
        val after=(before+pixels).coerceIn(top,end)
        if(after!=before) manualOffset=after.takeIf {it<end}
        return pixels-(after-before)
    }

    fun followCursor() {manualOffset=null}
}

/** How much of the window bottom the keyboard covers above the navigation bar, in px. */
private fun androidx.compose.ui.unit.Density.keyboardCover(ime: WindowInsets,navigation: WindowInsets)=
    (ime.getBottom(this)-navigation.getBottom(this)).coerceAtLeast(0)

/**
 * Only the visible window shrinks for the IME; the PTY keeps its full-height grid.
 *
 * [keyboardChrome] is the full height of chrome shown only with the keyboard (an auto-hidden key
 * toolbar, laid out by [keyboardRevealed]), in px. It is revealed as far as the keyboard covers, so
 * counting the same amount as covered keeps one terminal height, and one PTY size, on every frame.
 * [headerHeightSaved] is the height recovered by collapsing the top bar on that same frame.
 */
@Composable
internal fun Modifier.terminalImeViewport(ime: WindowInsets,navigation: WindowInsets,keyboardChrome: ()->Int={0},
    extendsUnderNavigation: Boolean=false,headerHeightSaved: ()->Int={0},headerOverlap: ()->Int={0},
    pan: TerminalImePanState=remember {TerminalImePanState()},focus: ()->TerminalKeyboardFocus?): Modifier {
    return stableTerminalViewport(coverage={keyboardCoverage(ime,navigation,keyboardChrome,extendsUnderNavigation,headerHeightSaved)},
        topOcclusion={headerOverlap()},pan=pan,focus=focus)
}

internal fun androidx.compose.ui.unit.Density.keyboardCoverage(
    ime: WindowInsets,navigation: WindowInsets,keyboardChrome: ()->Int,extendsUnderNavigation: Boolean=false,headerHeightSaved: ()->Int={0}
): Int {
    val keyboard=keyboardCover(ime,navigation)
    val covered=if(extendsUnderNavigation) ime.getBottom(this) else keyboard
    return (covered+minOf(keyboardChrome().coerceAtLeast(0),keyboard)-headerHeightSaved()).coerceAtLeast(0)
}

@Composable
internal fun Modifier.stableTerminalViewport(panToCursor: Boolean=true,coverage: androidx.compose.ui.unit.Density.()->Int,
    topOcclusion: androidx.compose.ui.unit.Density.()->Int={0},pan: TerminalImePanState=remember(panToCursor) {TerminalImePanState()},
    focus: ()->TerminalKeyboardFocus?): Modifier {
    return layout {measurable,constraints->
        if(!constraints.hasBoundedHeight) {
            val child=measurable.measure(constraints)
            layout(child.width,child.height) {child.placeRelative(0,0)}
        } else {
            val covered=coverage().coerceAtLeast(0)
            val fullHeight=constraints.maxHeight+covered
            val child=measurable.measure(constraints.copy(minHeight=fullHeight,maxHeight=fullHeight))
            layout(child.width,constraints.maxHeight) {
                // Read cursor state during placement, not composition or measurement.
                val occlusion=topOcclusion().coerceIn(0,(constraints.maxHeight-1).coerceAtLeast(0))
                child.placeRelative(0,if(panToCursor) -pan.position(focus(),constraints.maxHeight,covered,occlusion) else occlusion)
            }
        }
    }
}

internal fun terminalImePan(focus: TerminalKeyboardFocus?,visibleHeight: Int,covered: Int,topOcclusion: Int=0): Int {
    if(focus==null) return -topOcclusion
    if(covered<=0 && topOcclusion<=0) return 0
    // Include status/help lines below the prompt when they fit alongside it.
    // A distant status bar must not push a top-of-screen cursor out of view.
    val bottom=if(focus.contentBottom-focus.top<=visibleHeight-topOcclusion) focus.contentBottom else focus.cursorBottom
    val limit=minOf(covered,(floor(focus.top).toInt()-topOcclusion).coerceAtLeast(-topOcclusion))
    // Rest below the header. Only pan into the glass when the content needs the room.
    return ceil(bottom-visibleHeight).toInt().coerceIn(-topOcclusion,limit)
}

/**
 * Shows only as much of this chrome as the keyboard currently covers, so it rides in and out with the
 * keyboard's own animation instead of switching on a visibility flag a frame or two late.
 * [onFullHeight] receives the unclipped height for [terminalImeViewport].
 */
internal fun Modifier.keyboardRevealed(ime: WindowInsets,navigation: WindowInsets,onFullHeight: (Int)->Unit): Modifier =
    clipToBounds().layout {measurable,constraints->
        val child=measurable.measure(constraints.copy(minHeight=0))
        onFullHeight(child.height)
        val shown=minOf(child.height,keyboardCover(ime,navigation))
        layout(child.width,shown) {child.placeRelative(0,0)}
    }

@Composable
internal fun KeyboardCollapsingHeader(ime: WindowInsets,navigation: WindowInsets,onHeightSaved: (Int)->Unit,
    onOverlap: ((Int)->Unit)?=null,full: (@Composable ()->Unit)?,compact: @Composable ()->Unit) {
    Layout(contents=if(full==null) listOf(compact) else listOf(full,compact),modifier=Modifier.clipToBounds()) {children,constraints->
        val compactHeader=children.last().single().measure(constraints)
        val fullHeader=if(full==null) compactHeader else children.first().single().measure(constraints)
        val difference=(fullHeader.height-compactHeader.height).coerceAtLeast(0)
        val keyboard=keyboardCover(ime,navigation)
        val saved=minOf(keyboard,difference)
        val visible=if(keyboard>0 && saved*2>=difference) compactHeader else fullHeader
        val height=fullHeader.height-saved
        // Reclaim one pixel of workspace per pixel of keyboard coverage. Once the header has
        // shrunk, spend the remaining coverage on extending the terminal behind its glass.
        val overlap=if(onOverlap!=null) (keyboard-saved).coerceIn(0,height) else 0
        onOverlap?.invoke(overlap)
        onHeightSaved(saved+overlap)
        layout(fullHeader.width,height) {visible.placeRelative(0,(height-visible.height)/2)}
    }
}

/** Keep the bottom controls in place while drawing the top of the workspace beneath the header. */
internal fun Modifier.underWorkspaceHeader(overlap: ()->Int): Modifier = layout {measurable,constraints->
    val inset=overlap()
    val child=measurable.measure(constraints.copy(minHeight=constraints.minHeight+inset,maxHeight=constraints.maxHeight+inset))
    layout(child.width,child.height-inset) {child.placeRelative(0,-inset)}
}

