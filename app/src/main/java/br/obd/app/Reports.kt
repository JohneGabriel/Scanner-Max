package br.obd.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Relatórios em PDF (A4, fundo claro para imprimir): viagem e saúde do veículo.
 * Desenhados à mão no Canvas — sem WebView, sem bibliotecas.
 */
object Reports {
    private const val W = 595f
    private const val H = 842f
    private const val M = 40f

    private val ACCENT = Color.rgb(0xF5, 0x8A, 0x00)
    private val INK = Color.rgb(0x1B, 0x22, 0x2C)
    private val MUTED = Color.rgb(0x6B, 0x77, 0x85)
    private val LINE = Color.rgb(0xE3, 0xE7, 0xEC)
    private val TILE = Color.rgb(0xF5, 0xF7, 0xFA)
    private val GOOD = Color.rgb(0x2E, 0x9E, 0x4F)
    private val WARN = Color.rgb(0xE0, 0x8A, 0x00)
    private val BAD = Color.rgb(0xD9, 0x3B, 0x3B)
    private val INFO = Color.rgb(0x1E, 0x88, 0xA8)

    private val ptBr = Locale("pt", "BR")
    private val dateFmt = SimpleDateFormat("dd/MM/yyyy 'às' HH:mm", ptBr)

    fun levelColor(l: Level?) = when (l) { Level.GOOD -> GOOD; Level.WARN -> WARN; Level.BAD -> BAD; Level.INFO -> INFO; null -> INK }

    /** Escreve as páginas em sequência, quebrando quando falta espaço. */
    private class Writer(val doc: PdfDocument, val title: String, val subtitle: String) {
        var page: PdfDocument.Page? = null
        var c: Canvas? = null
        var y = 0f
        var n = 0

        fun newPage() {
            page?.let { footer(); doc.finishPage(it) }
            n++
            page = doc.startPage(PdfDocument.PageInfo.Builder(W.toInt(), H.toInt(), n).create())
            c = page!!.canvas
            header()
        }

        fun ensure(h: Float) { if (page == null || y + h > H - 60) newPage() }

        private fun header() {
            val cv = c!!
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.color = INK
            cv.drawRect(0f, 0f, W, 74f, p)
            p.color = ACCENT
            cv.drawRect(0f, 74f, W, 77f, p)
            // marca: arco de mostrador
            p.style = Paint.Style.STROKE; p.strokeWidth = 4f; p.strokeCap = Paint.Cap.ROUND
            cv.drawArc(RectF(M, 20f, M + 34f, 54f), 135f, 270f, false, p)
            p.style = Paint.Style.FILL
            text(cv, "Scanner Max", M + 46f, 34f, 11f, Color.argb(200, 255, 255, 255), bold = true)
            text(cv, title, M + 46f, 54f, 18f, Color.WHITE, bold = true)
            text(cv, subtitle, W - M, 54f, 10f, Color.argb(200, 255, 255, 255), align = Paint.Align.RIGHT)
            y = 100f
        }

        fun footer() {
            val cv = c ?: return
            val p = Paint(); p.color = LINE
            cv.drawLine(M, H - 40f, W - M, H - 40f, p)
            text(cv, "Gerado pelo Scanner Max em ${SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR")).format(Date())}",
                M, H - 26f, 8f, MUTED)
            text(cv, "Página $n", W - M, H - 26f, 8f, MUTED, align = Paint.Align.RIGHT)
        }

        fun finish() { page?.let { footer(); doc.finishPage(it) }; page = null }
    }

    private fun text(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = false,
                     align: Paint.Align = Paint.Align.LEFT) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.textSize = size; p.color = color; p.textAlign = align
        p.typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
        c.drawText(s, x, y, p)
    }

    /** Parágrafo com quebra de linha; devolve a altura usada. */
    private fun paragraph(w: Writer, s: String, size: Float = 10f, color: Int = INK, width: Float = W - 2 * M, x: Float = M): Float {
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; this.color = color }
        @Suppress("DEPRECATION")
        val layout = StaticLayout(s, tp, width.toInt(), Layout.Alignment.ALIGN_NORMAL, 1.25f, 0f, false)
        w.ensure(layout.height.toFloat())
        val c = w.c!!
        c.save(); c.translate(x, w.y); layout.draw(c); c.restore()
        w.y += layout.height
        return layout.height.toFloat()
    }

    private fun sectionTitle(w: Writer, s: String) {
        w.ensure(40f)
        w.y += 12f
        text(w.c!!, s.uppercase(ptBr), M, w.y + 10f, 9.5f, ACCENT, bold = true)
        w.y += 20f
    }

    /** Grade de blocos (rótulo pequeno + valor grande). */
    private fun tiles(w: Writer, items: List<Triple<String, String, Int?>>, cols: Int = 4) {
        val gap = 8f
        val tw = (W - 2 * M - gap * (cols - 1)) / cols
        val th = 48f
        items.chunked(cols).forEach { row ->
            w.ensure(th + gap)
            row.forEachIndexed { i, (label, value, color) ->
                val x = M + i * (tw + gap)
                val p = Paint(Paint.ANTI_ALIAS_FLAG); p.color = TILE
                w.c!!.drawRoundRect(RectF(x, w.y, x + tw, w.y + th), 8f, 8f, p)
                if (color != null) { p.color = color; w.c!!.drawRoundRect(RectF(x, w.y + 8f, x + 3f, w.y + th - 8f), 2f, 2f, p) }
                text(w.c!!, label, x + 10f, w.y + 17f, 8f, MUTED)
                text(w.c!!, value, x + 10f, w.y + 37f, 14f, color ?: INK, bold = true)
            }
            w.y += th + gap
        }
    }

    /** Gráfico de linha com área preenchida. */
    private fun chart(w: Writer, label: String, values: List<Double?>, color: Int, min: Double? = null, max: Double? = null,
                      ref: Double? = null, unit: String = "", height: Float = 110f) {
        val vals = values.filterNotNull()
        if (vals.size < 2) return
        w.ensure(height + 34f)
        val c = w.c!!
        text(c, label, M, w.y + 10f, 9f, INK, bold = true)
        w.y += 18f
        val lo = min ?: vals.min(); val hi = (max ?: vals.max()).let { if (it - lo < 1e-6) lo + 1 else it }
        val r = RectF(M + 30f, w.y, W - M, w.y + height)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = LINE; p.strokeWidth = 0.7f
        for (i in 0..4) {
            val yy = r.top + r.height() * i / 4
            c.drawLine(r.left, yy, r.right, yy, p)
            val v = hi - (hi - lo) * i / 4
            text(c, "%.0f".format(v), r.left - 4f, yy + 3f, 7f, MUTED, align = Paint.Align.RIGHT)
        }
        fun py(v: Double) = r.bottom - (((v - lo) / (hi - lo)).coerceIn(0.0, 1.0) * r.height()).toFloat()
        val step = r.width() / (values.size - 1).coerceAtLeast(1)
        val path = Path(); val area = Path()
        var started = false; var firstX = 0f; var lastX = 0f
        values.forEachIndexed { i, v ->
            if (v == null) return@forEachIndexed
            val x = r.left + i * step
            if (!started) { path.moveTo(x, py(v)); firstX = x; started = true } else path.lineTo(x, py(v))
            lastX = x
        }
        area.addPath(path); area.lineTo(lastX, r.bottom); area.lineTo(firstX, r.bottom); area.close()
        p.style = Paint.Style.FILL; p.color = Color.argb(40, Color.red(color), Color.green(color), Color.blue(color))
        c.drawPath(area, p)
        p.style = Paint.Style.STROKE; p.color = color; p.strokeWidth = 1.6f; p.strokeJoin = Paint.Join.ROUND
        c.drawPath(path, p)
        ref?.let {
            p.color = MUTED; p.strokeWidth = 0.8f
            p.pathEffect = android.graphics.DashPathEffect(floatArrayOf(4f, 3f), 0f)
            c.drawLine(r.left, py(it), r.right, py(it), p)
            p.pathEffect = null
        }
        if (unit.isNotEmpty()) text(c, unit, r.right, r.top - 4f, 7f, MUTED, align = Paint.Align.RIGHT)
        w.y += height + 12f
    }

    /** Barras horizontais (distribuição). */
    private fun bars(w: Writer, items: List<Pair<String, Double>>, color: Int) {
        val total = items.sumOf { it.second }.takeIf { it > 0 } ?: return
        items.forEach { (label, v) ->
            w.ensure(18f)
            val c = w.c!!
            text(c, label, M, w.y + 10f, 9f, INK)
            val x0 = M + 120f; val x1 = W - M - 50f
            val p = Paint(Paint.ANTI_ALIAS_FLAG); p.color = TILE
            c.drawRoundRect(RectF(x0, w.y + 2f, x1, w.y + 12f), 5f, 5f, p)
            p.color = color
            c.drawRoundRect(RectF(x0, w.y + 2f, x0 + (x1 - x0) * (v / total).toFloat(), w.y + 12f), 5f, 5f, p)
            text(c, "%.0f%%".format(v / total * 100), W - M, w.y + 10f, 9f, MUTED, align = Paint.Align.RIGHT)
            w.y += 18f
        }
    }

    private fun keyValues(w: Writer, rows: List<Pair<String, String>>) {
        rows.forEach { (k, v) ->
            w.ensure(16f)
            text(w.c!!, k, M, w.y + 10f, 9.5f, MUTED)
            text(w.c!!, v, W - M, w.y + 10f, 9.5f, INK, bold = true, align = Paint.Align.RIGHT)
            val p = Paint(); p.color = LINE
            w.c!!.drawLine(M, w.y + 15f, W - M, w.y + 15f, p)
            w.y += 18f
        }
    }

    private fun bullets(w: Writer, items: List<String>, numbered: Boolean = false) {
        items.forEachIndexed { i, s ->
            val prefix = if (numbered) "${i + 1}." else "•"
            w.ensure(14f)
            text(w.c!!, prefix, M + 4f, w.y + 10f, 10f, ACCENT, bold = true)
            paragraph(w, s, 10f, INK, W - 2 * M - 22f, M + 22f)
            w.y += 3f
        }
    }

    private fun out(ctx: Context, name: String): File =
        File(File(ctx.cacheDir, "logs").apply { mkdirs() }, name)

    fun hm(sec: Double): String {
        val m = (sec / 60).toInt()
        return if (m >= 60) "${m / 60}h${"%02d".format(m % 60)}" else "${m} min"
    }

    fun num(v: Double?, d: Int = 1) = v?.let { String.format(ptBr, "%.${d}f", it) } ?: "—"
    fun money(v: Double) = String.format(ptBr, "R$ %.2f", v)

    // ------------------------------------------------------------------ viagem

    fun trip(ctx: Context, s: TripSummary, pts: List<TripPoint>, profile: VehicleProfile?): File {
        val doc = PdfDocument()
        val w = Writer(doc, "Relatório de viagem", dateFmt.format(Date(s.start)))
        w.newPage()
        paragraph(w, listOfNotNull(s.vehicleName ?: profile?.name, profile?.fuelLabel,
            profile?.let { "motor ${num(it.displacementL)} L" }).joinToString("  ·  "), 11f, MUTED)
        w.y += 6f
        tiles(w, listOf(
            Triple("Distância", "${num(s.km)} km", null),
            Triple("Duração", hm(s.durationSec), null),
            Triple("Consumo", s.kmPerL?.let { "${num(it)} km/L" } ?: "—", ACCENT),
            Triple("Combustível", "${num(s.liters, 2)} L", null),
            Triple("Custo estimado", money(s.cost), null),
            Triple("Velocidade média", "${num(s.avgSpeed, 0)} km/h", null),
            Triple("Velocidade máxima", "${num(s.maxSpeed, 0)} km/h", null),
            Triple("Rotação máxima", "${num(s.maxRpm, 0)} rpm", null),
            Triple("Temp. média do motor", s.avgCoolant?.let { "${num(it, 0)} °C" } ?: "—", null),
            Triple("Temp. máxima", s.maxCoolant?.let { "${num(it, 0)} °C" } ?: "—", s.maxCoolant?.let { if (it > 105) BAD else null }),
            Triple("Tempo parado", "${num(s.idlePct, 0)}%", null),
            Triple("Bateria (mín.)", s.minBattery?.let { "${num(it)} V" } ?: "—", null),
        ))
        sectionTitle(w, "Gráficos")
        chart(w, "Velocidade", pts.map { it.speed }, INFO, 0.0, null, unit = "km/h")
        chart(w, "Rotação", pts.map { it.rpm }, ACCENT, 0.0, null, unit = "rpm")
        chart(w, "Temperatura do motor", pts.map { it.coolant }, BAD, null, null, 90.0, "°C", 90f)
        chart(w, "Consumo instantâneo", pts.map { it.lph }, GOOD, 0.0, null, unit = "L/h", height = 90f)

        sectionTitle(w, "Como você dirigiu")
        bars(w, listOf("Até 1.500 rpm" to s.rpmBands.getOrElse(0) { 0.0 }, "1.500 – 2.500 rpm" to s.rpmBands.getOrElse(1) { 0.0 },
            "2.500 – 3.500 rpm" to s.rpmBands.getOrElse(2) { 0.0 }, "Acima de 3.500 rpm" to s.rpmBands.getOrElse(3) { 0.0 }), ACCENT)
        w.y += 6f
        keyValues(w, listOf(
            "Acelerações bruscas" to "${s.hardAccel}",
            "Frenagens bruscas" to "${s.hardBrake}",
            "Tempo em corte de combustível (descidas, desacelerações)" to hm(s.fuelCutSec),
            "Tempo em marcha lenta" to hm(s.idleSec),
            "Aquecimento até 80 °C" to (s.warmupSec?.let { hm(it) } ?: if ((s.startCoolant ?: 0.0) >= 50) "já estava quente" else "não chegou"),
        ))
        sectionTitle(w, "Sobre os números")
        paragraph(w, consumptionNote(s, profile), 9f, MUTED)
        w.finish()
        val f = out(ctx, "viagem-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(s.start))}.pdf")
        f.outputStream().use { doc.writeTo(it) }
        doc.close()
        return f
    }

    fun consumptionNote(s: TripSummary?, p: VehicleProfile?): String = buildString {
        if (s?.estimated == false) append("O consumo vem direto da central (PID de taxa de combustível). ")
        else {
            append("O consumo é estimado a partir do ar que o motor admite (pressão do coletor, rotação e temperatura, ")
            append("ou o sensor de fluxo de ar quando existe), dividido pela proporção ar/combustível de ${p?.fuelLabel ?: "gasolina"}. ")
            if ((p?.calibCount ?: 0) > 0) append("A estimativa está calibrada com ${p!!.calibCount} abastecimento(s) (fator ${num(p.calib, 2)}). ")
            else append("Ainda não foi calibrada com um abastecimento real: a margem de erro pode passar de 15%. Calibre no menu Veículo. ")
        }
        append("Distância calculada pela velocidade lida da central a cada instante.")
    }

    // ------------------------------------------------------------------ saúde do veículo

    fun health(ctx: Context, p: VehicleProfile?, diag: DiagState?, tests: List<TestReport>, trips: List<TripSummary>,
               adapter: AdapterProfile?): File {
        val doc = PdfDocument()
        val w = Writer(doc, "Relatório do veículo", dateFmt.format(Date()))
        w.newPage()
        text(w.c!!, p?.name ?: "Veículo", M, w.y + 16f, 18f, INK, bold = true)
        w.y += 26f
        paragraph(w, listOfNotNull(p?.maker, p?.year?.toString(), p?.vin?.let { "chassi $it" }, p?.protocol).joinToString("  ·  "), 10f, MUTED)
        w.y += 6f

        // situação geral
        val codes = (diag?.stored.orEmpty() + diag?.pending.orEmpty())
        val worst = tests.groupBy { it.testId }.values.map { it.first() }
        val bad = worst.count { it.level == Level.BAD } + (diag?.stored?.size ?: 0)
        val warn = worst.count { it.level == Level.WARN } + (diag?.pending?.size ?: 0)
        val (head, color) = when {
            bad > 0 -> "Precisa de atenção" to BAD
            warn > 0 -> "Alguns pontos para acompanhar" to WARN
            diag == null && worst.isEmpty() -> "Sem dados suficientes" to MUTED
            else -> "Tudo em ordem" to GOOD
        }
        w.ensure(60f)
        val pnt = Paint(Paint.ANTI_ALIAS_FLAG); pnt.color = Color.argb(28, Color.red(color), Color.green(color), Color.blue(color))
        w.c!!.drawRoundRect(RectF(M, w.y, W - M, w.y + 50f), 10f, 10f, pnt)
        pnt.color = color
        w.c!!.drawCircle(M + 22f, w.y + 25f, 8f, pnt)
        text(w.c!!, head, M + 40f, w.y + 22f, 14f, color, bold = true)
        text(w.c!!, "${codes.size} código(s) de falha · ${worst.size} teste(s) recente(s) · ${trips.size} viagem(ns) nos últimos 30 dias",
            M + 40f, w.y + 38f, 9f, MUTED)
        w.y += 60f

        sectionTitle(w, "Códigos de falha")
        if (diag?.readAt == null) paragraph(w, "O diagnóstico não foi lido nesta sessão. Leia na aba Diagnóstico antes de gerar o relatório.", 10f, MUTED)
        else if (codes.isEmpty()) paragraph(w, "Nenhum código registrado na central (lido em ${dateFmt.format(Date(diag.readAt))}).", 10f, GOOD)
        else {
            diag.stored.orEmpty().forEach { paragraph(w, "$it  —  ${DtcInfo.describe(it)} (confirmado)", 10f, BAD) }
            diag.pending.orEmpty().forEach { paragraph(w, "$it  —  ${DtcInfo.describe(it)} (pendente)", 10f, WARN) }
        }
        diag?.readiness?.let { r ->
            w.y += 6f
            paragraph(w, "Luz de injeção ${if (r.milOn) "ACESA" else "apagada"} · prontidão: ${r.monitors.count { it.second }} de ${r.monitors.size} monitores completos", 10f, INK)
            val inc = r.monitors.filter { !it.second }.map { it.first }
            if (inc.isNotEmpty()) paragraph(w, "Incompletos: ${inc.joinToString(", ")}", 9f, MUTED)
        }

        sectionTitle(w, "Testes")
        if (worst.isEmpty()) paragraph(w, "Nenhum teste guiado rodado ainda.", 10f, MUTED)
        worst.forEach { t ->
            w.ensure(50f)
            val c = w.c!!
            val pp = Paint(Paint.ANTI_ALIAS_FLAG); pp.color = levelColor(t.level)
            c.drawCircle(M + 5f, w.y + 7f, 4f, pp)
            text(c, t.title, M + 16f, w.y + 11f, 11f, INK, bold = true)
            text(c, SimpleDateFormat("dd/MM/yyyy", ptBr).format(Date(t.at)), W - M, w.y + 11f, 9f, MUTED, align = Paint.Align.RIGHT)
            w.y += 16f
            paragraph(w, t.headline, 10f, levelColor(t.level), W - 2 * M - 16f, M + 16f)
            val ms = t.metrics.take(4).joinToString("  ·  ") { "${it.label}: ${it.value}" }
            if (ms.isNotBlank()) paragraph(w, ms, 8.5f, MUTED, W - 2 * M - 16f, M + 16f)
            w.y += 8f
        }

        if (trips.isNotEmpty()) {
            sectionTitle(w, "Uso nos últimos 30 dias")
            val t = TripTotals.of(trips)
            tiles(w, listOf(
                Triple("Viagens", "${t.count}", null),
                Triple("Distância", "${num(t.km, 0)} km", null),
                Triple("Consumo médio", t.kmPerL?.let { "${num(it)} km/L" } ?: "—", ACCENT),
                Triple("Gasto estimado", money(t.cost), null),
                Triple("Tempo ao volante", hm(t.runSec), null),
                Triple("Parado em lenta", if (t.runSec > 0) "${num(t.idleSec / t.runSec * 100, 0)}%" else "—", null),
                Triple("Temp. média", t.avgCoolant?.let { "${num(it, 0)} °C" } ?: "—", null),
                Triple("Velocidade máx.", "${num(t.maxSpeed, 0)} km/h", null),
            ))
            chart(w, "Consumo por viagem (km/L)", trips.reversed().map { it.kmPerL }, ACCENT, 0.0, null, unit = "km/L", height = 80f)
        }

        if (adapter != null && adapter.reported != "—") {
            sectionTitle(w, "Adaptador usado")
            paragraph(w, "${adapter.summary}. ${adapter.verdict}", 9f, MUTED)
        }
        w.finish()
        val f = out(ctx, "relatorio-${(p?.name ?: "veiculo").replace(Regex("[^A-Za-z0-9]+"), "-").lowercase()}-${SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())}.pdf")
        f.outputStream().use { doc.writeTo(it) }
        doc.close()
        return f
    }

    // ------------------------------------------------------------------ um teste

    fun test(ctx: Context, t: TestReport): File {
        val doc = PdfDocument()
        val w = Writer(doc, t.title, dateFmt.format(Date(t.at)))
        w.newPage()
        t.vehicleName?.let { paragraph(w, it, 11f, MUTED) }
        w.y += 4f
        text(w.c!!, t.headline, M, w.y + 18f, 18f, levelColor(t.level), bold = true)
        w.y += 30f
        paragraph(w, t.explanation, 10.5f, INK)
        t.note?.let { w.y += 4f; paragraph(w, it, 9f, MUTED) }
        sectionTitle(w, "Medições")
        tiles(w, t.metrics.map { Triple(it.label, it.value, it.level?.let { l -> levelColor(l) }) }, 3)
        chart(w, t.chartLabel, t.chart, ACCENT, t.chartMin, t.chartMax, t.chartRef, height = 130f)
        sectionTitle(w, "O que fazer")
        bullets(w, t.nextSteps, numbered = true)
        w.finish()
        val f = out(ctx, "teste-${t.testId}-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(t.at))}.pdf")
        f.outputStream().use { doc.writeTo(it) }
        doc.close()
        return f
    }
}
