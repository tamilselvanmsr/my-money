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
    val isDark: Boolean,
    val flatBg: Color = Color.Unspecified,  // more vibrant bg for flat/borderless mode
    val isBorderless: Boolean = false
) {
    val cardBg: Color get() = if (isBorderless) Color.Transparent else surface
    val cardBorderColor: Color get() = if (isBorderless) Color.Transparent else border
    val effectiveBg: Color get() = if (isBorderless && flatBg != Color.Unspecified) flatBg else bg
}

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
    isDark        = true,
    flatBg        = Color(0xFF060A12)   // deeper for flat dark
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
    isDark        = false,
    flatBg        = Color(0xFFDEE6F2)   // noticeably deeper blue-grey for flat light
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

// ── Theme 5: Gold ──────────────────────────────────────────────────────────
fun goldAppColors() = AppColors(
    bg            = Color(0xFFFEFCE8), surface = Color(0xFFFEF9C3), surfaceVariant = Color(0xFFFEF08A),
    border        = Color(0xFFCA8A04), borderStrong = Color(0xFFA86004),
    text          = Color(0xFF1C1000), textSecondary = Color(0xFF78580A), textTertiary = Color(0xFFB08030),
    divider       = Color(0xFFE8C840), accent = Color(0xFFCA8A04), accentDim = Color(0x20CA8A04),
    income        = Color(0xFF166534), expense = Color(0xFF991B1B), isDark = false,
    flatBg        = Color(0xFFFEED70)
)

// ── Theme 6: Jade ──────────────────────────────────────────────────────────
fun jadeAppColors() = AppColors(
    bg            = Color(0xFFF0FDF8), surface = Color(0xFFEBFAF4), surfaceVariant = Color(0xFFD4F0E8),
    border        = Color(0xFF6EC4A6), borderStrong = Color(0xFF3DA888),
    text          = Color(0xFF0A2920), textSecondary = Color(0xFF1E6550), textTertiary = Color(0xFF4A9C80),
    divider       = Color(0xFFB8DECE), accent = Color(0xFF0D7A5C), accentDim = Color(0x200D7A5C),
    income        = Color(0xFF15803D), expense = Color(0xFFB91C1C), isDark = false,
    flatBg        = Color(0xFFB8ECD8)
)

// ── Theme 7: Sand ──────────────────────────────────────────────────────────
fun sandAppColors() = AppColors(
    bg            = Color(0xFFFDF8F2), surface = Color(0xFFF5EAD8), surfaceVariant = Color(0xFFEEDEC5),
    border        = Color(0xFFCCAA80), borderStrong = Color(0xFFAA8860),
    text          = Color(0xFF2A1A08), textSecondary = Color(0xFF6E4C2A), textTertiary = Color(0xFFA07850),
    divider       = Color(0xFFDDC4A0), accent = Color(0xFF92400E), accentDim = Color(0x2092400E),
    income        = Color(0xFF3D7A3D), expense = Color(0xFFA83232), isDark = false,
    flatBg        = Color(0xFFE8CBB2)
)

// ── Theme 8: Midnight — deep blue-black, elegant ──────────────────────────
fun midnightAppColors() = AppColors(
    bg            = Color(0xFF0D0D1A), surface = Color(0xFF16162A), surfaceVariant = Color(0xFF1E1E38),
    border        = Color(0xFF2A2A50), borderStrong = Color(0xFF3A3A70),
    text          = Color(0xFFE8E8FF), textSecondary = Color(0xFF9090C0), textTertiary = Color(0xFF6060A0),
    divider       = Color(0x18FFFFFF), accent = Color(0xFF818CF8), accentDim = Color(0x26818CF8),
    income        = Color(0xFF34D399), expense = Color(0xFFF87171), isDark = true,
    flatBg        = Color(0xFF060610)
)

// ── Theme 9: Ocean — teal blue, clear and vibrant ─────────────────────────
fun oceanAppColors() = AppColors(
    bg            = Color(0xFFECF5F9), surface = Color(0xFFD6EDF8), surfaceVariant = Color(0xFFC0E4F4),
    border        = Color(0xFF77BBDD), borderStrong = Color(0xFF4499BB),
    text          = Color(0xFF0A2535), textSecondary = Color(0xFF1A5070), textTertiary = Color(0xFF4A8AAA),
    divider       = Color(0xFFAAD4E8), accent = Color(0xFF0077B6), accentDim = Color(0x200077B6),
    income        = Color(0xFF00897B), expense = Color(0xFFD84315), isDark = false,
    flatBg        = Color(0xFFB0D8EE)
)


