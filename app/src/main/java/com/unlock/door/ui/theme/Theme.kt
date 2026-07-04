package com.unlock.door.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape

// ─── 色彩系统 ───
// 灵感: 木纹、黄铜钥匙、宣纸 — 温暖、触感、家的气息

val Walnut     = Color(0xFF5C3D1E)  // 胡桃木 — 主按钮
val Sand       = Color(0xFFE8D5B7)  // 浅沙色 — 按钮高光
val Moss       = Color(0xFF4A7C59)  // 苔绿 — 成功
val Rust       = Color(0xFFC44B4B)  // 锈红 — 失败
val Parchment  = Color(0xFFF9F6F0)  // 宣纸白 — 背景
val Espresso   = Color(0xFF1A1814)  // 浓咖 — 主文字
val WarmGray   = Color(0xFF8B8578)  // 暖灰 — 次级文字
val Cream      = Color(0xFFF0EBE0)  // 奶油色 — 表面变体
val Border     = Color(0xFFD5CFC0)  // 边框
val PureWhite  = Color(0xFFFFFFFF)  // 卡片白
val WalnutDark = Color(0xFF3D2C1E)  // 深胡桃 — press 状态
val MossLight  = Color(0xFFD4E8D4)  // 浅苔 — 成功容器
val RustLight  = Color(0xFFFFDAD6)  // 浅锈 — 错误容器

private val WarmColorScheme = lightColorScheme(
    primary            = Walnut,
    onPrimary          = PureWhite,
    primaryContainer   = Sand,
    onPrimaryContainer = Espresso,
    secondary          = Moss,
    onSecondary        = PureWhite,
    secondaryContainer = MossLight,
    tertiary           = Rust,
    onTertiary         = PureWhite,
    tertiaryContainer  = RustLight,
    error              = Color(0xFFBA1A1A),
    errorContainer     = Color(0xFFFFDAD6),
    background         = Parchment,
    onBackground       = Espresso,
    surface            = PureWhite,
    onSurface          = Espresso,
    surfaceVariant     = Cream,
    onSurfaceVariant   = WarmGray,
    outline            = Border,
    outlineVariant     = Color(0xFFE8E2D8),
    inverseSurface     = Espresso,
    inverseOnSurface   = Parchment,
)

// ─── 字体排印 ───
// 系统默认字体，通过字重/间距/大小区分层级

val WarmTypography = Typography(
    displayLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
        lineHeight = 42.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = 0.sp,
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = 0.sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.15.sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.15.sp,
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp,
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp,
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.8.sp,
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    ),
)

// ─── 形状 ───
// 柔和圆角，避免尖锐直角

val WarmShapes = Shapes(
    small      = RoundedCornerShape(8.dp),
    medium     = RoundedCornerShape(14.dp),
    large      = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

// ─── 主题入口 ───

@Composable
fun SimpleUnlockTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = WarmColorScheme,
        typography = WarmTypography,
        shapes     = WarmShapes,
        content    = content,
    )
}
