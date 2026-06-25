package com.example.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// ── Material 3 seed colors ─────────────────────────────────────────────────────
// Light palette (soft blue-gray aesthetic)
val LightPrimary = Color(0xFF0EA5E9)
val LightSecondary = Color(0xFF475569)
val LightTertiary = Color(0xFF059669)
val LightBackground = Color(0xFFF4F6FA)
val LightSurface = Color(0xFFFFFFFF)
val LightOnBackground = Color(0xFF0F172A)
val LightOnSurface = Color(0xFF0F172A)
val LightOutline = Color(0xFFDDE4EF)
val LightSurfaceVariant = Color(0xFFEEF2F8)
val LightPrimaryContainer = Color(0xFFE0F2FE)
val LightOnPrimaryContainer = Color(0xFF0F172A)

// Dark palette (navy aesthetic)
val DarkPrimary = Color(0xFF00E5FF)
val DarkSecondary = Color(0xFF94A3B8)
val DarkTertiary = Color(0xFF10B981)
val DarkBackground = Color(0xFF0B0F19)
val DarkSurface = Color(0xFF131A26)
val DarkOnBackground = Color(0xFFFFFFFF)
val DarkOnSurface = Color(0xFFFFFFFF)
val DarkOutline = Color(0xFF1E293B)
val DarkSurfaceVariant = Color(0xFF1A2535)
val DarkPrimaryContainer = Color(0xFF00E5FF)
val DarkOnPrimaryContainer = Color(0xFF0B0F19)

// ── App-level color scheme (used throughout all composables) ───────────────────
data class AppColors(
    val bg: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val border: Color,
    val borderStrong: Color,
    val text: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val divider: Color,
    val accent: Color,
    val accentDim: Color,
    val income: Color,
    val expense: Color,
    val isDark: Boolean
)

fun darkAppColors() = AppColors(
    bg            = Color(0xFF0B0F19),
    surface       = Color(0xFF131A26),
    surfaceVariant = Color(0xFF1A2535),
    border        = Color(0xFF1E293B),
    borderStrong  = Color(0xFF2D3F55),
    text          = Color.White,
    textSecondary = Color(0xFF94A3B8),
    textTertiary  = Color(0xFF64748B),
    divider       = Color(0x14FFFFFF),
    accent        = Color(0xFF00E5FF),
    accentDim     = Color(0x2600E5FF),
    income        = Color(0xFF10B981),
    expense       = Color(0xFFF43F5E),
    isDark        = true
)

fun lightAppColors() = AppColors(
    bg            = Color(0xFFF4F6FA),
    surface       = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFEEF2F8),
    border        = Color(0xFFDDE4EF),
    borderStrong  = Color(0xFFC1CCDB),
    text          = Color(0xFF0F172A),
    textSecondary = Color(0xFF475569),
    textTertiary  = Color(0xFF94A3B8),
    divider       = Color(0xFFDDE4EF),
    accent        = Color(0xFF0EA5E9),
    accentDim     = Color(0x200EA5E9),
    income        = Color(0xFF059669),
    expense       = Color(0xFFDC2626),
    isDark        = false
)

val LocalAppColors = staticCompositionLocalOf { darkAppColors() }

