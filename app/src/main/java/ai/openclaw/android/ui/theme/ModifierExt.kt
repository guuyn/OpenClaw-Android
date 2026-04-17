package ai.openclaw.android.ui.theme

import android.os.Build
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape

fun Modifier.sciFiGlow(
    color: Color = SciFiPrimary,
    radius: Dp = 8.dp,
    shape: Shape = RoundedCornerShape(12.dp)
): Modifier = this.then(
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        androidx.compose.ui.draw.shadow(
            elevation = radius,
            shape = shape,
            ambientColor = color.copy(alpha = 0.5f),
            spotColor = color.copy(alpha = 0.5f)
        )
    } else {
        this
    }
)

fun Modifier.glassmorphism(
    blurRadius: Dp = 20.dp
): Modifier = this.then(
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        blur(blurRadius)
    } else {
        this
    }
)

fun Modifier.neonBorder(
    color: Color = SciFiPrimary,
    focused: Boolean = false,
    unfocusedColor: Color = SciFiOutline,
    borderWidth: Dp = 1.dp,
    cornerRadius: Dp = 24.dp
): Modifier = this.drawBehind {
    val borderColor = if (focused) color else unfocusedColor
    drawRoundRect(
        color = borderColor,
        cornerRadius = CornerRadius(cornerRadius.toPx()),
        style = Stroke(width = borderWidth.toPx())
    )
    if (focused) {
        drawRoundRect(
            color = color.copy(alpha = 0.15f),
            cornerRadius = CornerRadius(cornerRadius.toPx()),
            style = Stroke(width = (borderWidth * 3).toPx())
        )
    }
}

fun Modifier.gradientDivider(
    color: Color = SciFiPrimary,
    alpha: Float = 0.3f,
    thickness: Dp = 1.dp
): Modifier = this.drawBehind {
    drawLine(
        brush = Brush.horizontalGradient(
            colors = listOf(
                Color.Transparent,
                color.copy(alpha = alpha),
                Color.Transparent
            )
        ),
        start = Offset(0f, size.height / 2),
        end = Offset(size.width, size.height / 2),
        strokeWidth = thickness.toPx()
    )
}
