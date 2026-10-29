package br.obd.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import br.obd.app.Feedback
import br.obd.app.UpdState
import br.obd.app.Updater

/** Página "Atualizações" (menu): versão instalada, procurar, baixar e instalar. */
@Composable
fun UpdatesScreen() {
    val s by Updater.state.collectAsState()
    LaunchedEffect(Unit) { Updater.check(force = true) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Section("Scanner Max", icon = Icons.Filled.SystemUpdate) {
            KeyValue("Versão instalada", AppInfo.version)
            when (val st = s) {
                is UpdState.Available, is UpdState.Downloading, is UpdState.Ready -> UpdateBody(st)
                UpdState.Checking -> { Hint("Procurando versão nova…"); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                UpdState.UpToDate -> Callout("Você está na versão mais recente.", Tone.GOOD)
                is UpdState.Failed -> {
                    Callout(st.msg, Tone.WARN)
                    OutlinedButton(onClick = { Updater.check(true) }, Modifier.fillMaxWidth()) { BtnLabel("Tentar de novo") }
                }
                UpdState.Idle -> OutlinedButton(onClick = { Updater.check(true) }, Modifier.fillMaxWidth()) { BtnLabel("Procurar atualização") }
            }
        }
        Callout("O app confere sozinho se há versão nova e avisa você. A atualização mantém seus carros, viagens e configurações.", Tone.INFO)
        Spacer(Modifier.height(20.dp))
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
