package br.obd.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.PhonelinkErase
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import br.obd.app.ActPhase
import br.obd.app.Feedback
import br.obd.app.LicStatus
import br.obd.app.LicView
import br.obd.app.LicenseLogic
import br.obd.app.Licensing
import br.obd.app.ObdManager
import br.obd.app.Repos
import br.obd.app.UpdState
import br.obd.app.Updater
import br.obd.app.link.LinkTarget
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ------------------------------------------------------------------ quando abrir sozinha

/** A tela de ativação deve abrir sozinha agora? (no máximo 1x por dia, salvo quando é urgente) */
fun licenseShouldPrompt(v: LicView): Boolean {
    if (!Licensing.enforced) return false
    val day = System.currentTimeMillis() - Licensing.lastPrompt > 20 * 3600 * 1000
    return when (v.status) {
        LicStatus.OFF, LicStatus.ACTIVE -> false
        LicStatus.PENDING -> !Licensing.welcomed || v.leftMs < 24 * 3600 * 1000 || day
        LicStatus.NONE -> day
        else -> true // prazo vencido, limite, bloqueada, liberada, inválida
    }
}

// ------------------------------------------------------------------ tela cheia (abre sozinha)

@Composable
fun LicenseGate(app: AppActions, onClose: () -> Unit) {
    val v by Licensing.view.collectAsState()
    val phase by Licensing.phase.collectAsState()
    LaunchedEffect(Unit) {
        Licensing.markPrompted()
        Licensing.welcomed = true
        if (v.needsServer && phase != ActPhase.SENDING && phase != ActPhase.WAITING) Licensing.activate()
    }
    // ativou com a tela aberta: comemora e fecha sozinha
    LaunchedEffect(v.status) {
        if (v.status == LicStatus.ACTIVE) { delay(3200); onClose() }
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 520.dp).fillMaxSize().verticalScroll(rememberScrollState())
                .statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(18.dp))
            LicenseHero(v, phase)
            LicenseDetails(v, phase)
            LicenseActions(v, phase, page = false, app = app, onClose = onClose)
            Spacer(Modifier.height(12.dp))
        }
    }
}

// ------------------------------------------------------------------ página "Minha licença"

@Composable
fun LicenseScreen(app: AppActions) {
    val v by Licensing.view.collectAsState()
    val phase by Licensing.phase.collectAsState()
    LaunchedEffect(Unit) { Licensing.refresh(); Updater.check(force = true) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        LicenseHero(v, phase, compact = true)
        LicenseDetails(v, phase)
        LicenseActions(v, phase, page = true, app = app, onClose = {})
        UpdateSection()
        Spacer(Modifier.height(20.dp))
    }
}

// ------------------------------------------------------------------ partes

private data class Look(val color: Color, val icon: ImageVector?, val title: String, val text: String)

@Composable
private fun look(v: LicView, phase: ActPhase): Look {
    val running = phase == ActPhase.SENDING || phase == ActPhase.WAITING
    val devices = "Esta licença já está em ${v.used} de ${v.max} aparelhos."
    return when (v.status) {
        LicStatus.OFF -> Look(Good, Icons.Filled.VerifiedUser, "Tudo liberado", "Esta versão não exige ativação.")
        LicStatus.ACTIVE -> Look(Good, Icons.Filled.Check, if (phase == ActPhase.DONE) "Tudo pronto!" else "Licença ativa",
            "Seu Scanner Max está ativado neste aparelho. Bom diagnóstico!")
        LicStatus.PENDING -> when {
            running -> Look(Accent, null, "Ativando seu Scanner Max",
                "Isso leva de 30 segundos a 2 minutos. Você pode usar o app enquanto termina.")
            phase == ActPhase.OFFLINE -> Look(Warn, Icons.Filled.CloudOff, "Falta só a internet",
                "Conecte ao Wi-Fi ou aos dados móveis para concluir. O app funciona normalmente até o prazo acabar.")
            else -> Look(Accent, Icons.Filled.HourglassBottom, "Falta ativar",
                "A ativação é automática: basta abrir o app com internet. Até lá, tudo funciona normalmente.")
        }
        LicStatus.EXPIRED -> Look(Warn, Icons.Filled.HourglassBottom, "O prazo para ativar acabou",
            if (running) "Tentando ativar agora…" else "Conecte à internet e toque em Ativar agora. Enquanto isso, só o modo demonstração funciona.")
        LicStatus.LIMIT -> Look(Bad, Icons.Filled.Devices, "Limite de aparelhos atingido",
            "$devices Libere a vaga em um deles (menu → Minha licença → Liberar este aparelho) ou aguarde: aparelhos sem abrir o app por ${LicenseLogic.IDLE_RELEASE_DAYS} dias liberam a vaga sozinhos.")
        LicStatus.RELEASED -> Look(Cyan, Icons.Filled.PhonelinkErase, "Vaga liberada",
            "Este aparelho não usa mais a licença. Para voltar a usar aqui, toque em Ativar de novo.")
        LicStatus.BLOCKED -> Look(Bad, Icons.Filled.Block, "Licença bloqueada",
            "Esta licença foi desativada. Fale com o vendedor pelo mesmo canal onde você comprou.")
        LicStatus.INVALID -> Look(Bad, Icons.Filled.ErrorOutline, "Licença inválida",
            "Este arquivo de instalação foi modificado. Instale de novo pelo link que você recebeu na compra.")
        LicStatus.NONE -> Look(Cyan, null, "Versão de demonstração",
            "Esta cópia não tem licença: dá para explorar tudo com um carro simulado. Já comprou? Instale pelo link pessoal que você recebeu; a ativação é automática.")
    }
}

/** Anel animado + título. Girando enquanto ativa; cheio e verde quando ativou. */
@Composable
private fun LicenseHero(v: LicView, phase: ActPhase, compact: Boolean = false) {
    val l = look(v, phase)
    val running = phase == ActPhase.SENDING || phase == ActPhase.WAITING
    val color by animateColorAsState(l.color, tween(500), label = "c")
    val inf = rememberInfiniteTransition(label = "spin")
    val angle by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "a")
    val breathe by inf.animateFloat(0.92f, 1.04f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "b")
    val frac = when {
        running -> 0.28f
        v.status == LicStatus.PENDING -> (v.leftMs.toFloat() / LicenseLogic.GRACE_MS).coerceIn(0.04f, 1f)
        else -> 1f
    }
    val sweep by animateFloatAsState(frac, tween(900), label = "f")
    val pop = remember { Animatable(0.6f) }
    LaunchedEffect(v.status) { pop.snapTo(0.6f); pop.animateTo(1f, spring(dampingRatio = 0.45f)) }
    val track = MaterialTheme.colorScheme.surfaceVariant
    val size = if (compact) 108 else 150

    Box(Modifier.size(size.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().scale(if (running) breathe else 1f)) {
            val w = this.size.minDimension * 0.07f
            val tl = androidx.compose.ui.geometry.Offset(w, w)
            val sz = androidx.compose.ui.geometry.Size(this.size.width - 2 * w, this.size.height - 2 * w)
            drawArc(track, 0f, 360f, false, tl, sz, style = Stroke(w))
            val start = if (running) angle - 90f else -90f
            drawArc(Brush.sweepGradient(listOf(color.copy(alpha = 0.35f), color, color.copy(alpha = 0.35f))),
                start, 360f * sweep, false, tl, sz, style = Stroke(w, cap = StrokeCap.Round))
        }
        Box(Modifier.size((size * 0.62).dp).clip(CircleShape).background(color.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            if (l.icon == null) LogoMark((size * 0.36).toInt())
            else Icon(l.icon, null, Modifier.size((size * 0.30).dp).scale(pop.value), tint = color)
        }
    }
    Text(l.title, style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    Text(l.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 8.dp))
}

/** Etapas da ativação, cota de aparelhos e prazo. */
@Composable
private fun LicenseDetails(v: LicView, phase: ActPhase) {
    val message by Licensing.message.collectAsState()
    val running = phase == ActPhase.SENDING || phase == ActPhase.WAITING
    if (v.status == LicStatus.OFF || v.status == LicStatus.NONE) {
        if (v.status == LicStatus.NONE) HowDevicesWork()
        return
    }
    // etapas (enquanto ativa ou logo depois)
    AnimatedVisibility(running || phase == ActPhase.DONE, enter = fadeIn() + slideInVertically { it / 4 }, exit = fadeOut()) {
        AppCard {
            val sent = phase != ActPhase.SENDING
            val done = phase == ActPhase.DONE
            ActStep("Pedido enviado", if (sent) StepState.DONE else StepState.RUNNING)
            ActStep("Licença conferida", when { done -> StepState.DONE; phase == ActPhase.WAITING -> StepState.RUNNING; else -> StepState.WAIT })
            ActStep(if (v.status == LicStatus.ACTIVE || !done) "Aparelho registrado" else "Resposta recebida",
                if (done) StepState.DONE else StepState.WAIT)
        }
    }
    AppCard {
        Text("Sua licença", style = MaterialTheme.typography.titleMedium)
        if (v.pedido.isNotEmpty()) KeyValue("Pedido", "final ${v.pedido}")
        // cota de aparelhos
        val known = v.status != LicStatus.PENDING && v.status != LicStatus.EXPIRED
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Aparelhos", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(if (known) "${v.used} de ${v.max} usados" else "até ${v.max}", style = MaterialTheme.typography.bodyMedium.merge(Tabular),
                fontWeight = FontWeight.Medium, color = if (v.status == LicStatus.LIMIT) Bad else MaterialTheme.colorScheme.onSurface)
        }
        DeviceSlots(if (known) v.used else 0, v.max)
        // prazo
        if (v.status == LicStatus.PENDING) {
            var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
            LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis(); Licensing.refresh() } }
            val urgent = v.leftMs < 24 * 3600 * 1000
            KeyValue("Prazo para ativar", LicenseLogic.formatLeft(v.leftMs), if (urgent) Warn else null)
            LinearProgressIndicator(
                progress = { (v.leftMs.toFloat() / LicenseLogic.GRACE_MS).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = if (urgent) Warn else Accent, trackColor = MaterialTheme.colorScheme.surfaceVariant,
                drawStopIndicator = {},
            )
        }
        if (v.status == LicStatus.ACTIVE && v.activatedAt > 0) KeyValue("Última confirmação", date(v.lastCheck))
        if (message.isNotEmpty() && !running && phase != ActPhase.IDLE) Hint(message)
    }
    if (v.status != LicStatus.ACTIVE || phase != ActPhase.DONE) HowDevicesWork()
}

/** Bolinhas de vaga: cheias = usadas. */
@Composable
private fun DeviceSlots(used: Int, max: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        repeat(max) { i ->
            val on = i < used
            val c by animateColorAsState(if (on) (if (used >= max) Warn else Accent) else MaterialTheme.colorScheme.surfaceVariant,
                tween(400, delayMillis = i * 120), label = "slot")
            Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(c))
        }
    }
}

@Composable
private fun HowDevicesWork() {
    Callout(
        "Cada licença vale para até 3 aparelhos. Trocou de celular? Libere a vaga no antigo em Minha licença. " +
            "Formatar o celular conta como aparelho novo. Aparelhos que ficam ${LicenseLogic.IDLE_RELEASE_DAYS} dias sem abrir o app liberam a vaga sozinhos.",
        Tone.INFO, title = "Como funcionam os aparelhos",
    )
}

private enum class StepState { WAIT, RUNNING, DONE }

@Composable
private fun ActStep(text: String, s: StepState) {
    val c by animateColorAsState(when (s) { StepState.DONE -> Good; StepState.RUNNING -> Accent; StepState.WAIT -> MaterialTheme.colorScheme.outline }, label = "st")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(24.dp).clip(CircleShape).background(c.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            when (s) {
                StepState.DONE -> Icon(Icons.Filled.Check, null, Modifier.size(15.dp), tint = c)
                StepState.RUNNING -> PulseDot(c, 7)
                StepState.WAIT -> Box(Modifier.size(6.dp).clip(CircleShape).background(c))
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium,
            color = if (s == StepState.WAIT) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
    }
}

/** Botões de cada situação. Na tela automática sempre há uma saída; na página, as ações completas. */
@Composable
private fun LicenseActions(v: LicView, phase: ActPhase, page: Boolean, app: AppActions, onClose: () -> Unit) {
    val running = phase == ActPhase.SENDING || phase == ActPhase.WAITING
    var confirmRelease by remember { mutableStateOf(false) }
    val demo = { onClose(); if (!ObdManager.connected.value) ObdManager.connect(LinkTarget.DEMO) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        @Composable fun Primary(t: String, enabled: Boolean = true, a: () -> Unit) =
            Button(onClick = { Feedback.tick(); a() }, enabled = enabled, modifier = Modifier.fillMaxWidth().height(52.dp)) { BtnLabel(t) }
        @Composable fun Secondary(t: String, a: () -> Unit) =
            OutlinedButton(onClick = a, modifier = Modifier.fillMaxWidth().height(50.dp)) { BtnLabel(t) }

        when (v.status) {
            LicStatus.OFF -> if (!page) Primary("Continuar", a = onClose)
            LicStatus.ACTIVE -> if (page) {
                Secondary("Verificar agora") { Licensing.activate() }
                TextButton(onClick = { confirmRelease = true }, Modifier.fillMaxWidth()) { BtnLabel("Liberar este aparelho") }
            } else Primary("Começar", a = onClose)
            LicStatus.PENDING -> {
                if (running) { if (!page) Primary("Usar enquanto ativa", a = onClose) }
                else {
                    Primary("Ativar agora") { Licensing.activate() }
                    if (!page) Secondary("Continuar sem esperar", onClose)
                }
            }
            LicStatus.EXPIRED, LicStatus.LIMIT -> {
                Primary(if (v.status == LicStatus.LIMIT) "Verificar de novo" else "Ativar agora", enabled = !running) { Licensing.activate() }
                Secondary("Usar a demonstração", demo)
            }
            LicStatus.RELEASED -> {
                Primary("Ativar de novo", enabled = !running) { Licensing.activate() }
                Secondary("Usar a demonstração", demo)
            }
            LicStatus.BLOCKED, LicStatus.INVALID -> Secondary("Usar a demonstração", demo)
            LicStatus.NONE -> {
                Primary("Explorar a demonstração", a = demo)
                Secondary("Conhecer o Scanner Max") { app.openUrl(Repos.SITE_URL) }
            }
        }
        if (!page && v.status != LicStatus.ACTIVE && v.status != LicStatus.OFF && !running && v.status != LicStatus.PENDING)
            TextButton(onClick = onClose, Modifier.fillMaxWidth()) { BtnLabel("Fechar") }
    }
    if (confirmRelease) androidx.compose.material3.AlertDialog(
        onDismissRequest = { confirmRelease = false },
        icon = { Icon(Icons.Filled.PhonelinkErase, null, tint = Warn) },
        title = { Text("Liberar este aparelho?") },
        text = { Text("A vaga fica livre para outro celular. Aqui, o app passa a funcionar só no modo demonstração até você ativar de novo.") },
        confirmButton = { TextButton(onClick = { confirmRelease = false; ObdManager.disconnect(); Licensing.releaseThisDevice() }) { Text("Liberar", color = Warn) } },
        dismissButton = { TextButton(onClick = { confirmRelease = false }) { Text("Cancelar") } },
    )
}

private fun date(ms: Long) = if (ms <= 0) "—" else SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR")).format(Date(ms))

// ------------------------------------------------------------------ atualizações

@Composable
private fun UpdateSection() {
    val s by Updater.state.collectAsState()
    Section("Atualizações", icon = Icons.Filled.SystemUpdate) {
        KeyValue("Versão instalada", AppInfo.version)
        when (val st = s) {
            is UpdState.Available, is UpdState.Downloading, is UpdState.Ready -> UpdateBody(st)
            UpdState.Checking -> { Hint("Procurando…"); LinearProgressIndicator(Modifier.fillMaxWidth()) }
            UpdState.UpToDate -> Callout("Você está na versão mais recente.", Tone.GOOD)
            is UpdState.Failed -> { Callout(st.msg, Tone.WARN); OutlinedButton(onClick = { Updater.check(true) }, Modifier.fillMaxWidth()) { BtnLabel("Tentar de novo") } }
            UpdState.Idle -> OutlinedButton(onClick = { Updater.check(true) }, Modifier.fillMaxWidth()) { BtnLabel("Procurar atualização") }
        }
    }
}

@Composable
private fun UpdateBody(st: UpdState) {
    when (st) {
        is UpdState.Available -> {
            Text("Versão ${st.r.name} disponível", style = MaterialTheme.typography.titleSmall, color = Accent)
            if (st.r.notes.isNotBlank()) Hint(st.r.notes)
            Button(onClick = { Feedback.tick(); Updater.download(st.r) }, Modifier.fillMaxWidth().height(50.dp)) { BtnLabel("Atualizar agora") }
        }
        is UpdState.Downloading -> {
            Text("Baixando a versão ${st.r.name}… ${(st.frac * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(progress = { st.frac }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), drawStopIndicator = {})
        }
        is UpdState.Ready -> {
            Text("Versão ${st.r.name} pronta para instalar", style = MaterialTheme.typography.titleSmall, color = Good)
            if (!Updater.canInstall()) Hint("O Android vai pedir para permitir que o Scanner Max instale atualizações. Ative a opção e volte.")
            Button(onClick = { Updater.install() }, Modifier.fillMaxWidth().height(50.dp)) { BtnLabel("Instalar") }
        }
        else -> Unit
    }
}

/** Aviso flutuante de versão nova (some ao tocar em "Depois"). */
@Composable
fun UpdateBanner(modifier: Modifier = Modifier) {
    val s by Updater.state.collectAsState()
    val dismissed by Updater.dismissed.collectAsState()
    val show = !dismissed && (s is UpdState.Available || s is UpdState.Downloading || s is UpdState.Ready)
    AnimatedVisibility(show, modifier, enter = fadeIn() + slideInVertically { it }, exit = fadeOut() + slideOutVertically { it }) {
        Column(
            Modifier.padding(horizontal = 12.dp).widthIn(max = 520.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Filled.SystemUpdate, Accent, 36)
                Spacer(Modifier.width(12.dp))
                Text("Nova versão do Scanner Max", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (s is UpdState.Available) TextButton(onClick = { Updater.dismissed.value = true }) { Text("Depois") }
            }
            UpdateBody(s)
        }
    }
}
