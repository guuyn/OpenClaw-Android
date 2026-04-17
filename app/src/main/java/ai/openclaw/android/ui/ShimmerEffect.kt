package ai.openclaw.android.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Creates an animated shimmer gradient brush.
 *
 * Usage:
 * ```
 * val shimmer = rememberShimmerBrush()
 * Box(modifier = Modifier.background(shimmer))
 * ```
 */
@Composable
fun rememberShimmerBrush(
    shimmerColor: Color = Color.LightGray.copy(alpha = 0.6f),
    baseColor: Color = Color.DarkGray.copy(alpha = 0.3f),
    durationMillis: Int = 1000,
    angle: Float = 45f
): Brush {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val shimmerAnimation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerProgress"
    )

    val tan = kotlin.math.tan(Math.toRadians(angle.toDouble())).toFloat()

    return Brush.linearGradient(
        colors = listOf(
            baseColor,
            shimmerColor,
            baseColor
        ),
        start = Offset(x = shimmerAnimation - 0.5f * tan, y = 0f),
        end = Offset(x = shimmerAnimation + 0.5f * tan, y = 1f)
    )
}

/** Shimmer placeholder box */
@Composable
fun ShimmerBox(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 8.dp,
    shimmerColor: Color = Color.LightGray.copy(alpha = 0.6f),
    baseColor: Color = Color.DarkGray.copy(alpha = 0.3f)
) {
    val brush = rememberShimmerBrush(shimmerColor, baseColor)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(brush)
    )
}

/** Shimmer placeholder line (for text lines) */
@Composable
fun ShimmerLine(
    modifier: Modifier = Modifier,
    height: Dp = 12.dp,
    shimmerColor: Color = Color.LightGray.copy(alpha = 0.6f),
    baseColor: Color = Color.DarkGray.copy(alpha = 0.3f)
) {
    ShimmerBox(
        modifier = modifier.height(height),
        cornerRadius = 4.dp,
        shimmerColor = shimmerColor,
        baseColor = baseColor
    )
}

/** Shimmer placeholder circle (for avatars) */
@Composable
fun ShimmerCircle(
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    shimmerColor: Color = Color.LightGray.copy(alpha = 0.6f),
    baseColor: Color = Color.DarkGray.copy(alpha = 0.3f)
) {
    val brush = rememberShimmerBrush(shimmerColor, baseColor)
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(brush)
    )
}

/** Shimmer placeholder text block */
@Composable
fun ShimmerText(
    modifier: Modifier = Modifier,
    height: Dp = 16.dp,
    shimmerColor: Color = Color.LightGray.copy(alpha = 0.6f),
    baseColor: Color = Color.DarkGray.copy(alpha = 0.3f)
) {
    ShimmerLine(
        modifier = modifier,
        height = height,
        shimmerColor = shimmerColor,
        baseColor = baseColor
    )
}

// ==================== Demo ====================

@Composable
fun ShimmerDemo(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Simulated AI message skeleton
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            ShimmerCircle(size = 32.dp)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ShimmerLine(modifier = Modifier.fillMaxWidth(0.8f))
                ShimmerLine(modifier = Modifier.fillMaxWidth(0.6f))
                ShimmerLine(modifier = Modifier.fillMaxWidth(0.9f))
            }
        }

        // Simulated user message skeleton
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            ShimmerBox(
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .height(40.dp),
                cornerRadius = 16.dp
            )
        }

        // Simulated A2UI card skeleton
        ShimmerBox(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp),
            cornerRadius = 12.dp
        )

        // Simulated AI thinking skeleton
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            ShimmerCircle(size = 32.dp)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ShimmerLine(modifier = Modifier.fillMaxWidth(0.5f))
                ShimmerLine(modifier = Modifier.fillMaxWidth(0.3f))
            }
        }
    }
}
