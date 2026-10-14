package com.unlock.door.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ─── 色彩系统 ───
//
// 取色自《东南大学视觉形象识别系统》A5-1 标准主色（seu.edu.cn 官方公布）：
//
//   东大绿   CMYK 70 / 30 / 100 / 20   RGB 76, 125, 44    #4C7D2C
//   东大黄   CMYK  0 / 20 / 100 /  0   RGB 253, 208, 0    #FDD000
//   标准黑   CMYK  0 /  0 /   0 /100   RGB 35, 24, 21     #231815
//
// 用法约定：
//   · 绿是主色，负责「按钮、选中、成功」这类需要行动与肯定的地方；
//   · 黄是辅色，只做点缀（徽标、提醒），绝不铺面 —— 大面积黄会廉价；
//   · 其余中性色都往绿相里偏一点点，让白底不显得冷。

val SeuGreen     = Color(0xFF4C7D2C)  // 东大绿 —— 品牌标准色，用于描边 / 强调
val SeuGreenFill = Color(0xFF5B8F33)  // 大色块填充 —— 标准色提亮一档，实心面积大时更透气
val SeuGreenDeep = Color(0xFF47701F)  // 深一档 —— 场上的标题文字 / 成功
val SeuGreenSoft = Color(0xFFE9F1E0)  // 极浅绿 —— 选中底 / 容器
val SeuYellow    = Color(0xFFFDD000)  // 东大黄 —— 辅色，仅点缀
val SeuInk       = Color(0xFF231815)  // 标准黑 —— 主文字
val SeuInkSoft   = Color(0xFF6B7066)  // 次级文字
val SeuInkTint   = Color(0xFF9AA094)  // 三级文字 / 中性图标
val SeuLine      = Color(0xFFE6EAE0)  // 发丝分隔线（极简风靠线，不靠阴影）
val SeuSurface   = Color(0xFFFFFFFF)  // 卡片面
val SeuBg        = Color(0xFFF7F9F5)  // 页面底 —— 带一丝绿的白
val SeuError     = Color(0xFFC0392B)  // 失败 —— 压暗的红，不用纯红
val SeuErrorSoft = Color(0xFFFBE9E7)  // 失败容器

// ─── 主界面「感应场」专用 ───
// 整屏铺一块浅绿场。底色比页面底 #F7F9F5 有存在感，但要比第一版更轻 —— 场是背景，
// 不该跟中央的圆盘抢注意力。
// 场上的文字不能直接用 SeuInkSoft（对比度只有 3.9，小字发虚），另给一档更深的。
val SeuGreenField = Color(0xFFEEF5E7)  // 场底色
val SeuInkOnField = Color(0xFF5E6B52)  // 场上的次级文字

private val SeuColorScheme = lightColorScheme(
    primary            = SeuGreen,
    onPrimary          = SeuSurface,
    primaryContainer   = SeuGreenSoft,
    onPrimaryContainer = SeuGreenDeep,
    secondary          = SeuGreenDeep,
    onSecondary        = SeuSurface,
    secondaryContainer = SeuGreenSoft,
    onSecondaryContainer = SeuGreenDeep,
    tertiary           = SeuYellow,
    onTertiary         = SeuInk,
    tertiaryContainer  = Color(0xFFFFF6D1),
    onTertiaryContainer = Color(0xFF5A4400),
    error              = SeuError,
    onError            = SeuSurface,
    errorContainer     = SeuErrorSoft,
    onErrorContainer   = Color(0xFF7A1F14),
    background         = SeuBg,
    onBackground       = SeuInk,
    surface            = SeuSurface,
    onSurface          = SeuInk,
    surfaceVariant     = Color(0xFFF2F5EE),
    onSurfaceVariant   = SeuInkSoft,
    outline            = SeuLine,
    outlineVariant     = Color(0xFFEDF1E8),
    inverseSurface     = SeuInk,
    inverseOnSurface   = SeuBg,
)

// ─── 字体排印 ───
//
// 极简风的做法：靠字号和留白拉层级，不靠字重堆叠。
// 全篇只用 Medium / SemiBold 两档，且把字距收回到 0 —— 原来的 0.5~0.8sp
// 是中文正文里显松散、标题里显啰嗦的来源。

val SeuTypography = Typography(
    displayLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 40.sp,
        lineHeight = 46.sp,
        letterSpacing = (-1).sp,
    ),
    headlineLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.4).sp,
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
        lineHeight = 25.sp,
        letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp,
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.sp,
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp,
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp,
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp,
    ),
)

// ─── 形状 ───
// 统一四档，布局里一律引用，不再各自写 dp 值

val SeuShapes = androidx.compose.material3.Shapes(
    small      = RoundedCornerShape(10.dp),
    medium     = RoundedCornerShape(14.dp),
    large      = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

// ─── 主题入口 ───

@Composable
fun SimpleUnlockTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SeuColorScheme,
        typography = SeuTypography,
        shapes     = SeuShapes,
        content    = content,
    )
}
