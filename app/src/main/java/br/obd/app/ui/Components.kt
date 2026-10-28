package br.obd.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Good = Color(0xFF66BB6A)
val Warn = Color(0xFFFFB74D)
val Bad = Color(0xFFEF5350)

private val Scheme = darkColorScheme(
    primary = Color(0xFFFFA726),
    onPrimary = Color(0xFF1A1100),
    secondary = Color(0xFF4DD0E1),
    background = Color(0xFF0D1117),
    onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF0D1117),
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF1C2330),
    onSurfaceVariant = Color(0xFF9AA7B4),
    surfaceContainer = Color(0xFF161B22),
    surfaceContainerHigh = Color(0xFF1C2330),
    error = Bad,
)

@Composable
fun ScannerTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = Scheme, content = content)

/** Mostrador em arco de 270°. */
@Composable
fun Gauge(
    value: Double?,
    max: Double,
    label: String,
    unit: String,
    modifier: Modifier = Modifier,
    warnAt: Double? = null,
) {
    val frac by animateFloatAsState(((value ?: 0.0) / max).coerceIn(0.0, 1.0).toFloat(), label = "gauge")
    val track = MaterialTheme.colorScheme.surfaceVariant
    val color = if (warnAt != null && (value ?: 0.0) >= warnAt) Bad else MaterialTheme.colorScheme.primary
    Box(modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(6.dp)) {
            val w = size.minDimension * 0.07f
            inset(w / 2) {
                drawArc(track, 135f, 270f, false, style = Stroke(w, cap = StrokeCap.Round))
                if (frac > 0f) drawArc(color, 135f, 270f * frac, false, style = Stroke(w, cap = StrokeCap.Round))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                value?.let { "%.0f".format(it) } ?: "—",
                fontSize = 34.sp, fontWeight = FontWeight.Bold,
            )
            Text(unit, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(2.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Cartão pequeno com rótulo e valor. */
@Composable
fun StatCard(label: String, value: String, modifier: Modifier = Modifier, color: Color? = null) {
    Card(
        modifier,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                value, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                color = color ?: MaterialTheme.colorScheme.onSurface, maxLines = 1,
            )
        }
    }
}

/** Seção com título. */
@Composable
fun Section(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

/** Linha de gráfico simples (sem eixos), com faixa de referência opcional. */
@Composable
fun LineChart(
    points: List<Double?>,
    modifier: Modifier = Modifier,
    min: Double? = null,
    max: Double? = null,
    color: Color = MaterialTheme.colorScheme.primary,
    reference: Double? = null,
) {
    val grid = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier.fillMaxWidth().height(110.dp)) {
        val vals = points.filterNotNull()
        if (vals.size < 2) return@Canvas
        val lo = min ?: vals.min()
        val hi = (max ?: vals.max()).let { if (it - lo < 1e-6) lo + 1 else it }
        fun y(v: Double) = size.height * (1 - ((v - lo) / (hi - lo)).coerceIn(0.0, 1.0)).toFloat()
        reference?.let {
            drawLine(grid, Offset(0f, y(it)), Offset(size.width, y(it)), strokeWidth = 2f)
        }
        val step = size.width / (points.size - 1).coerceAtLeast(1)
        val path = Path()
        var started = false
        points.forEachIndexed { i, v ->
            if (v == null) return@forEachIndexed
            val x = i * step
            if (!started) { path.moveTo(x, y(v)); started = true } else path.lineTo(x, y(v))
        }
        drawPath(path, color, style = Stroke(4f, cap = StrokeCap.Round))
    }
}

/** Barra centrada em zero para correções de combustível (−range..+range). */
@Composable
fun TrimBar(value: Double?, range: Double = 25.0, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val c = trimColor(value)
    Canvas(modifier.fillMaxWidth().height(10.dp)) {
        drawRoundRect(track, cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f))
        val mid = size.width / 2
        drawLine(Color.White.copy(alpha = 0.5f), Offset(mid, 0f), Offset(mid, size.height), 2f)
        if (value != null) {
            val w = (value / range).coerceIn(-1.0, 1.0).toFloat() * mid
            val left = if (w < 0) mid + w else mid
            drawRect(c, topLeft = Offset(left, 0f), size = androidx.compose.ui.geometry.Size(kotlin.math.abs(w), size.height))
        }
    }
}

fun trimColor(v: Double?): Color = when {
    v == null -> Color.Gray
    kotlin.math.abs(v) <= 10 -> Good
    kotlin.math.abs(v) <= 20 -> Warn
    else -> Bad
}

@Composable
fun Mono(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)

@Composable
fun Hint(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
fun KeyValue(key: String, value: String, valueColor: Color? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(key, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.End)
    }
}
