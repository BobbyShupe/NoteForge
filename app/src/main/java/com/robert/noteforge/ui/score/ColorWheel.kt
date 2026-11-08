package com.robert.noteforge.ui.score

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private val HueStops = listOf(
    Color.Red,
    Color.Yellow,
    Color.Green,
    Color.Cyan,
    Color.Blue,
    Color.Magenta,
    Color.Red
)

/**
 * Hue-wheel note color picker: the outer ring selects hue, the inner square
 * selects saturation (horizontal) and value (vertical). Every touch applies
 * live via [onColor]; the Default button clears to the automatic palette
 * color ([fallback]) via `onColor(null)`.
 */
@Composable
fun ColorWheel(
    initialArgb: Int?,
    fallback: Color,
    onColor: (Int?) -> Unit,
    modifier: Modifier = Modifier
) {
    var hue by remember(initialArgb) {
        mutableFloatStateOf(
            if (initialArgb != null) {
                val hsv = FloatArray(3)
                android.graphics.Color.colorToHSV(initialArgb, hsv)
                hsv[0] / 360f
            } else {
                0.52f
            }
        )
    }
    var sat by remember(initialArgb) {
        mutableFloatStateOf(
            if (initialArgb != null) {
                val hsv = FloatArray(3)
                android.graphics.Color.colorToHSV(initialArgb, hsv)
                hsv[1]
            } else {
                1f
            }
        )
    }
    var value by remember(initialArgb) {
        mutableFloatStateOf(
            if (initialArgb != null) {
                val hsv = FloatArray(3)
                android.graphics.Color.colorToHSV(initialArgb, hsv)
                hsv[2]
            } else {
                1f
            }
        )
    }
    var custom by remember(initialArgb) { mutableStateOf(initialArgb != null) }

    val picked = Color.hsv(hue * 360f, sat.coerceIn(0f, 1f), value.coerceIn(0f, 1f))

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Canvas(
            modifier = Modifier
                .size(150.dp)
                .pointerInput(Unit) {
                    fun applyAt(pos: Offset, canvasSize: Size) {
                        val cx = canvasSize.width / 2f
                        val cy = canvasSize.height / 2f
                        val outerR = minOf(canvasSize.width, canvasSize.height) / 2f
                        val ringInner = outerR * 0.76f
                        val half = ringInner / sqrt(2f) * 0.92f
                        val dx = pos.x - cx
                        val dy = pos.y - cy
                        val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                        if (dist in ringInner - 8f..outerR + 8f) {
                            val deg = atan2(dy, dx) * 180f / PI.toFloat()
                            hue = ((deg + 360f) % 360f) / 360f
                            custom = true
                            onColor(Color.hsv(hue * 360f, sat, value).toArgb())
                        } else if (kotlin.math.abs(dx) <= half && kotlin.math.abs(dy) <= half) {
                            sat = ((dx + half) / (half * 2f)).coerceIn(0f, 1f)
                            value = (1f - (dy + half) / (half * 2f)).coerceIn(0f, 1f)
                            custom = true
                            onColor(Color.hsv(hue * 360f, sat, value).toArgb())
                        }
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        applyAt(down.position, size.toSize())
                        var last = down.position
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.all { !it.pressed }) break
                            val change = event.changes.firstOrNull { it.pressed } ?: break
                            applyAt(change.position, size.toSize())
                            if (change.position != last) change.consume()
                            last = change.position
                        }
                    }
                }
        ) {
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val cy = h / 2f
            val outerR = minOf(w, h) / 2f
            val thickness = outerR * 0.24f
            val ringInner = outerR * 0.76f
            // Hue ring.
            drawArc(
                brush = Brush.sweepGradient(HueStops),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(cx - outerR, cy - outerR),
                size = Size(outerR * 2f, outerR * 2f),
                style = Stroke(width = thickness)
            )
            // Saturation/value square inscribed in the ring.
            val half = ringInner / sqrt(2f) * 0.92f
            val sqTopLeft = Offset(cx - half, cy - half)
            val sqSize = Size(half * 2f, half * 2f)
            val fullHue = Color.hsv(hue * 360f, 1f, 1f)
            drawRect(
                brush = Brush.horizontalGradient(listOf(Color.White, fullHue)),
                topLeft = sqTopLeft,
                size = sqSize
            )
            drawRect(
                brush = Brush.verticalGradient(listOf(Color.Transparent, Color.Black)),
                topLeft = sqTopLeft,
                size = sqSize
            )
            drawRect(
                color = Color.Gray,
                topLeft = sqTopLeft,
                size = sqSize,
                style = Stroke(width = 2f)
            )
            // Hue marker on the ring.
            val angle = hue * 360f * PI.toFloat() / 180f
            val midR = (outerR + ringInner) / 2f
            val hx = cx + cos(angle) * midR
            val hy = cy + sin(angle) * midR
            drawCircle(Color.White, radius = 9f, center = Offset(hx, hy))
            drawCircle(picked, radius = 6f, center = Offset(hx, hy))
            // SV marker in the square.
            drawCircle(
                Color.White,
                radius = 8f,
                center = Offset(cx - half + sat * half * 2f, cy - half + (1f - value) * half * 2f)
            )
            drawCircle(
                picked,
                radius = 5f,
                center = Offset(cx - half + sat * half * 2f, cy - half + (1f - value) * half * 2f)
            )
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .border(2.dp, MaterialTheme.colorScheme.outline)
                    .padding(2.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(if (custom) picked else fallback)
                )
            }
            Text(
                if (custom) "Custom" else "Auto",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(
                onClick = {
                    custom = false
                    onColor(null)
                }
            ) { Text("Default") }
        }
    }
}

private fun androidx.compose.ui.unit.IntSize.toSize(): Size =
    Size(width.toFloat(), height.toFloat())
