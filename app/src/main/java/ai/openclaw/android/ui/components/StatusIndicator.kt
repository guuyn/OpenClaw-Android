package ai.openclaw.android.ui.components

import android.os.Build
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ai.openclaw.android.ui.theme.SciFiError
import ai.openclaw.android.ui.theme.SciFiPrimary
import ai.openclaw.android.ui.theme.SciFiSecondary

enum class ConnectionState { ONLINE, THINKING, OFFLINE }

@Composable
fun StatusIndicator(
    state: ConnectionState,
    size: Dp = 8.dp,
    modifier: Modifier = Modifier
) {
    val color = when (state) {
        ConnectionState.ONLINE -> SciFiPrimary
        ConnectionState.THINKING -> SciFiSecondary
        ConnectionState.OFFLINE -> SciFiError
    }

    val infiniteTransition = rememberInfiniteTransition()
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (state == ConnectionState.OFFLINE) 400 else 1500
            ),
            repeatMode = RepeatMode.Reverse
        )
    )

    Box(
        modifier = modifier
            .size(size)
            .background(color.copy(alpha = pulse), CircleShape)
            .then(
                if (state == ConnectionState.THINKING && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Modifier.shadow(
                        elevation = 4.dp,
                        shape = CircleShape,
                        ambientColor = color.copy(alpha = 0.5f),
                        spotColor = color.copy(alpha = 0.5f)
                    )
                } else Modifier
            )
    )
}
