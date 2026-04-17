# Sci-Fi UI Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Redesign OpenClaw Android app UI with sci-fi aesthetic — neon cyan accents, glassmorphism, animated effects — across all screens.

**Architecture:** Hybrid approach. Material 3 theme handles colors/typography/shapes (80%). Custom composables in new `ui/components/` directory handle unique sci-fi effects: glow, glassmorphism, scan lines, typing cursor, energy bar, particle background, status indicator (20%).

**Tech Stack:** Kotlin, Jetpack Compose, Material 3, Compose Animation

---

## File Structure

| Operation | File | Responsibility |
|-----------|------|----------------|
| Extend | `ui/theme/Color.kt` | Add missing colors (disabled fix, light mode bubbles) |
| Modify | `ui/theme/Theme.kt` | Remove dynamic color toggle, simplify to fixed sci-fi scheme |
| Modify | `ui/theme/Type.kt` | Add monospace accent style |
| Create | `ui/theme/ModifierExt.kt` | Reusable sci-fi modifier extensions (glow, glass, neon border) |
| Create | `ui/components/StatusIndicator.kt` | Connection state dot (online/thinking/offline) |
| Create | `ui/components/ScanLineOverlay.kt` | AI generating scan line effect |
| Create | `ui/components/TypingCursor.kt` | Blinking cursor for streaming responses |
| Create | `ui/components/EnergyBar.kt` | Input bar focus energy line |
| Create | `ui/components/ParticleBackground.kt` | Static star-field overlay |
| Create | `ui/components/HapticHelper.kt` | Haptic feedback utility |
| Modify | `ChatScreen.kt` | Top bar, message bubbles, input bar, typing indicator, context menu |
| Modify | `ui/TypingIndicator.kt` | Replace with 3-dot pulse animation |
| Modify | `ui/A2UICards.kt` | Restyle card container with glassmorphism + top accent border |
| Modify | `ui/SettingsScreen.kt` | Group card style, switch colors, input fields |
| Modify | `notification/NotificationScreen.kt` | Card style, unread indicator |
| Create | `res/drawable/bg_stars.xml` | Static particle/star drawable |
| Create | `res/drawable/bg_grid.xml` | Hex grid texture drawable |

All paths relative to `app/src/main/java/ai/openclaw/android/` except drawables which are in `app/src/main/res/`.

---

### Task 1: Update Color.kt — Add missing colors

**Files:**
- Modify: `app/src/main/java/ai/openclaw/android/ui/theme/Color.kt`

- [ ] **Step 1: Add missing light mode bubble colors**

The existing `Color.kt` already has the core sci-fi palette. Add the two missing light-mode bubble colors after line 49:

```kotlin
// Add after SciFiLightAiBubbleBorder (line 49):

val SciFiLightUserBubbleStart = Color(0xFF059669)  // 亮色模式用户气泡起始
val SciFiLightUserBubbleEnd   = Color(0xFF4CC9F0)  // 亮色模式用户气泡结束
```

- [ ] **Step 2: Verify existing colors match spec**

Confirm these colors already exist and match the spec values:
- `SciFiBackground` = `Color(0xFF0A0E1A)` ✅
- `SciFiDisabled` = `Color(0xFF7D8FA6)` ✅ (WCAG AA fix already applied)
- `SciFiGlow` = `Color(0x4006D6A0)` ✅
- All other colors per spec — already present

No other changes needed in Color.kt.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/ai/openclaw/android/ui/theme/Color.kt
git commit -m "feat: add light-mode bubble colors to sci-fi palette"
```

---

### Task 2: Update Theme.kt — Simplify to fixed sci-fi scheme

**Files:**
- Modify: `app/src/main/java/ai/openclaw/android/ui/theme/Theme.kt`

- [ ] **Step 1: Simplify OpenClawTheme composable**

Replace the theme function (lines 72-106) to remove dynamic color support and simplify the API. The `OpenClawTheme` function should always use the sci-fi dark scheme:

```kotlin
@Composable
fun OpenClawTheme(
    content: @Composable () -> Unit
) {
    val colorScheme = SciFiDarkColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = SciFiBackground.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
```

Keep the `SciFiDarkColorScheme`, `SciFiLightColorScheme`, `LegacyDarkColorScheme`, and `LegacyLightColorScheme` definitions as-is (lines 22-70) in case they're needed later — only simplify the public API.

- [ ] **Step 2: Build to verify**

Run: `JAVA_HOME="E:/Program Files/Android/Android Studio/jbr" ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/ai/openclaw/android/ui/theme/Theme.kt
git commit -m "refactor: simplify OpenClawTheme to fixed sci-fi dark scheme"
```

---

### Task 3: Update Type.kt — Add monospace accent style

**Files:**
- Modify: `app/src/main/java/ai/openclaw/android/ui/theme/Type.kt`

- [ ] **Step 1: Add monospace accent typography**

Replace the entire file content with:

```kotlin
package ai.openclaw.android.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Typography = Typography(
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 16.sp
    )
)

val MonospaceAccent = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Normal,
    fontSize = 10.sp,
    lineHeight = 14.sp,
    letterSpacing = 0.sp
)
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/ai/openclaw/android/ui/theme/Type.kt
git commit -m "feat: add monospace accent typography for data-style decorative text"
```

---

### Task 4: Create ModifierExt.kt — Sci-fi modifier extensions

**Files:**
- Create: `app/src/main/java/ai/openclaw/android/ui/theme/ModifierExt.kt`

- [ ] **Step 1: Create modifier extension file**

```kotlin
package ai.openclaw.android.ui.theme

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

fun Modifier.sciFiGlow(
    color: Color = SciFiPrimary,
    radius: Dp = 8.dp,
    shape: Shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
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
    blurRadius: Dp = 20.dp,
    fallbackAlpha: Float = 0.8f
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
```

- [ ] **Step 2: Build to verify**

Run: `JAVA_HOME="E:/Program Files/Android/Android Studio/jbr" ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/ai/openclaw/android/ui/theme/ModifierExt.kt
git commit -m "feat: add sci-fi modifier extensions (glow, glass, neon border, gradient divider)"
```

---

### Task 5: Create StatusIndicator.kt

**Files:**
- Create: `app/src/main/java/ai/openclaw/android/ui/components/StatusIndicator.kt`

- [ ] **Step 1: Create status indicator composable**

```kotlin
package ai.openclaw.android.ui.components

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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ai.openclaw.android.ui.theme.SciFiPrimary
import ai.openclaw.android.ui.theme.SciFiSecondary
import ai.openclaw.android.ui.theme.SciFiError

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
                if (state == ConnectionState.THINKING) {
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
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/ai/openclaw/android/ui/components/StatusIndicator.kt
git commit -m "feat: add StatusIndicator with online/thinking/offline states"
```

---

### Task 6: Create ScanLineOverlay.kt

**Files:**
- Create: `app/src/main/java/ai/openclaw/android/ui/components/ScanLineOverlay.kt`

- [ ] **Step 1: Create scan line overlay composable**

```kotlin
package ai.openclaw.android.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ai.openclaw.android.ui.theme.SciFiPrimary

@Composable
fun ScanLineOverlay(
    isGenerating: Boolean,
    modifier: Modifier = Modifier,
    color: Color = SciFiPrimary.copy(alpha = 0.25f)
) {
    if (!isGenerating) return

    val infiniteTransition = rememberInfiniteTransition()
    val scanOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                val y = size.height * scanOffset
                drawLine(
                    brush = Brush.horizontalGradient(
                        colors = listOf(Color.Transparent, color, Color.Transparent)
                    ),
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 2.dp.toPx()
                )
            }
    )
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/ai/openclaw/android/ui/components/ScanLineOverlay.kt
git commit -m "feat: add ScanLineOverlay for AI generating state"
```

---

### Task 7: Create TypingCursor.kt

**Files:**
- Create: `app/src/main/java/ai/openclaw/android/ui/components/TypingCursor.kt`

- [ ] **Step 1: Create typing cursor composable**

```kotlin
package ai.openclaw.android.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ai.openclaw.android.ui.theme.SciFiPrimary

@Composable
fun TypingCursor(
    modifier: Modifier = Modifier,
    color: Color = SciFiPrimary
) {
    val infiniteTransition = rememberInfiniteTransition()
    val cursorAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 530),
            repeatMode = RepeatMode.Reverse
        )
    )

    Box(
        modifier = modifier
            .width(2.dp)
            .height(16.dp)
            .background(color.copy(alpha = cursorAlpha))
    )
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/ai/openclaw/android/ui/components/TypingCursor.kt
git commit -m "feat: add TypingCursor with 530ms blink animation"
```

---

### Task 8: Create EnergyBar.kt

**Files:**
- Create: `app/src/main/java/ai/openclaw/android/ui/components/EnergyBar.kt`

- [ ] **Step 1: Create energy bar composable**

```kotlin
package ai.openclaw.android.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ai.openclaw.android.ui.theme.SciFiPrimary

@Composable
fun EnergyBar(
    isFocused: Boolean,
    modifier: Modifier = Modifier,
    color: Color = SciFiPrimary
) {
    val widthFraction by animateFloatAsState(
        targetValue = if (isFocused) 0.8f else 0f,
        animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing),
        label = "energyBar"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .padding(horizontal = 32.dp)
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(widthFraction)
                .height(1.dp)
                .background(
                    brush = Brush.horizontalGradient(
                        colors = listOf(Color.Transparent, color, Color.Transparent)
                    )
                )
        )
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/ai/openclaw/android/ui/components/EnergyBar.kt
git commit -m "feat: add EnergyBar with center-expand animation"
```

---

### Task 9: Create ParticleBackground.kt + drawable resources

**Files:**
- Create: `app/src/main/res/drawable/bg_stars.xml`
- Create: `app/src/main/res/drawable/bg_grid.xml`
- Create: `app/src/main/java/ai/openclaw/android/ui/components/ParticleBackground.kt`

- [ ] **Step 1: Create star-field drawable**

Create `app/src/main/res/drawable/bg_stars.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="400dp"
    android:height="800dp"
    android:viewportWidth="400"
    android:viewportHeight="800">
    <!-- Scattered dots simulating distant stars -->
    <path
        android:fillColor="#FFFFFF"
        android:fillAlpha="0.04"
        android:pathData="M20,50m1,0a1,1 0,1 0,-2a1,1 0,1 0,2 M80,120m1,0a1,1 0,1 0,-2a1,1 0,1 0,2
            M150,30m1,0a1,1 0,1 0,-2a1,1 0,1 0,2 M300,90m1,0a1,1 0,1 0,-2a1,1 0,1 0,2
            M50,200m1,0a1,1 0,1 0,-2a1,1 0,1 0,2 M250,180m1,0a1,1 0,1 0,-2a1,1 0,1 0,2
            M370,250m1,0a1,1 0,1 0,-2a1,1 0,1 0,2 M100,350m1,0a1,1 0,1 0,-2a1,1 0,1 0,2
            M200,400m1,0a1,1 0,1 0,-2a1,1 0,1 0,2 M320,500m1,0a1,1 0,1 0,-2a1,1 0,1 0,2
            M60,600m1,0a1,1 0,1 0,-2a1,1 0,1 0,2 M180,700m1,0a1,1 0,1 0,-2a1,1 0,1 0,2
            M280,620m1,0a1,1 0,1 0,-2a1,1 0,1 0,2 M350,750m1,0a1,1 0,1 0,-2a1,1 0,1 0,2
            M130,480m1,0a1,1 0,1 0,-2a1,1 0,1 0,2 M40,780m1,0a1,1 0,1 0,-2a1,1 0,1 0,2
            M220,280m1,0a1,1 0,1 0,-2a1,1 0,1 0,2 M170,550m1,0a1,1 0,1 0,-2a1,1 0,1 0,2
            M310,380m1,0a1,1 0,1 0,-2a1,1 0,1 0,2 M90,90m1,0a1,1 0,1 0,-2a1,1 0,1 0,2" />
</vector>
```

- [ ] **Step 2: Create hex grid texture drawable**

Create `app/src/main/res/drawable/bg_grid.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="200dp"
    android:height="200dp"
    android:viewportWidth="200"
    android:viewportHeight="200">
    <path
        android:strokeColor="#FFFFFF"
        android:strokeAlpha="0.03"
        android:strokeWidth="0.5"
        android:pathData="
            M0,0 L200,0 M0,40 L200,40 M0,80 L200,80 M0,120 L200,120 M0,160 L200,160 M0,200 L200,200
            M0,0 L0,200 M40,0 L40,200 M80,0 L80,200 M120,0 L120,200 M160,0 L160,200 M200,0 L200,200" />
</vector>
```

- [ ] **Step 3: Create ParticleBackground composable**

```kotlin
package ai.openclaw.android.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.alpha
import androidx.compose.ui.contentAlignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import ai.openclaw.android.R

@Composable
fun ParticleBackground(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = androidx.compose.ui.Alignment.Center
    ) {
        Image(
            painter = painterResource(R.drawable.bg_stars),
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .alpha(0.05f),
            contentScale = ContentScale.Crop
        )
        content()
    }
}
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/res/drawable/bg_stars.xml app/src/main/res/drawable/bg_grid.xml app/src/main/java/ai/openclaw/android/ui/components/ParticleBackground.kt
git commit -m "feat: add static particle background and grid texture drawables"
```

---

### Task 10: Create HapticHelper.kt

**Files:**
- Create: `app/src/main/java/ai/openclaw/android/ui/components/HapticHelper.kt`

- [ ] **Step 1: Create haptic feedback helper**

```kotlin
package ai.openclaw.android.ui.components

import androidx.compose.foundation.LocalHapticFeedback
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType

class HapticHelper(private val haptic: HapticFeedback) {
    fun sendConfirm() = haptic.performHapticFeedback(HapticFeedbackType.Confirm)
    fun onAiReply() = haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    fun onLongPress() = haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    fun onError() = haptic.performHapticFeedback(HapticFeedbackType.Reject)
}

@Composable
fun rememberHapticHelper(): HapticHelper {
    val haptic = LocalHapticFeedback.current
    return remember { HapticHelper(haptic) }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/ai/openclaw/android/ui/components/HapticHelper.kt
git commit -m "feat: add HapticHelper for typed haptic feedback access"
```

---

### Task 11: Build verification — Foundation layer

- [ ] **Step 1: Compile debug build**

Run: `JAVA_HOME="E:/Program Files/Android/Android Studio/jbr" ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Fix any compilation errors**

If any import issues or missing references, fix them. All new files reference only `SciFi*` colors from `Color.kt` which already exist.

---

### Task 12: Update ChatScreen — Top bar with glassmorphism + status indicator

**Files:**
- Modify: `app/src/main/java/ai/openclaw/android/ChatScreen.kt`

- [ ] **Step 1: Add top bar composable before the LazyColumn**

In `ChatScreen()` (line 244), before the `LazyColumn`, add a top bar. Insert after `Column(modifier = modifier.fillMaxSize()) {`:

```kotlin
        // Top bar with glassmorphism
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = SciFiBackground.copy(alpha = 0.85f),
            tonalElevation = 0.dp
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusIndicator(state = ConnectionState.ONLINE)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "OpenClaw",
                        color = SciFiOnBackground,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "· qwen-plus",
                        color = SciFiOutlineVariant,
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(onClick = { /* settings */ }) {
                        Icon(Icons.Default.Settings, "设置", tint = SciFiOnSurfaceVariant)
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .gradientDivider()
                )
            }
        }
```

Add imports at the top of the file:
```kotlin
import ai.openclaw.android.ui.components.StatusIndicator
import ai.openclaw.android.ui.components.ConnectionState
import ai.openclaw.android.ui.theme.gradientDivider
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/ai/openclaw/android/ChatScreen.kt
git commit -m "feat: add glassmorphism top bar with status indicator"
```

---

### Task 13: Update ChatScreen — Replace loading indicator with thinking dots

**Files:**
- Modify: `app/src/main/java/ai/openclaw/android/ChatScreen.kt` (lines 274-286)

- [ ] **Step 1: Replace CircularProgressIndicator with typing dots**

Replace the `isLoading` block (lines 274-286):

```kotlin
            if (isLoading) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 8.dp, top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("🤖", fontSize = 16.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        SciFiThinkingDots()
                    }
                }
            }
```

Add the thinking dots composable at the bottom of the file (before the closing of the file):

```kotlin
@Composable
private fun SciFiThinkingDots() {
    val infiniteTransition = rememberInfiniteTransition()

    Row(
        modifier = Modifier.padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(3) { index ->
            val scale by infiniteTransition.animateFloat(
                initialValue = 0.6f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 600, delayMillis = index * 200),
                    repeatMode = RepeatMode.Reverse
                )
            )
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 600, delayMillis = index * 200),
                    repeatMode = RepeatMode.Reverse
                )
            )
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(SciFiPrimary.copy(alpha = alpha), CircleShape)
                    .scale(scale)
            )
        }
    }
}
```

Add missing imports:
```kotlin
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.scale
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/ai/openclaw/android/ChatScreen.kt
git commit -m "feat: replace CircularProgressIndicator with sci-fi thinking dots"
```

---

### Task 14: Update ChatScreen — Glassmorphism input bar with energy bar

**Files:**
- Modify: `app/src/main/java/ai/openclaw/android/ChatScreen.kt` (lines 302-390)

- [ ] **Step 1: Replace the input area with glassmorphism styled version**

Replace the `Surface` block for the input area (starting at line 302 `Surface(modifier = Modifier.fillMaxWidth(), color = SciFiBackground...)`) with:

```kotlin
        // Input area — glassmorphism + energy bar
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = SciFiBackground.copy(alpha = 0.6f),
            tonalElevation = 0.dp
        ) {
            Column {
                val focusRequesterTag = remember { FocusRequester() }
                var isInputFocused by remember { mutableStateOf(false) }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .imePadding(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 36.dp, max = 120.dp)
                            .neonBorder(
                                focused = isInputFocused,
                                cornerRadius = 24.dp
                            )
                            .background(
                                SciFiSurfaceVariant.copy(alpha = 0.6f),
                                RoundedCornerShape(24.dp)
                            )
                            .focusRequester(focusRequesterTag)
                            .onFocusChanged { isInputFocused = it.isFocused }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (inputText.isEmpty()) {
                            Text(
                                text = "输入消息...",
                                color = SciFiOnSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                        BasicTextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = LocalTextStyle.current.copy(
                                color = SciFiOnSurfaceVariant
                            ),
                            maxLines = 4
                        )
                        if (inputText.isNotEmpty() || isInputFocused) {
                            TypingCursor(
                                modifier = Modifier.align(Alignment.CenterEnd)
                                    .padding(end = 4.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    // Send button with glow
                    val sendEnabled = inputText.isNotBlank() && !isLoading
                    IconButton(
                        onClick = {
                            if (sendEnabled) {
                                hapticHelper.sendConfirm()
                                sendMessage(inputText)
                                inputText = ""
                            }
                        },
                        enabled = sendEnabled,
                        interactionSource = sendInteraction,
                        modifier = Modifier
                            .size(40.dp)
                            .then(
                                if (sendEnabled) Modifier.sciFiGlow(radius = 4.dp)
                                else Modifier
                            )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Send,
                            contentDescription = "发送",
                            modifier = Modifier.scale(sendScale),
                            tint = if (sendEnabled) SciFiPrimary
                            else SciFiOnSurfaceVariant.copy(alpha = 0.38f)
                        )
                    }

                    // Voice button
                    if (voiceManager != null) {
                        IconButton(
                            onClick = {
                                if (voiceManager.hasRecordAudioPermission()) {
                                    voiceManager.startSession { transcript ->
                                        voiceSessionHandler?.invoke(transcript)
                                    }
                                } else {
                                    audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = "语音输入",
                                tint = SciFiOnSurfaceVariant
                            )
                        }
                    }
                }

                EnergyBar(isFocused = isInputFocused)
            }
        }
```

Add missing imports:
```kotlin
import ai.openclaw.android.ui.components.EnergyBar
import ai.openclaw.android.ui.components.TypingCursor
import ai.openclaw.android.ui.theme.neonBorder
import ai.openclaw.android.ui.theme.sciFiGlow
import androidx.compose.ui.onFocusChanged
```

Also add `val hapticHelper = rememberHapticHelper()` near the top of `ChatScreen()` after the voice state declarations, and import `import ai.openclaw.android.ui.components.rememberHapticHelper`.

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/ai/openclaw/android/ChatScreen.kt
git commit -m "feat: glassmorphism input bar with energy bar and neon border"
```

---

### Task 15: Update ChatScreen — Enhance AI bubble with avatar + timestamp

**Files:**
- Modify: `app/src/main/java/ai/openclaw/android/ChatScreen.kt` (AiMessageBubble, lines 517-634)

- [ ] **Step 1: Add avatar label and formatted timestamp to AI bubble**

In `AiMessageBubble()`, inside the `Column` block (line 550), add an avatar label row before the segments loop:

```kotlin
                // Avatar label row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 4.dp)
                ) {
                    Text(
                        text = "🤖 OpenClaw",
                        style = MonospaceAccent,
                        color = SciFiOutlineVariant
                    )
                }
```

Replace the existing timestamp at line 606-611 with right-aligned version:

```kotlin
                Text(
                    text = dateFormat.format(Date(message.timestamp)),
                    style = MonospaceAccent,
                    color = SciFiOutlineVariant.copy(alpha = 0.5f),
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .align(Alignment.End)
                )
```

Add import: `import ai.openclaw.android.ui.theme.MonospaceAccent`

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/ai/openclaw/android/ChatScreen.kt
git commit -m "feat: add avatar label and monospace timestamp to AI message bubbles"
```

---

### Task 16: Update ChatScreen — Add context menu as bottom sheet

**Files:**
- Modify: `app/src/main/java/ai/openclaw/android/ChatScreen.kt`

- [ ] **Step 1: Replace DropdownMenu with bottom sheet**

In both `UserMessageBubble()` and `AiMessageBubble()`, replace the `DropdownMenu` blocks with a `ModalBottomSheetLayout`. This requires restructuring the `showMenu` state to work with the sheet.

In `MessageBubble()`, hoist the bottom sheet state:

```kotlin
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MessageBubble(
    message: ChatMessage,
    dateFormat: SimpleDateFormat,
    onCardAction: (CardAction) -> Unit = {}
) {
    val isUser = message.role == "user"
    val clipboardManager = LocalClipboardManager.current
    val sheetState = rememberModalBottomSheetState()
    var showMenu by remember { mutableStateOf(false) }
    val hapticHelper = rememberHapticHelper()

    if (showMenu) {
        ModalBottomSheet(
            onDismissRequest = { showMenu = false },
            sheetState = sheetState,
            containerColor = SciFiSurfaceVariant
        ) {
            Column(
                modifier = Modifier.padding(vertical = 8.dp)
            ) {
                // Drag handle
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .width(40.dp)
                            .height(4.dp)
                            .background(SciFiOutlineVariant, RoundedCornerShape(2.dp))
                    )
                }
                // Copy
                BottomSheetOption(
                    icon = Icons.Default.ContentCopy,
                    label = "复制",
                    onClick = {
                        clipboardManager.setText(AnnotatedString(message.content))
                        showMenu = false
                    }
                )
                // Regenerate (AI only)
                if (!isUser) {
                    BottomSheetOption(
                        icon = Icons.Default.Refresh,
                        label = "重新生成",
                        onClick = { showMenu = false }
                    )
                }
                // Share
                BottomSheetOption(
                    icon = Icons.Default.Share,
                    label = "分享",
                    onClick = { showMenu = false }
                )
                // Delete
                BottomSheetOption(
                    icon = Icons.Default.Delete,
                    label = "删除",
                    tint = SciFiError,
                    onClick = { showMenu = false }
                )
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        if (isUser) {
            UserMessageBubble(
                message = message,
                dateFormat = dateFormat,
                onCardAction = onCardAction,
                onLongClick = {
                    hapticHelper.onLongPress()
                    showMenu = true
                }
            )
        } else {
            AiMessageBubble(
                message = message,
                dateFormat = dateFormat,
                onCardAction = onCardAction,
                onLongClick = {
                    hapticHelper.onLongPress()
                    showMenu = true
                }
            )
        }
    }
}
```

Add the `BottomSheetOption` composable:

```kotlin
@Composable
private fun BottomSheetOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color = SciFiOnBackground,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon, label,
            tint = tint,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(label, fontSize = 16.sp, color = tint)
    }
}
```

Remove the `showMenu` and `onShowMenuChange` parameters from `UserMessageBubble` and `AiMessageBubble`, and remove their `DropdownMenu` blocks. Keep only `onLongClick` parameter.

Add imports:
```kotlin
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.clickable
```

- [ ] **Step 2: Build to verify**

Run: `JAVA_HOME="E:/Program Files/Android/Android Studio/jbr" ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/ai/openclaw/android/ChatScreen.kt
git commit -m "feat: replace dropdown menu with bottom sheet context menu"
```

---

### Task 17: Update A2UICards — Glassmorphism card styling

**Files:**
- Modify: `app/src/main/java/ai/openclaw/android/ui/A2UICards.kt`

- [ ] **Step 1: Update CardContainer composable**

Find the `CardContainer` composable and update its styling:
- Background: `SciFiSurfaceVariant.copy(alpha = 0.7f)` (semi-transparent for glassmorphism feel)
- Border: top 2dp `SciFiPrimary`, other sides 1dp `SciFiOutline`
- Add `gradientDivider()` between header and content
- Keep existing layout structure unchanged

The key changes are only in the `CardContainer` modifier chain — replace hardcoded `SciFiSurfaceVariant` with `SciFiSurfaceVariant.copy(alpha = 0.7f)`, and add top accent border:

```kotlin
// In CardContainer, change the border modifier:
.border(
    width = BorderSize(0.dp, 0.dp, 0.dp, 2.dp),  // top accent
    color = SciFiPrimary,
    shape = RoundedCornerShape(12.dp)
)
```

If `BorderSize` is not available, use `drawBehind` to draw the top border:

```kotlin
.drawBehind {
    drawLine(
        color = SciFiPrimary,
        start = Offset(0f, 0f),
        end = Offset(size.width, 0f),
        strokeWidth = 2.dp.toPx()
    )
}
```

- [ ] **Step 2: Update divider in CardHeader**

In `CardHeader`, replace any divider lines with `gradientDivider()` modifier.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/ai/openclaw/android/ui/A2UICards.kt
git commit -m "feat: glassmorphism card styling with top accent border"
```

---

### Task 18: Update SettingsScreen — Sci-fi group card style

**Files:**
- Modify: `app/src/main/java/ai/openclaw/android/ui/SettingsScreen.kt`

- [ ] **Step 1: Apply sci-fi card styling to settings groups**

Find all `Card` or `Surface` components used for settings groups and apply:
- Background: `SciFiSurfaceVariant`
- Border: 1dp `SciFiOutline`
- Corner radius: 12dp
- Section title: `SciFiPrimary`, 14sp, uppercase

Replace hardcoded `Color(0xFF4CAF50)` (lines 409, 422) with `SciFiPrimary`.
Replace `Color(0xFFFFA500)` (line 300) with `SciFiError`.

- [ ] **Step 2: Update Switch colors**

In any `Switch` composables, ensure the colors use:
```kotlin
SwitchDefaults.colors(
    checkedThumbColor = SciFiPrimary,
    checkedTrackColor = SciFiPrimary.copy(alpha = 0.5f)
)
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/ai/openclaw/android/ui/SettingsScreen.kt
git commit -m "feat: sci-fi group card style for settings screen"
```

---

### Task 19: Update NotificationScreen — Card style + unread indicator

**Files:**
- Modify: `app/src/main/java/ai/openclaw/android/notification/NotificationScreen.kt`

- [ ] **Step 1: Apply sci-fi card styling to notification items**

For each notification item card:
- Background: `SciFiSurfaceVariant`
- Unread items: add 2dp `SciFiPrimary` left border (via `drawBehind`)
- Read items: `SciFiOutlineVariant` text, no accent border
- Category badge: dark chip with `SciFiOutlineVariant` text

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/ai/openclaw/android/notification/NotificationScreen.kt
git commit -m "feat: sci-fi card style and unread indicator for notification screen"
```

---

### Task 20: Update TypingIndicator.kt — Sci-fi 3-dot pulse

**Files:**
- Modify: `app/src/main/java/ai/openclaw/android/ui/TypingIndicator.kt`

- [ ] **Step 1: Replace existing animation with sci-fi dots**

Update the `DotAnimation` composable to use sci-fi colors and the correct animation spec:

```kotlin
@Composable
fun DotAnimation(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition()
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600),
            repeatMode = RepeatMode.Reverse
        )
    )
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600),
            repeatMode = RepeatMode.Reverse
        )
    )
    Box(
        modifier = modifier
            .size(8.dp)
            .background(SciFiPrimary.copy(alpha = alpha), CircleShape)
            .scale(scale)
    )
}
```

Update `TypingIndicator` to use `SciFiPrimary` colors and remove references to `tertiaryContainer`.

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/ai/openclaw/android/ui/TypingIndicator.kt
git commit -m "feat: sci-fi 3-dot pulse animation for typing indicator"
```

---

### Task 21: Add error state composables

**Files:**
- Modify: `app/src/main/java/ai/openclaw/android/ChatScreen.kt`

- [ ] **Step 1: Add error card composable**

Add these composables at the bottom of `ChatScreen.kt`:

```kotlin
/** Full-page error card — sci-fi styled with friendly Chinese text */
@Composable
fun SciFiErrorCard(
    title: String,
    description: String,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.padding(16.dp),
        shape = RoundedCornerShape(12.dp),
        color = SciFiSurfaceVariant,
        border = BorderStroke(1.dp, SciFiError)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("⚠", fontSize = 24.sp, color = SciFiError)
            Spacer(modifier = Modifier.height(8.dp))
            Text(title, color = SciFiOnBackground, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(description, color = SciFiOnSurfaceVariant, fontSize = 13.sp)
            if (onRetry != null) {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onRetry,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = SciFiPrimary),
                    border = BorderStroke(1.dp, SciFiPrimary)
                ) {
                    Text("重新连接")
                }
            }
        }
    }
}

/** Inline error card for chat stream */
@Composable
fun InlineErrorCard(
    errorCode: String,
    message: String,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.padding(vertical = 4.dp),
        shape = RoundedCornerShape(8.dp),
        color = SciFiError.copy(alpha = 0.1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    drawLine(
                        color = SciFiError,
                        start = Offset(0f, 0f),
                        end = Offset(0f, size.height),
                        strokeWidth = 2.dp.toPx()
                    )
                }
                .padding(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("⚠", fontSize = 12.sp, color = SciFiError)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(errorCode, style = MonospaceAccent, color = SciFiError)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(message, color = SciFiOnSurfaceVariant, fontSize = 13.sp)
            }
            if (onRetry != null) {
                TextButton(onClick = onRetry) {
                    Text("重试", color = SciFiPrimary, fontSize = 12.sp)
                }
            }
        }
    }
}
```

Add missing imports:
```kotlin
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/ai/openclaw/android/ChatScreen.kt
git commit -m "feat: add sci-fi styled error cards (full-page and inline)"
```

---

### Task 22: Full build and verify

- [ ] **Step 1: Clean build**

Run: `JAVA_HOME="E:/Program Files/Android/Android Studio/jbr" ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Run unit tests**

Run: `JAVA_HOME="E:/Program Files/Android/Android Studio/jbr" ./gradlew :app:test`
Expected: All tests PASS

- [ ] **Step 3: Fix any issues and final commit**

If any compilation or test issues arise, fix them and commit.
