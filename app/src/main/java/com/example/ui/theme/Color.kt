package com.example.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// ── Material 3 seed colors ─────────────────────────────────────────────────────
// Light palette (soft blue-gray aesthetic)
val LightPrimary = Color(0xFF0369A1)
val LightSecondary = Color(0xFF475569)
val LightTertiary = Color(0xFF059669)
val LightBackground = Color(0xFFF4F6FA)
val LightSurface = Color(0xFFFFFFFF)
val LightOnBackground = Color(0xFF0F172A)
val LightOnSurface = Color(0xFF0F172A)
val LightOutline = Color(0xFFD6DDE8)
val LightSurfaceVariant = Color(0xFFEEF2F8)
val LightPrimaryContainer = Color(0xFFDCEEFA)
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
    border        = Color(0xFFD6DDE8),
    borderStrong  = Color(0xFFB4BFD0),
    text          = Color(0xFF0F172A),
    textSecondary = Color(0xFF475569),
    textTertiary  = Color(0xFF94A3B8),
    divider       = Color(0xFFE6EBF4),
    accent        = Color(0xFF0369A1),
    accentDim     = Color(0x200369A1),
    income        = Color(0xFF059669),
    expense       = Color(0xFFDC2626),
    isDark        = false
)

val LocalAppColors = staticCompositionLocalOf { darkAppColors() }

// ── Theme 3: Forest — deep forest greens, amber accents, colored surfaces ────
fun forestAppColors() = AppColors(
    bg            = Color(0xFFF0F7EF),
    surface       = Color(0xFFD8EED5),  // distinctly green surface
    surfaceVariant = Color(0xFFC8E4C4),
    border        = Color(0xFF9EC89A),
    borderStrong  = Color(0xFF6BA565),
    text          = Color(0xFF1A3320),
    textSecondary = Color(0xFF3D6648),
    textTertiary  = Color(0xFF6B9B72),
    divider       = Color(0xFFBED9BA),
    accent        = Color(0xFF1B7040),
    accentDim     = Color(0x201B7040),
    income        = Color(0xFF16A34A),
    expense       = Color(0xFFC2410C),
    isDark        = false
)

// ── Theme 4: Sunset — warm cream, vivid tangerine, colored surfaces ───────────
fun sunsetAppColors() = AppColors(
    bg            = Color(0xFFFFF8EF),
    surface       = Color(0xFFFFE4C4),  // distinctly warm tan surface
    surfaceVariant = Color(0xFFFFD8A0),
    border        = Color(0xFFD4A055),
    borderStrong  = Color(0xFFB87C30),
    text          = Color(0xFF2D1B00),
    textSecondary = Color(0xFF7A4511),
    textTertiary  = Color(0xFFBF7B45),
    divider       = Color(0xFFEDC990),
    accent        = Color(0xFFD35400),
    accentDim     = Color(0x20D35400),
    income        = Color(0xFF27AE60),
    expense       = Color(0xFFC0392B),
    isDark        = false
)

// ── Theme 5: Ocean — sky-blue surfaces, cyan accents ─────────────────────────
fun oceanAppColors() = AppColors(
    bg            = Color(0xFFEBF5F9),
    surface       = Color(0xFFCDE8F4),  // teal-blue surface
    surfaceVariant = Color(0xFFBBDEED),
    border        = Color(0xFF84C3DB),
    borderStrong  = Color(0xFF4EA8CC),
    text          = Color(0xFF0D3A4F),
    textSecondary = Color(0xFF1A6B90),
    textTertiary  = Color(0xFF4A9BB8),
    divider       = Color(0xFFAAD8EA),
    accent        = Color(0xFF0288D1),
    accentDim     = Color(0x200288D1),
    income        = Color(0xFF00897B),
    expense       = Color(0xFFD84315),
    isDark        = false
)

// ── Theme 6: Lavender — purple-tinted surfaces, deep violet accent ────────────
fun lavenderAppColors() = AppColors(
    bg            = Color(0xFFF2EDFF),
    surface       = Color(0xFFE2D4FF),  // clearly purple surface
    surfaceVariant = Color(0xFFD5C4FF),
    border        = Color(0xFFAA8EE0),
    borderStrong  = Color(0xFF8B6CC8),
    text          = Color(0xFF2C1B5C),
    textSecondary = Color(0xFF5E3A9C),
    textTertiary  = Color(0xFF9070C8),
    divider       = Color(0xFFC8B2F0),
    accent        = Color(0xFF6A1B9A),
    accentDim     = Color(0x206A1B9A),
    income        = Color(0xFF2E7D32),
    expense       = Color(0xFFB71C1C),
    isDark        = false
)

// ── Theme 7: Rose — pink-flushed surfaces, magenta accent ────────────────────
fun roseAppColors() = AppColors(
    bg            = Color(0xFFFFF0F3),
    surface       = Color(0xFFFFCEDB),  // clearly pink surface
    surfaceVariant = Color(0xFFFFBDD0),
    border        = Color(0xFFF08CA8),
    borderStrong  = Color(0xFFD46080),
    text          = Color(0xFF560A1E),
    textSecondary = Color(0xFFA0284C),
    textTertiary  = Color(0xFFCC6688),
    divider       = Color(0xFFF4B4C8),
    accent        = Color(0xFFE91E63),
    accentDim     = Color(0x20E91E63),
    income        = Color(0xFF2E7D32),
    expense       = Color(0xFFC62828),
    isDark        = false
)

// ── Theme 8: Carbon — MyMoney-inspired deep dark, purple accent ───────────────
fun carbonAppColors() = AppColors(
    bg            = Color(0xFF121212),
    surface       = Color(0xFF1E1E2E),  // deep blue-grey surface
    surfaceVariant = Color(0xFF252540),
    border        = Color(0xFF32325A),
    borderStrong  = Color(0xFF4A4A80),
    text          = Color(0xFFE2E2FF),
    textSecondary = Color(0xFF9898CC),
    textTertiary  = Color(0xFF6666A0),
    divider       = Color(0x1AFFFFFF),
    accent        = Color(0xFFBB86FC),
    accentDim     = Color(0x26BB86FC),
    income        = Color(0xFF03DAC6),
    expense       = Color(0xFFCF6679),
    isDark        = true
)


