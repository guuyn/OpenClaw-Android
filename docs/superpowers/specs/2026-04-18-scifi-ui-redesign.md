# Sci-Fi UI Redesign — Design Spec

> Date: 2026-04-18 | Status: Approved | Scope: Full 4-phase implementation

## Overview

Redesign the OpenClaw Android app UI with a sci-fi aesthetic inspired by Blade Runner 2049 and Ghost in the Shell. Deep space dark theme with neon cyan accents, glassmorphism, and subtle animated effects.

**Approach**: Hybrid — Material 3 theme handles colors/typography/shapes (80%), custom composables handle unique sci-fi effects (20%).

**Priority**: Quality first, component by component.

**Reference**: `docs/ui-sci-fi-design.md` (original design proposal).

## Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Architecture | Hybrid (theme + custom components) | Theme covers standard styling, components cover glow/glass/scan effects |
| Scope | Full 4-phase | User requested complete implementation |
| Bottom nav bar | Deferred | Focus on chat screen first; nav bar as future optional feature |
| Error text | Friendly Chinese | "连接已断开" not "SIGNAL LOST" — sci-fi visuals, readable copy |
| Timestamps | Normal format | "18:42" at 9sp #475569, not hex-style |
| Particle background | Static drawable | Pre-rendered bitmap, zero GPU overhead |
| Dynamic color | Disabled | Fixed sci-fi color scheme, not system-driven |

## File Structure

```
ui/theme/
├── Color.kt           ← Full sci-fi palette (extend existing)
├── Theme.kt           ← OpenClawTheme with fixed dark scheme (update existing)
├── Type.kt            ← Add monospace accent style (extend existing)
└── ModifierExt.kt     ← NEW: sci-fi modifier extensions

ui/components/          ← NEW directory: custom sci-fi composables
├── GlowEffect.kt      ← Glow/shadow modifiers
├── ScanLineOverlay.kt  ← AI-generating scan line
├── TypingCursor.kt     ← Blinking cursor for streaming
├── EnergyBar.kt        ← Input focus energy line
├── ParticleBackground.kt ← Static star-field overlay
├── StatusIndicator.kt  ← Connection state dot
└── HapticHelper.kt    ← Haptic feedback utilities
```

## Section 1: Theme & Foundation

### Color Scheme

All colors defined in `Color.kt`, applied via Material 3 `ColorScheme` in `Theme.kt`.

Key colors:
- Background: `#0A0E1A` (near-black, blue tint)
- Surface: `#111827`, Surface Variant: `#1E293B`
- Primary: `#06D6A0` (neon cyan)
- Secondary: `#4CC9F0` (ice blue)
- On Background: `#F1F5F9` (bright white text)
- On Surface Variant: `#94A3B8` (secondary text)
- Disabled: `#7D8FA6` (WCAG AA compliant, replaces #64748B)
- Error: `#EF4444`
- User bubble gradient: `#06D6A0` → `#4CC9F0`
- AI bubble background: `#1E293B` with 2dp left border `#06D6A0`
- Glow: `#06D6A0` at 25% opacity

### Typography

Add monospace accent style for decorative data elements (error codes, labels). Standard Material 3 for all body/headline text.

### Modifier Extensions (`ModifierExt.kt`)

Reusable modifiers applied via extension functions:
- `Modifier.sciFiGlow(color, radius)` — shadow-based glow effect
- `Modifier.glassmorphism(blurRadius)` — blur + semi-transparent bg (API 31+; fallback: no blur)
- `Modifier.neonBorder(color, focused)` — border that changes color on focus
- `Modifier.energyBar(isFocused)` — expanding center line on focus

### Degradation

| Effect | API 31+ | Below API 31 |
|--------|---------|--------------|
| Glassmorphism blur | `Modifier.blur(20.dp)` | Semi-transparent bg only, no blur |
| Glow | `Modifier.shadow()` with colored ambient | Standard elevation shadow |

## Section 2: Chat Screen

### Message Bubbles

**User bubble**: Existing gradient (#06D6A0 → #4CC9F0) already implemented. Add:
- Cyan shadow glow: `box-shadow 0 4dp 12dp rgba(6,214,160,0.15)`
- Corner radii: topStart=16, topEnd=16, bottomStart=16, bottomEnd=4
- Entry animation: `slideInVertically + fadeIn`, 300ms

**AI bubble**: Existing dark bg + left border. Add:
- Avatar label row: "🤖 OpenClaw" at 10sp #475569
- Timestamp: normal format "18:42", 9sp, #475569, right-aligned
- Corner radii: topStart=16, topEnd=16, bottomStart=4, bottomEnd=16
- Entry animation: same as user

### AI Generating State (3 components)

1. **Thinking dots** — 3 cyan circles, scale 0.6→1.0 + alpha 0.3→1.0, 200ms stagger, infinite loop. Replaces current `CircularProgressIndicator`. Only shown when waiting for first token.

2. **Scan line overlay** — Horizontal light line sweeping top-to-bottom over the generating AI bubble. 800ms cycle, `LinearGradient` animation. Only on the single active generating bubble; removed when generation completes.

3. **Typing cursor** — Blinking cyan vertical bar (2dp × 16dp) at end of streaming text. 530ms blink cycle. Shown during streaming, hidden when complete.

### Input Bar

Glassmorphism container:
- Background: `rgba(30,41,59,0.6)` + blur 20dp (fallback: no blur)
- Border: 1dp #334155 → #06D6A0 on focus + glow shadow
- Corner radius: 24dp
- Send button: cyan circle + icon, glow when text non-empty
- Voice button: pulse ring animation when recording
- Energy bar below: expands from center on focus, 600ms `FastOutSlowInEasing`

### Top Bar

- Background: `rgba(10,14,26,0.8)` + blur (glassmorphism)
- Title: "OpenClaw" bold white + model name in #475569
- Status indicator (see below)
- Bottom line: 1px cyan, 30% opacity, flowing left→right animation
- Scroll parallax: background alpha 0.8 → 0.95 when scrolled >100px

### Status Indicator

Dot (8dp circle) with 3 states:
- **Online**: #06D6A0 steady glow
- **Thinking**: #4CC9F0 pulse (scale + alpha), 1500ms cycle, outer glow ring
- **Offline**: #EF4444 blink, 400ms cycle

## Section 3: Extended Screens

### A2UI Cards

Keep existing layout structure. Restyle only:
- Background: `rgba(30,41,59,0.7)` with blur
- Border: top 2dp #06D6A0, other sides 1dp #334155
- Type badge: dark chip (#1E293B bg) with #475569 text
- Divider: gradient fade-out on both ends
- Card corner radius: 12dp

### Settings Screen

- Section titles: #06D6A0, 14sp, uppercase label
- Card groups: #1E293B bg, 1dp #334155 border, 12dp corners
- Switches: thumb #06D6A0 when on, track #334155
- Input fields: same glassmorphism style as chat input
- Dropdowns: dark surface, cyan highlight on selected item

### Notification Screen

- Same card style as settings
- Unread: 2dp #06D6A0 left border
- Read: #475569 text, no accent
- Category badge: same chip style

### Error States

**Full-page error card**:
- Background: #1E293B
- Border: 1dp #EF4444, optional blink animation
- Icon: red warning triangle
- Title: friendly Chinese text ("连接已断开", "请求过于频繁", "服务器异常")
- Description: #94A3B8
- Retry button: cyan outline + text

**Inline error (in chat stream)**:
- Background: `rgba(239,68,68,0.1)`
- Left border: 2dp #EF4444
- Error code badge: monospace, "E-429" style
- Description text + cyan "重试" link

### Empty State Page

- Background: #0A0E1A + hex grid SVG texture (alpha 0.03)
- Logo: cyan ring circle, pulse animation (scale 1.0→1.05, 3s cycle)
- Subtitle: "你好，我是 OpenClaw" in #4CC9F0
- Quick question cards: glassmorphism, border #334155 → #06D6A0 on focus, tap sends text
- Layout: centered, 2-3 cards in grid

### Context Menu (Long-press message)

- Bottom sheet: #1E293B + blur
- Drag handle: #475569, 40dp × 4dp
- Options: 52dp height, icon 24dp, text 16sp #F1F5F9
- Delete option: #EF4444 icon + text
- Selection highlight: bg #334155, icon → #06D6A0
- Entry: slide up + backdrop fade, haptic CONTEXT_CLICK

### Scroll-to-Bottom Button

- Cyan circle 40dp, `rgba(6,214,160,0.15)` background
- 1dp #06D6A0 border
- Appears when scrolled >5 messages from bottom
- Position: bottom-right, 16dp above input area
- `AnimatedVisibility` + scale + fade

### Haptic Feedback

| Scene | Type | Strength |
|-------|------|----------|
| Send message | `Confirm` | Light |
| AI reply arrives | `TextHandleMove` | Subtle |
| Long-press menu | `ContextClick` | Medium |
| Error | `Reject` | Strong |

Implementation: `LocalHapticFeedback.current` in Compose.

### Sound Effects (Optional, Default OFF)

- Send: short electronic tone (50ms)
- Receive: soft holographic chime (100ms)
- Error: low warning tone (200ms)
- Files: `res/raw/send_sound.ogg`, `receive_sound.ogg`, `error_sound.ogg`
- Playback: `SoundPool`, max 3 streams
- Toggle in Settings

### Page Transitions

- Entry: slide from right + scale 0.95→1.0 + fade in, 300ms
- Exit: slide left + scale 1.0→0.98 + fade out, 250ms
- A2UI card expand: `SharedTransitionLayout`, 400ms `FastOutSlowInEasing`
