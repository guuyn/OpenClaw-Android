package ai.openclaw.android.ui.theme

import androidx.compose.ui.graphics.Color

// ==================== Sci-Fi 主题色板 ====================

// === 基底色 ===
val SciFiBackground     = Color(0xFF0A0E1A)  // 近乎纯黑，微蓝
val SciFiSurface        = Color(0xFF111827)  // 卡片/表面
val SciFiSurfaceVariant = Color(0xFF1E293B)  // 悬浮层/弹窗
val SciFiOutline        = Color(0xFF334155)  // 边框/分割线
val SciFiOutlineVariant = Color(0xFF475569)  // 次要边框

// === 强调色 ===
val SciFiPrimary       = Color(0xFF06D6A0)  // 青色霓虹
val SciFiOnPrimary     = Color(0xFF0A0E1A)  // 主色上的文字
val SciFiSecondary     = Color(0xFF4CC9F0)  // 冰蓝色
val SciFiTertiary      = Color(0xFF7C3AED)  // 紫色（辅助装饰）
val SciFiError         = Color(0xFFEF4444)  // 错误/警告
val SciFiSuccess       = Color(0xFF06D6A0)  // 成功

// === 文字色 ===
val SciFiOnBackground  = Color(0xFFF1F5F9)  // 主文字 — 对比度 18.4:1
val SciFiOnSurface     = Color(0xFFF1F5F9)  // 表面文字
val SciFiOnSurfaceVariant = Color(0xFF94A3B8)  // 副文字 — 对比度 5.8:1
val SciFiDisabled      = Color(0xFF7D8FA6)  // 禁用文字 — 对比度 4.6:1 ✅

// === 卡片 & 气泡 ===
val SciFiUserBubbleStart  = Color(0xFF06D6A0)  // 用户气泡渐变起始
val SciFiUserBubbleEnd    = Color(0xFF4CC9F0)  // 用户气泡渐变结束
val SciFiAiBubbleBg       = Color(0xFF1E293B)  // AI 气泡背景
val SciFiAiBubbleBorder   = Color(0xFF06D6A0)  // AI 气泡左边框

// === 装饰色 ===
val SciFiGlow            = Color(0x4006D6A0)  // 青色半透明发光
val SciFiScanLine        = Color(0x4006D6A0)  // 扫描线
val SciFiEnergyBar       = Color(0xFF06D6A0)  // 能量条
val SciFiParticle        = Color(0x0DFFFFFF)  // 粒子背景 alpha=0.05
val SciFiGrid            = Color(0x08FFFFFF)  // 网格纹理 alpha=0.03

// ==================== 亮色模式色板（可选） ====================

val SciFiLightBackground  = Color(0xFFF8FAFC)
val SciFiLightSurface     = Color(0xFFFFFFFF)
val SciFiLightPrimary     = Color(0xFF059669)
val SciFiLightOnPrimary   = Color(0xFFFFFFFF)
val SciFiLightOnSurface   = Color(0xFF0F172A)
val SciFiLightAiBubbleBg  = Color(0xFFF1F5F9)
val SciFiLightAiBubbleBorder = Color(0xFF059669)

// ==================== 保留旧色值（向后兼容） ====================

val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)

val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)
