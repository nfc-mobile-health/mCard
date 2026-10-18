package com.example.mcard.ui

import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

enum class TokenState { Ready, Working, Done, Problem }

class MapleShape(private val lobes: Int = 5, private val amp: Float = 0.085f) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        return Outline.Generic(maplePath(size, 1f, lobes, amp))
    }
}

@Composable
fun MapleToken(
    state: TokenState,
    size: Dp,
    description: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val animationsOff = Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    ) == 0f
    val fill by animateColorAsState(
        targetValue = when (state) {
            TokenState.Ready, TokenState.Done -> MapleColors.Pine
            TokenState.Working -> MapleColors.Amber
            TokenState.Problem -> MapleColors.Red
        },
        animationSpec = tween(300),
        label = "token fill",
    )
    val infinite = rememberInfiniteTransition(label = "token rings")
    val pulse by infinite.animateFloat(
        initialValue = 0f,
        targetValue = if (animationsOff) 0f else 1f,
        animationSpec = infiniteRepeatable(tween(1600), RepeatMode.Restart),
        label = "token pulse",
    )
    val strokePx = with(LocalDensity.current) { 3.dp.toPx() }
    val ringColor = if (state == TokenState.Working) MapleColors.Amber else MapleColors.Mist2
    val hasRings = state == TokenState.Ready || state == TokenState.Working

    Box(
        modifier = modifier
            .size(size)
            .semantics {
                contentDescription = description
                liveRegion = LiveRegionMode.Polite
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size)) {
            if (hasRings) {
                drawPath(
                    path = maplePath(this.size, scale = 0.72f + pulse * 0.05f),
                    color = ringColor.copy(alpha = 0.5f),
                    style = Stroke(width = strokePx, cap = StrokeCap.Round),
                )
                drawPath(
                    path = maplePath(this.size, scale = 0.84f + pulse * 0.05f),
                    color = ringColor,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round),
                )
            }
            drawPath(
                path = maplePath(this.size, scale = 0.61f),
                color = fill,
            )
            drawTokenSymbol(
                state = state,
                color = if (state == TokenState.Working) MapleColors.Ink else MapleColors.White,
            )
        }
    }
}

private fun maplePath(size: Size, scale: Float, lobes: Int = 5, amp: Float = 0.085f): Path {
    val center = Offset(size.width / 2f, size.height / 2f)
    val radius = min(size.width, size.height) * scale / 2f / (1f + amp)
    val path = Path()
    val steps = 170
    for (i in 0..steps) {
        val theta = 2.0 * PI * i / steps
        val r = radius * (1f + amp * cos(lobes * (theta + PI / 2)).toFloat())
        val x = center.x + r * cos(theta).toFloat()
        val y = center.y + r * sin(theta).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTokenSymbol(
    state: TokenState,
    color: Color,
) {
    val center = Offset(size.width / 2f, size.height / 2f)
    val unit = size.minDimension / 12f
    val stroke = Stroke(width = unit * 0.45f, cap = StrokeCap.Round)
    when (state) {
        TokenState.Done -> {
            drawLine(color, center + Offset(-2.2f * unit, 0f), center + Offset(-0.5f * unit, 1.7f * unit), strokeWidth = stroke.width, cap = StrokeCap.Round)
            drawLine(color, center + Offset(-0.5f * unit, 1.7f * unit), center + Offset(2.5f * unit, -1.7f * unit), strokeWidth = stroke.width, cap = StrokeCap.Round)
        }
        TokenState.Problem -> {
            drawLine(color, center + Offset(0f, -2.4f * unit), center + Offset(0f, 0.9f * unit), strokeWidth = stroke.width, cap = StrokeCap.Round)
            drawCircle(color, unit * 0.35f, center + Offset(0f, 2.3f * unit))
        }
        TokenState.Ready, TokenState.Working -> {
            drawArc(color, 220f, 100f, false, topLeft = center + Offset(-3f * unit, -2.2f * unit), size = Size(6f * unit, 6f * unit), style = stroke)
            drawArc(color, 225f, 90f, false, topLeft = center + Offset(-2f * unit, -1.2f * unit), size = Size(4f * unit, 4f * unit), style = stroke)
            drawArc(color, 235f, 70f, false, topLeft = center + Offset(-1f * unit, -0.2f * unit), size = Size(2f * unit, 2f * unit), style = stroke)
        }
    }
}
