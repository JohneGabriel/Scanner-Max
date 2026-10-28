package br.obd.app.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

// ------------------------------------------------------------------ tema

val Accent = Color(0xFFFFA726)
val Cyan = Color(0xFF4DD0E1)
val Good = Color(0xFF66BB6A)
val Warn = Color(0xFFFFB74D)
val Bad = Color(0xFFEF5350)
private val Hairline = Color(0x14FFFFFF)

private val Scheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF1F1300),
    primaryContainer = Color(0xFF3A2A12),
    onPrimaryContainer = Color(0xFFFFDDB3),
    secondary = Cyan,
    secondaryContainer = Color(0xFF16323A),
    onSecondaryContainer = Color(0xFFBDEFF6),
    background = Color(0xFF0A0D12),
    onBackground = Color(0xFFE8EDF2),
    surface = Color(0xFF0A0D12),
    onSurface = Color(0xFFE8EDF2),
    surfaceVariant = Color(0xFF1E2531),
    onSurfaceVariant = Color(0xFF93A1B0),
    surfaceContainer = Color(0xFF121821),
    surfaceContainerHigh = Color(0xFF18202B),
    surfaceContainerHighest = Color(0xFF1E2531),
    outline = Color(0xFF2A3341),
    outlineVariant = Color(0xFF1E2531),
    error = Bad,
)

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
fun ScannerTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = Scheme, typography = AppTypography, content = content)

// ------------------------------------------------------------------ mostrador

/**
 * Mostrador em arco de 270° com marcas, faixa vermelha opcional e ponteiro de arco
 * em degradê. O número fica no centro com dígitos de largura fixa.
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
    scaleLabel: (Double) -> String = { "%.0f".format(it) },
) {
    val frac by animateFloatAsState(
        ((value ?: 0.0) / max).coerceIn(0.0, 1.0).toFloat(),
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow),
        label = "gauge",
    )
    val track = MaterialTheme.colorScheme.surfaceVariant
    val tick = MaterialTheme.colorScheme.onSurfaceVariant
    val hot = warnAt != null && (value ?: 0.0) >= warnAt
    val arc = if (hot) Bad else Accent

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

                // marcas de escala
                val steps = (max / majorStep).toInt()
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
                        strokeWidth = if (major) 3f else 2f,
                        cap = StrokeCap.Round,
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
                            Brush.sweepGradient(
                                0f to arc.copy(alpha = 0.55f), 0.75f to arc, 1f to arc.copy(alpha = 0.55f),
                                center = center, // centro dentro do inset
                            ),
                            135f, 270f * frac, false, style = Stroke(w, cap = StrokeCap.Round),
                        )
                    }
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    value?.let { "%.0f".format(it) } ?: "—",
                    style = Tabular, fontSize = 38.sp, fontWeight = FontWeight.Bold,
                    color = if (hot) Bad else MaterialTheme.colorScheme.onSurface,
                )
                Text(unit, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                label.uppercase(),
                Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ------------------------------------------------------------------ cartões

@Composable
fun AppCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier.fillMaxWidth(),
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
) {
    Card(
        modifier,
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                value, style = Tabular, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                color = color ?: MaterialTheme.colorScheme.onSurface, maxLines = 1,
            )
        }
    }
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
            if (icon != null) {
                Box(
                    Modifier.size(28.dp).clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) { Icon(icon, null, Modifier.size(16.dp), tint = Accent) }
                Spacer(Modifier.width(10.dp))
            }
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            trailing?.invoke()
        }
        content()
    }
}

enum class Tone { INFO, GOOD, WARN, BAD }

/** Caixa de destaque com ícone, para interpretações e avisos. */
@Composable
fun Callout(text: String, tone: Tone = Tone.INFO, modifier: Modifier = Modifier) {
    val (c, icon) = when (tone) {
        Tone.INFO -> Cyan to Icons.Filled.Info
        Tone.GOOD -> Good to Icons.Filled.CheckCircle
        Tone.WARN -> Warn to Icons.Filled.Warning
        Tone.BAD -> Bad to Icons.Filled.Warning
    }
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.copy(alpha = 0.10f))
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = c)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
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
        Text(text, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1)
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
            drawLine(Color.White.copy(alpha = 0.25f), Offset(0f, y(it)), Offset(size.width, y(it)),
                strokeWidth = 2f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
        }
        val step = size.width / (points.size - 1).coerceAtLeast(1)
        val line = Path()
        var firstX = 0f; var lastX = 0f; var started = false
        points.forEachIndexed { i, v ->
            if (v == null) return@forEachIndexed
            val x = i * step
            if (!started) { line.moveTo(x, y(v)); firstX = x; started = true } else line.lineTo(x, y(v))
            lastX = x
        }
        val area = Path().apply {
            addPath(line)
            lineTo(lastX, size.height); lineTo(firstX, size.height); close()
        }
        drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.30f), Color.Transparent)))
        drawPath(line, color, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Barra centrada em zero para correções de combustível (−range..+range). */
@Composable
fun TrimBar(value: Double?, range: Double = 25.0, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val c = trimColor(value)
    val frac by animateFloatAsState(((value ?: 0.0) / range).coerceIn(-1.0, 1.0).toFloat(), label = "trim")
    Canvas(modifier.fillMaxWidth().height(8.dp)) {
        drawRoundRect(track, cornerRadius = CornerRadius(size.height / 2))
        val mid = size.width / 2
        if (value != null) {
            val w = frac * mid
            val left = if (w < 0) mid + w else mid
            drawRoundRect(c, topLeft = Offset(left, 0f), size = Size(abs(w), size.height),
                cornerRadius = CornerRadius(size.height / 2))
        }
        drawLine(Color.White.copy(alpha = 0.6f), Offset(mid, -3f), Offset(mid, size.height + 3f), 2f)
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
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Text(key, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium.merge(Tabular), fontWeight = FontWeight.Medium,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.End)
    }
}
