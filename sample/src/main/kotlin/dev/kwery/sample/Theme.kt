package dev.kwery.sample

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Kwery's own palette, so the sample and the project's artwork agree.
 *
 * Light only. A sample that ships one considered scheme reads better than one
 * that ships two half-tuned ones.
 */
private val Purple = Color(0xFF7C5CF6)
private val PurpleDark = Color(0xFF5B3FD1)
private val PurpleWash = Color(0xFFEFEAFE)
private val Ink = Color(0xFF16162A)
private val Muted = Color(0xFF6E7191)
private val Canvas = Color(0xFFFBFAFF)
private val CardTint = Color(0xFFFFFFFF)
private val Line = Color(0xFFE8E6F2)

private val scheme = lightColorScheme(
    primary = Purple,
    onPrimary = Color.White,
    primaryContainer = PurpleWash,
    onPrimaryContainer = PurpleDark,
    secondary = Muted,
    background = Canvas,
    onBackground = Ink,
    surface = CardTint,
    onSurface = Ink,
    surfaceVariant = PurpleWash,
    onSurfaceVariant = Muted,
    outline = Line,
    outlineVariant = Line,
    error = Color(0xFFD1345B),
)

private val type = Typography(
    headlineMedium = TextStyle(
        fontSize = 30.sp,
        lineHeight = 36.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.5).sp,
    ),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    labelSmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
)

@Composable
fun SampleTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = type, content = content)
}
