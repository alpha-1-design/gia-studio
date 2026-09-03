package com.giastudio.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RectangleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.giastudio.app.ui.theme.Neo
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------
// Square neobrutal surface
// ---------------------------------------------------------------------------

@Composable
fun NeoSurface(
    modifier: Modifier = Modifier,
    background: Color = Neo.PaperRaised,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .shadow(Neo.Shadow, RectangleShape, spotColor = Neo.Ink, ambientColor = Neo.Ink)
            .background(background)
            .border(Neo.Border, Neo.Ink)
            .padding(10.dp)
    ) {
        content()
    }
}

// ---------------------------------------------------------------------------
// Buttons
// ---------------------------------------------------------------------------

@Composable
fun NeoButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    container: Color = Neo.Ink,
    contentColor: Color = Neo.Paper,
    enabled: Boolean = true,
    borderColor: Color = Neo.Ink,
    fontSize: TextUnit = 14.sp,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier
            .shadow(if (pressed) 2.dp else Neo.Shadow, RectangleShape, spotColor = Neo.Ink, ambientColor = Neo.Ink)
            .background(if (enabled) container else Neo.Disabled)
            .border(Neo.Border, borderColor)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .then(if (pressed) Modifier.offset(y = 2.dp) else Modifier)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (enabled) contentColor else Neo.Ink.copy(alpha = 0.6f),
            fontSize = fontSize,
            fontWeight = FontWeight.Black,
            maxLines = 1,
        )
    }
}

@Composable
fun NeoIconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    container: Color = Neo.PaperRaised,
    contentColor: Color = Neo.Ink,
    enabled: Boolean = true,
    iconSize: Int = 22,
    tint: Color? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier
            .shadow(if (pressed) 1.dp else 3.dp, RectangleShape, spotColor = Neo.Ink, ambientColor = Neo.Ink)
            .background(if (enabled) container else Neo.Disabled)
            .border(Neo.Border, Neo.Ink)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .then(if (pressed) Modifier.offset(x = 1.dp, y = 1.dp) else Modifier)
            .size(42.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = tint ?: contentColor,
            modifier = Modifier.size(iconSize.dp),
        )
    }
}

/** Square toggle chip (mute/solo/arm/fx). */
@Composable
fun NeoChip(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    activeColor: Color = Neo.Ink,
    textColor: Color = Neo.Paper,
) {
    Box(
        modifier
            .background(if (active) activeColor else Neo.PaperRaised)
            .border(2.dp, Neo.Ink)
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (active) textColor else Neo.Ink,
            fontSize = 11.sp,
            fontWeight = FontWeight.Black,
            maxLines = 1,
        )
    }
}

// ---------------------------------------------------------------------------
// Sliders (square, touch-sized)
// ---------------------------------------------------------------------------

@Composable
fun NeoSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    min: Float = 0f,
    max: Float = 1f,
    fill: Color = Neo.Ink,
    trackColor: Color = Neo.Disabled,
) {
    var widthPx by remember { mutableStateOf(0) }
    val frac = ((value - min) / (max - min).coerceAtLeast(0.0001f)).coerceIn(0f, 1f)

    fun drag(xPx: Float) {
        if (widthPx <= 0) return
        val f = (xPx / widthPx).coerceIn(0f, 1f)
        onValueChange(min + (max - min) * f)
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(30.dp)
            .onSizeChanged { widthPx = it.width }
            .pointerInput(widthPx) {
                detectTapGestures { drag(it.x) }
            }
            .pointerInput(widthPx) {
                detectDragGestures { change, _ ->
                    change.consume()
                    drag(change.position.x)
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(Modifier.fillMaxWidth().height(10.dp)) {
            Box(
                Modifier
                    .weight(frac.coerceAtLeast(0.001f))
                    .fillMaxHeight()
                    .background(fill)
            )
            Box(
                Modifier
                    .weight((1f - frac).coerceIn(0.001f, 1f))
                    .fillMaxHeight()
                    .background(trackColor)
            )
        }
        val thumb = 18
        Box(
            Modifier
                .offset { IntOffset((widthPx * frac - thumb / 2).roundToInt(), 0) }
                .size(thumb.dp)
                .background(Neo.PaperRaised)
                .border(3.dp, Neo.Ink)
        )
    }
}

@Composable
fun NeoLabeledSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    min: Float = 0f,
    max: Float = 1f,
    readout: String,
    help: String? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Black,
            color = Neo.Ink,
            modifier = Modifier.width(64.dp),
            maxLines = 1,
        )
        NeoSlider(value = value, onValueChange = onValueChange, min = min, max = max, modifier = Modifier.weight(1f))
        Text(
            readout,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = Neo.InkSoft,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(52.dp),
        )
        if (help != null) Explain(help)
    }
}

/** Vertical fader (mixer). value maps to 0..max. */
@Composable
fun NeoFader(
    value: Float,
    onValueChange: (Float) -> Unit,
    max: Float = 1f,
    modifier: Modifier = Modifier,
    fill: Color = Neo.Ink,
) {
    var heightPx by remember { mutableStateOf(0) }
    val frac = (value / max.coerceAtLeast(0.0001f)).coerceIn(0f, 1f)

    fun drag(yPx: Float) {
        if (heightPx <= 0) return
        val f = (1f - yPx / heightPx).coerceIn(0f, 1f)
        onValueChange(f * max)
    }


    Box(
        modifier
            .width(44.dp)
            .height(160.dp)
            .onSizeChanged { heightPx = it.height }
            .pointerInput(heightPx) {
                detectTapGestures { drag(it.y) }
            }
            .pointerInput(heightPx) {
                detectDragGestures { change, _ ->
                    change.consume()
                    drag(change.position.y)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().weight((1f - frac).coerceIn(0.001f, 1f)).background(Neo.Disabled))
            Box(Modifier.fillMaxWidth().weight(frac.coerceAtLeast(0.001f)).background(fill))
        }
        val handle = 30
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .offset { IntOffset(0, (heightPx * (1f - frac) - handle / 2).roundToInt().coerceAtLeast(0)) }
                .size(handle.dp)
                .background(Neo.PaperRaised)
                .border(3.dp, Neo.Ink)
        )
    }
}

// ---------------------------------------------------------------------------
// Meters
// ---------------------------------------------------------------------------

/** Horizontal level meter for recording/monitoring feedback. */
@Composable
fun NeoMeter(level: Float, modifier: Modifier = Modifier, height: Int = 10) {
    val lv = level.coerceIn(0f, 1f)
    Row(
        modifier
            .border(2.dp, Neo.Ink)
            .background(Neo.PaperRaised)
            .padding(2.dp),
    ) {
        val bars = 18
        val lit = (lv * bars).toInt()
        repeat(bars) { i ->
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(horizontal = 1.dp)
                    .background(
                        when {
                            i >= lit -> Neo.Disabled.copy(alpha = 0.5f)
                            i < bars * 0.66 -> Neo.Ok
                            i < bars * 0.9 -> Neo.AccentHot
                            else -> Neo.Record
                        }
                    )
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Inline help ("?") bubbles
// ---------------------------------------------------------------------------

@Composable
fun Explain(
    text: String,
    modifier: Modifier = Modifier,
    open: Boolean = false,
    onToggle: ((Boolean) -> Unit)? = null,
) {
    var internalOpen by remember { mutableStateOf(open) }
    val isOpen = onToggle?.let { _ -> open } ?: internalOpen
    val toggle: (Boolean) -> Unit = { v ->
        if (onToggle != null) onToggle(v) else internalOpen = v
    }
    Column(modifier) {
        Box(
            Modifier
                .size(26.dp)
                .background(if (isOpen) Neo.Accent else Neo.PaperRaised)
                .border(2.dp, Neo.Ink)
                .clickable { toggle(!isOpen) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.HelpOutline,
                contentDescription = "Help",
                tint = Neo.Ink,
                modifier = Modifier.size(15.dp),
            )
        }
        if (isOpen) {
            Box(
                Modifier
                    .padding(top = 4.dp)
                    .fillMaxWidth()
                    .background(Neo.Accent.copy(alpha = 0.55f))
                    .border(2.dp, Neo.Ink)
                    .padding(6.dp)
            ) {
                Text(text, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Neo.Ink)
            }
        }
    }
}

/** Small uppercase label used on headers. */
@Composable
fun NeoLabel(text: String, modifier: Modifier = Modifier, color: Color = Neo.InkSoft) {
    Text(
        text.uppercase(),
        modifier = modifier,
        fontSize = 10.sp,
        fontWeight = FontWeight.Black,
        letterSpacing = 1.2.sp,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
