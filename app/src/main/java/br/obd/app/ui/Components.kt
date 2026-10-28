package br.obd.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isUnspecified
import androidx.compose.ui.unit.sp
import br.obd.app.Level
import br.obd.app.Settings
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

// ------------------------------------------------------------------ temas

/** Paleta completa de um tema. */
data class AppPalette(
    val name: String,
    val accent: Color,
    val accent2: Color,
    val bg: Color,
    val surface: Color,
    val high: Color,
    val highest: Color,
    val outline: Color,
    val text: Color,
    val muted: Color,
    val dark: Boolean = true,
)

private val DEFAULT_PALETTE = AppPalette("Grafite", Color(0xFFFFA726), Color(0xFF4DD0E1), Color(0xFF0A0D12), Color(0xFF121821), Color(0xFF18202B), Color(0xFF1E2531), Color(0xFF2A3341), Color(0xFFE8EDF2), Color(0xFF93A1B0))

val THEMES = listOf(
    AppPalette("Grafite", Color(0xFFFFA726), Color(0xFF4DD0E1), Color(0xFF0A0D12), Color(0xFF121821), Color(0xFF18202B), Color(0xFF1E2531), Color(0xFF2A3341), Color(0xFFE8EDF2), Color(0xFF93A1B0)),
    AppPalette("Meia-noite", Color(0xFF5C9DFF), Color(0xFF7CE0C3), Color(0xFF070B16), Color(0xFF0F1626), Color(0xFF151E33), Color(0xFF1C2740), Color(0xFF26334F), Color(0xFFE6ECF7), Color(0xFF8E9BB5)),
    AppPalette("Esmeralda", Color(0xFF34D399), Color(0xFFFBBF24), Color(0xFF06110D), Color(0xFF0D1B16), Color(0xFF12241D), Color(0xFF193026), Color(0xFF223D31), Color(0xFFE4F2EC), Color(0xFF8AA89B)),
    AppPalette("Rubi", Color(0xFFFF5370), Color(0xFFFFB86C), Color(0xFF120709), Color(0xFF1D0E12), Color(0xFF261318), Color(0xFF311A20), Color(0xFF42232B), Color(0xFFF5E6E9), Color(0xFFB0959B)),
    AppPalette("Ametista", Color(0xFFB388FF), Color(0xFF64FFDA), Color(0xFF0D0816), Color(0xFF171024), Color(0xFF1E1530), Color(0xFF271C3D), Color(0xFF34274F), Color(0xFFEDE6F7), Color(0xFFA197B5)),
    AppPalette("Oceano", Color(0xFF22D3EE), Color(0xFFA3E635), Color(0xFF041015), Color(0xFF0A1C24), Color(0xFF0F2630), Color(0xFF153140), Color(0xFF1D4152), Color(0xFFE2F2F6), Color(0xFF86A6B0)),
    AppPalette("Pôr do sol", Color(0xFFFF7A59), Color(0xFFFFD166), Color(0xFF140C0A), Color(0xFF211512), Color(0xFF2A1B17), Color(0xFF35231E), Color(0xFF473029), Color(0xFFF7EAE5), Color(0xFFB59D94)),
    AppPalette("AMOLED", Color(0xFFFFA726), Color(0xFF4DD0E1), Color(0xFF000000), Color(0xFF0B0B0B), Color(0xFF121212), Color(0xFF1A1A1A), Color(0xFF262626), Color(0xFFEDEDED), Color(0xFF9A9A9A)),
    AppPalette("Claro", Color(0xFFE67E00), Color(0xFF0097A7), Color(0xFFF3F5F8), Color(0xFFFFFFFF), Color(0xFFF0F2F5), Color(0xFFE6E9EE), Color(0xFFD5DAE1), Color(0xFF151A21), Color(0xFF5F6B78), dark = false),
)

/** Tema atual, global e observável: mudar aqui recolore o app inteiro na hora. */
object ThemeState {
    var current by mutableStateOf(DEFAULT_PALETTE)
    fun apply(index: Int) { current = THEMES.getOrElse(index) { THEMES[0] } }
}

val Accent: Color get() = ThemeState.current.accent
val Cyan: Color get() = ThemeState.current.accent2
val Good: Color get() = if (ThemeState.current.dark) Color(0xFF66BB6A) else Color(0xFF2E9E4F)
val Warn = Color(0xFFFFB74D)
val Bad = Color(0xFFEF5350)
private val Hairline: Color get() = if (ThemeState.current.dark) Color(0x14FFFFFF) else Color(0x14000000)

fun levelColor(l: Level?): Color = when (l) {
    Level.GOOD -> Good; Level.WARN -> Warn; Level.BAD -> Bad; Level.INFO -> Cyan; null -> Color.Unspecified
}

fun Level.tone() = when (this) { Level.GOOD -> Tone.GOOD; Level.WARN -> Tone.WARN; Level.BAD -> Tone.BAD; Level.INFO -> Tone.INFO }

/** Números com largura fixa: o valor não "pula" de lado quando muda. */
val Tabular = TextStyle(fontFeatureSettings = "tnum")

private val AppTypography = Typography().let { t ->
    t.copy(
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = t.labelSmall.copy(letterSpacing = 0.4.sp),
    )
}

@Composable
fun ScannerTheme(content: @Composable () -> Unit) {
    val p = ThemeState.current
    val scheme = if (p.dark) darkColorScheme(
        primary = p.accent, onPrimary = Color(0xFF14100A),
        primaryContainer = p.accent.copy(alpha = 0.18f).compositeOver(p.surface), onPrimaryContainer = p.text,
        secondary = p.accent2, secondaryContainer = p.accent2.copy(alpha = 0.18f).compositeOver(p.surface), onSecondaryContainer = p.text,
        background = p.bg, onBackground = p.text, surface = p.bg, onSurface = p.text,
        surfaceVariant = p.highest, onSurfaceVariant = p.muted,
        surfaceContainer = p.surface, surfaceContainerHigh = p.high, surfaceContainerHighest = p.highest,
        surfaceContainerLow = p.surface, surfaceContainerLowest = p.bg,
        outline = p.outline, outlineVariant = p.highest, error = Bad,
    ) else lightColorScheme(
        primary = p.accent, onPrimary = Color.White,
        primaryContainer = p.accent.copy(alpha = 0.16f).compositeOver(p.surface), onPrimaryContainer = p.text,
        secondary = p.accent2, secondaryContainer = p.accent2.copy(alpha = 0.16f).compositeOver(p.surface), onSecondaryContainer = p.text,
        background = p.bg, onBackground = p.text, surface = p.bg, onSurface = p.text,
        surfaceVariant = p.highest, onSurfaceVariant = p.muted,
        surfaceContainer = p.surface, surfaceContainerHigh = p.high, surfaceContainerHighest = p.highest,
        surfaceContainerLow = p.surface, surfaceContainerLowest = p.bg,
        outline = p.outline, outlineVariant = p.highest, error = Bad,
    )
    val d = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(d.density, d.fontScale.coerceIn(0.85f, 1.12f))) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
    }
}

private fun Color.compositeOver(bg: Color): Color {
    val a = alpha
    return Color(red * a + bg.red * (1 - a), green * a + bg.green * (1 - a), blue * a + bg.blue * (1 - a), 1f)
}

// ------------------------------------------------------------------ toque

/** Clique com leve "afundar" e vibração curta (se ligada nas preferências). */
fun Modifier.pressable(enabled: Boolean = true, onClick: () -> Unit): Modifier = composed {
    val haptic = LocalHapticFeedback.current
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val sc by animateFloatAsState(if (pressed) 0.96f else 1f, spring(stiffness = Spring.StiffnessMediumLow), label = "press")
    this.scale(sc).clickable(src, indication = androidx.compose.material3.ripple(), enabled = enabled) {
        if (Settings.prefs.value.haptics) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        onClick()
    }
}

/** Vibração curta para botões comuns. */
@Composable
fun rememberTap(): (() -> Unit) -> () -> Unit {
    val haptic = LocalHapticFeedback.current
    return { action -> {
        if (Settings.prefs.value.haptics) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        action()
    } }
}

// ------------------------------------------------------------------ mostrador

/**
 * Mostrador em arco de 270° com marcas, faixa vermelha opcional e arco em degradê.
 * O número fica no centro com dígitos de largura fixa.
 */
@Composable
fun Gauge(
    value: Double?,
    max: Double,
    label: String,
    unit: String,
    modifier: Modifier = Modifier,
    warnAt: Double? = null,
    majorStep: Double = max / 7,
    digits: Int = 0,
) {
    val frac by animateFloatAsState(
        ((value ?: 0.0) / max).coerceIn(0.0, 1.0).toFloat(),
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow),
        label = "gauge",
    )
    val track = MaterialTheme.colorScheme.surfaceVariant
    val tick = MaterialTheme.colorScheme.onSurfaceVariant
    val hot = warnAt != null && (value ?: 0.0) >= warnAt
    val arc by animateColorAsState(if (hot) Bad else Accent, label = "arc")

    Card(
        modifier,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, Hairline),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).padding(10.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.minDimension * 0.065f
                val r = size.minDimension / 2 - w
                val c = Offset(size.width / 2, size.height / 2)
                val steps = (max / majorStep).toInt().coerceAtLeast(1)
                for (i in 0..steps * 2) {
                    val f = i / (steps * 2f)
                    val ang = Math.toRadians((135 + 270 * f).toDouble())
                    val major = i % 2 == 0
                    val inner = r - w * (if (major) 1.9f else 1.4f)
                    val outer = r - w * 0.95f
                    val red = warnAt != null && f * max >= warnAt
                    drawLine(
                        if (red) Bad.copy(alpha = 0.8f) else tick.copy(alpha = if (major) 0.55f else 0.25f),
                        Offset(c.x + inner * cos(ang).toFloat(), c.y + inner * sin(ang).toFloat()),
                        Offset(c.x + outer * cos(ang).toFloat(), c.y + outer * sin(ang).toFloat()),
                        strokeWidth = if (major) 3f else 2f, cap = StrokeCap.Round,
                    )
                }
                inset(w) {
                    drawArc(track, 135f, 270f, false, style = Stroke(w, cap = StrokeCap.Round))
                    warnAt?.let {
                        val start = (it / max).toFloat()
                        drawArc(Bad.copy(alpha = 0.22f), 135f + 270f * start, 270f * (1 - start), false,
                            style = Stroke(w, cap = StrokeCap.Butt))
                    }
                    if (frac > 0.002f) {
                        drawArc(
                            Brush.sweepGradient(0f to arc.copy(alpha = 0.55f), 0.75f to arc, 1f to arc.copy(alpha = 0.55f), center = center),
                            135f, 270f * frac, false, style = Stroke(w, cap = StrokeCap.Round),
                        )
                        // ponta luminosa
                        val ang = Math.toRadians((135 + 270 * frac).toDouble())
                        val rr = size.minDimension / 2
                        drawCircle(arc.copy(alpha = 0.35f), w * 0.9f, Offset(center.x + rr * cos(ang).toFloat(), center.y + rr * sin(ang).toFloat()))
                    }
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                FitText(
                    value?.let { "%.${digits}f".format(it) } ?: "—",
                    Tabular.copy(fontSize = 38.sp, fontWeight = FontWeight.Bold),
                    Modifier.padding(horizontal = 18.dp),
                    color = if (hot) Bad else MaterialTheme.colorScheme.onSurface,
                )
                Text(unit, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Text(
                label.uppercase(), Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            )
        }
    }
}

/** Anel de progresso animado com conteúdo no centro (testes, contagem regressiva). */
@Composable
fun Ring(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = Accent,
    stroke: Float = 14f,
    content: @Composable () -> Unit,
) {
    val p by animateFloatAsState(progress.coerceIn(0f, 1f), tween(450, easing = FastOutSlowInEasing), label = "ring")
    val track = MaterialTheme.colorScheme.surfaceVariant
    Box(modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = stroke.dp.toPx()
            inset(w / 2) {
                drawArc(track, -90f, 360f, false, style = Stroke(w))
                drawArc(Brush.sweepGradient(listOf(color.copy(alpha = 0.5f), color, color.copy(alpha = 0.5f))),
                    -90f, 360f * p, false, style = Stroke(w, cap = StrokeCap.Round))
            }
        }
        content()
    }
}

/** Ponto pulsante (ao vivo, gravando, atenção). */
@Composable
fun PulseDot(color: Color, size: Int = 10) {
    val t = rememberInfiniteTransition(label = "pulse")
    val a by t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "a")
    val s by t.animateFloat(1f, 2.2f, infiniteRepeatable(tween(1200), RepeatMode.Restart), label = "s")
    Box(Modifier.size((size * 2.4).dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(size.dp).scale(s).clip(CircleShape).background(color.copy(alpha = (2.2f - s) / 3f)))
        Box(Modifier.size(size.dp).clip(CircleShape).background(color.copy(alpha = a)))
    }
}

// ------------------------------------------------------------------ cartões

@Composable
fun AppCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier.fillMaxWidth().let { if (onClick != null) it.clip(RoundedCornerShape(20.dp)).pressable(onClick = onClick) else it },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, Hairline),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

/** Cartão pequeno com ícone, rótulo e valor. */
@Composable
fun StatCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    color: Color? = null,
    icon: ImageVector? = null,
    sub: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val c by animateColorAsState(color ?: MaterialTheme.colorScheme.onSurface, label = "stat")
    Card(
        modifier.let { if (onClick != null) it.clip(RoundedCornerShape(16.dp)).pressable(onClick = onClick) else it },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, Hairline),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(icon, null, Modifier.size(14.dp), tint = color ?: MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(5.dp))
                }
                Text(label, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(4.dp))
            FitText(value, Tabular.copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold), color = c)
            if (sub != null) Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Ícone dentro de um quadrado arredondado colorido. */
@Composable
fun IconBadge(icon: ImageVector, color: Color = Accent, size: Int = 28) {
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape((size * 0.32).dp)).background(color.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, Modifier.size((size * 0.58).dp), tint = color) }
}

/** Seção com título (e ícone opcional). */
@Composable
fun Section(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    AppCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) { Icon(icon, null, Modifier.size(18.dp), tint = Accent); Spacer(Modifier.width(10.dp)) }
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            trailing?.invoke()
        }
        content()
    }
}

/** Botão-bloco com ícone e rótulo (atalhos). */
@Composable
fun ActionTile(icon: ImageVector, label: String, modifier: Modifier = Modifier, color: Color = Accent, sub: String? = null, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
            .border(BorderStroke(1.dp, Hairline), RoundedCornerShape(18.dp))
            .pressable(onClick = onClick).padding(horizontal = 8.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        IconBadge(icon, color, 38)
        Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, maxLines = 2,
            overflow = TextOverflow.Ellipsis, lineHeight = 16.sp)
        if (sub != null) Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, maxLines = 1)
    }
}

/** Linha de menu: ícone, título, descrição e seta. */
@Composable
fun MenuRow(icon: ImageVector, title: String, sub: String? = null, color: Color = Accent, trailing: @Composable (() -> Unit)? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).pressable(onClick = onClick).padding(vertical = 10.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(icon, color, 36)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke()
    }
}

enum class Tone { INFO, GOOD, WARN, BAD }

/** Caixa de destaque com ícone, para interpretações e avisos. */
@Composable
fun Callout(text: String, tone: Tone = Tone.INFO, modifier: Modifier = Modifier, title: String? = null) {
    val (c, icon) = when (tone) {
        Tone.INFO -> Cyan to Icons.Filled.Info
        Tone.GOOD -> Good to Icons.Filled.CheckCircle
        Tone.WARN -> Warn to Icons.Filled.Warning
        Tone.BAD -> Bad to Icons.Filled.Error
    }
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.copy(alpha = 0.10f)).padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = c)
        Spacer(Modifier.width(10.dp))
        Column {
            if (title != null) Text(title, style = MaterialTheme.typography.titleSmall, color = c)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/** Pílula de status: ponto colorido + texto. */
@Composable
fun StatusPill(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(CircleShape).background(color.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Tela vazia com ilustração (ícone grande), título, texto e ação. */
@Composable
fun EmptyState(icon: ImageVector, title: String, text: String, action: (@Composable () -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 28.dp, horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val t = rememberInfiniteTransition(label = "float")
        val dy by t.animateFloat(-4f, 4f, infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "dy")
        Box(Modifier.padding(top = (8 + dy).dp)) { IconBadge(icon, Accent, 76) }
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        action?.invoke()
    }
}

// ------------------------------------------------------------------ gráficos

/** Gráfico de linha com área em degradê, grade discreta e linha de referência opcional. */
@Composable
fun LineChart(
    points: List<Double?>,
    modifier: Modifier = Modifier,
    min: Double? = null,
    max: Double? = null,
    color: Color = Accent,
    reference: Double? = null,
    height: Int = 120,
) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    val refColor = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier.fillMaxWidth().height(height.dp)) {
        for (i in 1..3) {
            val y = size.height * i / 4f
            drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        }
        val vals = points.filterNotNull()
        if (vals.size < 2) return@Canvas
        val lo = min ?: vals.min()
        val hi = (max ?: vals.max()).let { if (it - lo < 1e-6) lo + 1 else it }
        val pad = 4.dp.toPx()
        fun y(v: Double) = pad + (size.height - 2 * pad) * (1 - ((v - lo) / (hi - lo)).coerceIn(0.0, 1.0)).toFloat()
        reference?.let {
            drawLine(refColor.copy(alpha = 0.5f), Offset(0f, y(it)), Offset(size.width, y(it)),
                strokeWidth = 2f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
        }
        val step = size.width / (points.size - 1).coerceAtLeast(1)
        val line = Path()
        var firstX = 0f; var lastX = 0f; var lastY = 0f; var started = false
        points.forEachIndexed { i, v ->
            if (v == null) return@forEachIndexed
            val x = i * step
            if (!started) { line.moveTo(x, y(v)); firstX = x; started = true } else line.lineTo(x, y(v))
            lastX = x; lastY = y(v)
        }
        val area = Path().apply { addPath(line); lineTo(lastX, size.height); lineTo(firstX, size.height); close() }
        drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.30f), Color.Transparent)))
        drawPath(line, color, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(color, 4.dp.toPx(), Offset(lastX, lastY))
        drawCircle(color.copy(alpha = 0.25f), 8.dp.toPx(), Offset(lastX, lastY))
    }
}

/** Barras verticais simples (ex.: consumo por viagem). */
@Composable
fun BarChart(values: List<Double?>, modifier: Modifier = Modifier, color: Color = Accent, height: Int = 110, highlightLast: Boolean = true) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val anim by animateFloatAsState(1f, tween(700), label = "bars")
    Canvas(modifier.fillMaxWidth().height(height.dp)) {
        val vals = values.filterNotNull()
        if (vals.isEmpty()) return@Canvas
        val hi = vals.max().coerceAtLeast(1e-6)
        val n = values.size
        val gap = 6.dp.toPx()
        val bw = (size.width - gap * (n - 1)) / n
        values.forEachIndexed { i, v ->
            val x = i * (bw + gap)
            drawRoundRect(track, Offset(x, 0f), Size(bw, size.height), CornerRadius(6.dp.toPx()))
            if (v != null) {
                val h = (v / hi).toFloat() * size.height * anim
                val c = if (highlightLast && i == n - 1) color else color.copy(alpha = 0.55f)
                drawRoundRect(c, Offset(x, size.height - h), Size(bw, h), CornerRadius(6.dp.toPx()))
            }
        }
    }
}

/** Barra horizontal de proporção (ex.: tempo em cada faixa de rotação). */
@Composable
fun ShareBar(label: String, frac: Float, color: Color = Accent, value: String? = null) {
    val f by animateFloatAsState(frac.coerceIn(0f, 1f), tween(600), label = "share")
    val track = MaterialTheme.colorScheme.surfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row {
            Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Text(value ?: "%.0f%%".format(frac * 100), style = MaterialTheme.typography.bodySmall.merge(Tabular),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Canvas(Modifier.fillMaxWidth().height(8.dp)) {
            drawRoundRect(track, cornerRadius = CornerRadius(size.height / 2))
            drawRoundRect(color, size = Size(size.width * f, size.height), cornerRadius = CornerRadius(size.height / 2))
        }
    }
}

/** Barra centrada em zero para correções de combustível (−range..+range). */
@Composable
fun TrimBar(value: Double?, range: Double = 25.0, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val mid0 = MaterialTheme.colorScheme.onSurface
    val c = trimColor(value)
    val frac by animateFloatAsState(((value ?: 0.0) / range).coerceIn(-1.0, 1.0).toFloat(), label = "trim")
    Canvas(modifier.fillMaxWidth().height(8.dp)) {
        drawRoundRect(track, cornerRadius = CornerRadius(size.height / 2))
        val mid = size.width / 2
        if (value != null) {
            val w = frac * mid
            val left = if (w < 0) mid + w else mid
            drawRoundRect(c, topLeft = Offset(left, 0f), size = Size(abs(w), size.height), cornerRadius = CornerRadius(size.height / 2))
        }
        drawLine(mid0.copy(alpha = 0.6f), Offset(mid, -3f), Offset(mid, size.height + 3f), 2f)
    }
}

fun trimColor(v: Double?): Color = when {
    v == null -> Color.Gray
    abs(v) <= 10 -> Good
    abs(v) <= 20 -> Warn
    else -> Bad
}

// ------------------------------------------------------------------ texto

@Composable
fun Mono(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
fun Hint(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
fun KeyValue(key: String, value: String, valueColor: Color? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(key, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium.merge(Tabular), fontWeight = FontWeight.Medium,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.End)
    }
}

@Composable
fun Step(n: Int, text: String, color: Color = Accent) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(color.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Text("$n", style = MaterialTheme.typography.labelMedium, color = color)
        }
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
    }
}

/** Formatação curta de números (com unidade) e "—" quando não há valor. */
fun fmt(v: Double?, pattern: String = "%.0f", unit: String = "") = v?.let { pattern.format(it) + unit } ?: "—"


// ------------------------------------------------------------------ responsivo

/**
 * Texto de uma linha que diminui a fonte até caber na largura disponível, em vez de
 * quebrar no meio da palavra. Usado em números, botões e títulos curtos.
 */
@Composable
fun FitText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    minScale: Float = 0.55f,
    textAlign: TextAlign? = null,
) {
    var scale by remember(text.length, style.fontSize) { mutableFloatStateOf(1f) }
    var ready by remember(text.length, style.fontSize) { mutableStateOf(false) }
    Text(
        text,
        modifier.drawWithContent { if (ready) drawContent() },
        color = color,
        style = if (style.fontSize.isUnspecified) style else style.copy(fontSize = style.fontSize * scale),
        maxLines = 1,
        softWrap = false,
        textAlign = textAlign,
        onTextLayout = { r ->
            if (r.hasVisualOverflow && scale > minScale) scale = (scale * 0.9f).coerceAtLeast(minScale) else ready = true
        },
    )
}

/** Rótulo de botão: uma linha, encolhe se precisar. */
@Composable
fun BtnLabel(text: String) = FitText(text, MaterialTheme.typography.labelLarge)

/**
 * Grade que escolhe o número de colunas pela largura da tela (celular estreito, comum,
 * dobrável, tablet) e deixa as células da mesma linha com a mesma altura.
 */
@Composable
fun <T> AdaptiveGrid(
    items: List<T>,
    minCell: Dp = 104.dp,
    maxCols: Int = 4,
    spacing: Dp = 10.dp,
    cell: @Composable (T, Modifier) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cols = ((maxWidth + spacing) / (minCell + spacing)).toInt().coerceIn(1, maxCols)
        Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
            items.chunked(cols).forEach { row ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(spacing)) {
                    row.forEach { cell(it, Modifier.weight(1f).fillMaxHeight()) }
                    repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}
