package com.tristinbaker.inkshelf.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.lazy.LazyColumnMMD
import com.tristinbaker.inkshelf.core.eink.ScrollMode
import com.tristinbaker.inkshelf.ui.theme.GrayRamp
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Provided once at the app root, so no screen has to thread the setting through. */
val LocalScrollMode = staticCompositionLocalOf { ScrollMode.PAGE }

/**
 * In MMD 1.0.2, `scrollStep` is a row count, not a page: a value of 0 makes the
 * list follow the finger pixel by pixel, and N jumps exactly N rows per swipe.
 * Rows here vary in height (letter bars, covers, wrapped titles), so no fixed
 * N lands on page boundaries.
 */
private const val SMOOTH_SCROLL_STEP = 0

/**
 * Sized to match the MMD bar it replaces, measured off the panel: a 15x11dp
 * arrow, an 8dp track. The arrow's touch target is the full width and
 * [ARROW_TARGET] tall, far bigger than the glyph, because a fingertip on this
 * screen covers more than the triangle does.
 */
private val SCROLLBAR_WIDTH = 40.dp
private val ARROW_TARGET = 44.dp
private val ARROW_WIDTH = 15.dp
private val ARROW_HEIGHT = 11.dp
private val TRACK_WIDTH = 8.dp
private val TRACK_STROKE = 1.5.dp
private val THUMB_MIN = 16.dp

/**
 * Every list in the app. In [ScrollMode.SMOOTH] a swipe follows the finger; in
 * [ScrollMode.PAGE] it is taken over before the list sees it and turned into
 * exactly one page turn when the finger lifts.
 *
 * MMD's own scrollbar is switched off and drawn here instead. Its arrows move
 * by `scrollStep` rows, which is 0 for the smooth drag every list needs, so they
 * were drawn but did nothing. These turn one page, in either mode, and a long
 * press jumps to the neighbouring entry of [sectionStarts]: the item index of
 * each letter bar, ascending. Lists without sections jump to the ends instead.
 */
@Composable
fun InkLazyColumn(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    sectionStarts: List<Int> = emptyList(),
    content: LazyListScope.() -> Unit,
) {
    val paged = LocalScrollMode.current == ScrollMode.PAGE
    val scope = rememberCoroutineScope()
    val scrollable by remember(state) {
        derivedStateOf { state.canScrollForward || state.canScrollBackward }
    }
    Row(modifier = if (paged) modifier.pageTurns(state, scope) else modifier) {
        LazyColumnMMD(
            modifier = Modifier.weight(1f),
            state = state,
            scrollStep = SMOOTH_SCROLL_STEP,
            isScrollbarVisible = false,
            content = content,
        )
        if (scrollable) PageScrollbar(state, scope, sectionStarts)
    }
}

@Composable
private fun PageScrollbar(state: LazyListState, scope: CoroutineScope, sectionStarts: List<Int>) {
    fun jump(forward: Boolean) = scope.launch {
        val target = sectionTarget(
            starts = sectionStarts,
            first = state.firstVisibleItemIndex,
            firstOffset = state.firstVisibleItemScrollOffset,
            lastIndex = state.layoutInfo.totalItemsCount - 1,
            forward = forward,
        )
        state.scrollToItem(target)
    }

    Column(
        modifier = Modifier
            .width(SCROLLBAR_WIDTH)
            .fillMaxHeight(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PageArrow(
            up = true,
            onClick = { scope.launch { state.turnPage(forward = false) } },
            onLongClick = { jump(forward = false) },
        )
        Canvas(
            modifier = Modifier
                .weight(1f)
                .width(SCROLLBAR_WIDTH)
                .pointerInput(state) {
                    // A tap on the track jumps to that point in the list, as
                    // MMD's did, so the thumb can still be used to get far fast.
                    detectTapGestures { tap ->
                        val total = state.layoutInfo.totalItemsCount
                        if (total == 0) return@detectTapGestures
                        val fraction = (tap.y / size.height).coerceIn(0f, 1f)
                        scope.launch {
                            state.scrollToItem((fraction * total).toInt().coerceAtMost(total - 1))
                        }
                    }
                },
        ) {
            // Read in the draw phase only, so scrolling redraws the thumb without
            // recomposing the list around it.
            val info = state.layoutInfo
            val total = info.totalItemsCount.coerceAtLeast(1)
            val visible = info.visibleItemsInfo.size.coerceIn(1, total)
            val stroke = TRACK_STROKE.toPx()
            val trackWidth = TRACK_WIDTH.toPx()
            val left = (size.width - trackWidth) / 2
            val radius = CornerRadius(trackWidth / 2)
            drawRoundRect(
                color = GrayRamp.g0,
                topLeft = Offset(left + stroke / 2, stroke / 2),
                size = Size(trackWidth - stroke, size.height - stroke),
                cornerRadius = radius,
                style = Stroke(width = stroke),
            )
            val thumb = (size.height * visible / total).coerceIn(THUMB_MIN.toPx(), size.height)
            val travel = size.height - thumb
            val progress = when {
                !state.canScrollForward -> 1f
                total <= visible -> 0f
                else -> (state.firstVisibleItemIndex.toFloat() / (total - visible)).coerceIn(0f, 1f)
            }
            drawRoundRect(
                color = GrayRamp.g0,
                topLeft = Offset(left, travel * progress),
                size = Size(trackWidth, thumb),
                cornerRadius = radius,
            )
        }
        PageArrow(
            up = false,
            onClick = { scope.launch { state.turnPage(forward = true) } },
            onLongClick = { jump(forward = true) },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PageArrow(up: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(ARROW_TARGET)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                // No ripple: a grey wash fading in and out is two extra partial
                // refreshes for a tap whose result is a whole new page anyway.
                indication = null,
                role = Role.Button,
                onClickLabel = if (up) "Page up" else "Page down",
                onLongClickLabel = if (up) "Previous letter" else "Next letter",
                onLongClick = onLongClick,
                onClick = onClick,
            )
            .padding(vertical = 8.dp),
        contentAlignment = if (up) Alignment.TopCenter else Alignment.BottomCenter,
    ) {
        Canvas(modifier = Modifier.size(ARROW_WIDTH, ARROW_HEIGHT)) {
            val path = Path().apply {
                if (up) {
                    moveTo(size.width / 2, 0f)
                    lineTo(size.width, size.height)
                    lineTo(0f, size.height)
                } else {
                    moveTo(0f, 0f)
                    lineTo(size.width, 0f)
                    lineTo(size.width / 2, size.height)
                }
                close()
            }
            drawPath(path, color = GrayRamp.g0)
        }
    }
}

/**
 * Watches in the Initial pass, ahead of the list and its rows. Below touch slop
 * nothing is touched, so a tap still reaches the row under it. Past slop every
 * change is consumed, which cancels the row's click and starves the list's own
 * drag handling, so the content does not move until the single jump on lift.
 */
private fun Modifier.pageTurns(state: LazyListState, scope: CoroutineScope): Modifier =
    pointerInput(state) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var travel = 0f
            var turning = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                travel += change.positionChangeIgnoreConsumed().y
                if (!turning && abs(travel) > viewConfiguration.touchSlop) turning = true
                if (turning) event.changes.forEach { it.consume() }
                if (!change.pressed) break
            }
            // Finger moving up pulls the content up: forward.
            if (turning) scope.launch { state.turnPage(forward = travel < 0) }
        }
    }

/**
 * Forward puts the row the bottom edge cut through at the top, so the line you
 * were reading is where the eye goes next. Back moves one viewport up, then
 * steps onto the next whole row so the page starts on a clean edge. A row taller
 * than the screen has no edge to land on and moves by a viewport instead.
 */
internal suspend fun LazyListState.turnPage(forward: Boolean) {
    val info = layoutInfo
    val viewport = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
    if (forward) {
        if (!canScrollForward) return
        val visible = info.visibleItemsInfo
        val cut = visible.lastOrNull { it.offset + it.size > info.viewportEndOffset }
        val target = when {
            cut == null -> visible.lastOrNull()?.index?.plus(1)
            cut.index > firstVisibleItemIndex -> cut.index
            else -> null
        }
        if (target != null) {
            scrollToItem(target.coerceAtMost(info.totalItemsCount - 1))
        } else {
            scrollBy(viewport)
        }
    } else {
        if (!canScrollBackward) return
        val start = firstVisibleItemIndex
        scrollBy(-viewport)
        val next = firstVisibleItemIndex + 1
        if (firstVisibleItemScrollOffset > 0 && next < start) scrollToItem(next)
    }
}

/**
 * Where a long press on an arrow lands, as an item index.
 *
 * Down goes to the next bar below the top of the screen. Up goes to the start
 * of the section being read, or, if the screen already starts exactly on a
 * bar, to the one before it, so repeated presses keep walking back. With no bar
 * left in that direction it goes to the very top or bottom of the list.
 */
internal fun sectionTarget(
    starts: List<Int>,
    first: Int,
    firstOffset: Int,
    lastIndex: Int,
    forward: Boolean,
): Int = if (forward) {
    starts.firstOrNull { it > first } ?: lastIndex
} else {
    starts.lastOrNull { it < first || (it == first && firstOffset > 0) } ?: 0
}.coerceIn(0, lastIndex.coerceAtLeast(0))
