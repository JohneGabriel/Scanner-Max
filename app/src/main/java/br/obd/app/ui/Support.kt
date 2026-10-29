package br.obd.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import br.obd.app.DiagState
import br.obd.app.Garage
import br.obd.app.ObdManager
import br.obd.app.Reports
import br.obd.app.TestRunner
import br.obd.app.TripStore
import java.net.URLEncoder

object AppInfo {
    val version: String
        get() = runCatching {
            val ctx = ObdManager.context
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
        }.getOrDefault("?")
}

object Permissions {
    fun bluetoothList(): Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= 31) { add(Manifest.permission.BLUETOOTH_CONNECT); add(Manifest.permission.BLUETOOTH_SCAN) }
        else add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    fun hasBluetooth(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun hasScan(ctx: Context): Boolean = if (Build.VERSION.SDK_INT >= 31)
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
    else ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
}

object Support {
    private fun issue(app: AppActions, title: String, body: String, label: String) {
        val url = "$REPO/issues/new?labels=${URLEncoder.encode(label, "UTF-8")}&title=${URLEncoder.encode(title, "UTF-8")}" +
            "&body=${URLEncoder.encode(body, "UTF-8")}"
        app.openUrl(url)
    }

    private fun context(): String {
        val v = Garage.current.value
        val a = ObdManager.adapter.value
        return """
            |
            |---
            |App: ${AppInfo.version} · Android ${Build.VERSION.RELEASE} (${Build.MANUFACTURER} ${Build.MODEL})
            |Carro: ${v?.name ?: "—"} ${v?.year ?: ""} · ${v?.protocol ?: "protocolo ?"}
            |Adaptador: ${a.summary}
        """.trimMargin()
    }

    fun bug(app: AppActions) = issue(app, "Problema: ",
        "**O que aconteceu?**\n\n\n**O que eu esperava?**\n\n\n**Como repetir:**\n1. \n2. \n\n" +
            "_Dica: exporte o log em Menu → Log e exportação e anexe aqui._" + context(), "bug")

    fun feature(app: AppActions) = issue(app, "Sugestão: ",
        "**O que eu gostaria que o app fizesse:**\n\n\n**Por que isso ajudaria:**\n\n" + context(), "enhancement")
}

/** Relatório de saúde do veículo (PDF) a partir de tudo que o app sabe do carro atual. */
object HealthReport {
    fun share(app: AppActions) {
        val ctx = ObdManager.context
        val p = Garage.current.value
        val monthAgo = System.currentTimeMillis() - 30L * 24 * 3600 * 1000
        val trips = TripStore.trips.value.filter { (p == null || it.vehicleId == p.id) && it.start >= monthAgo }
        val tests = TestRunner.history.value.filter { p == null || it.vehicleId == p.id }
        val diag: DiagState? = ObdManager.diag.value.takeIf { it.readAt != null }
        val f = Reports.health(ctx, p, diag, tests, trips, ObdManager.adapter.value)
        app.share(f, "application/pdf")
    }
}

object Legal {
    val TERMS = "Termos de uso" to listOf(
        "Sobre o serviço" to "O Scanner Max é um aplicativo de diagnóstico que lê informações da central eletrônica do seu veículo pela tomada OBD2, usando um adaptador compatível com ELM327 (ou o cabo KKL, para centrais VW). Ele é distribuído gratuitamente, como está, para uso pessoal.",
        "Natureza das informações" to "As leituras, explicações de códigos, veredictos dos testes, estimativas de consumo e relatórios são orientativos. Eles podem conter imprecisões causadas pelo adaptador, pela central do veículo ou pelos limites do próprio padrão OBD2, e não substituem a avaliação de um mecânico qualificado.",
        "Uso responsável" to "Não use o app de forma que tire sua atenção do trânsito. Testes que pedem para acelerar devem ser feitos com o carro parado, em local seguro e ventilado. A medição de aceleração só deve ser usada em local fechado e apropriado, nunca em vias públicas. Respeite sempre as leis de trânsito.",
        "Comandos que alteram a central" to "Algumas funções enviam comandos que apagam dados da central (como apagar códigos de falha, que também zera os monitores de prontidão). O app pede confirmação antes. Você é responsável por decidir usá-las. O terminal permite enviar qualquer comando: use apenas se souber o que está fazendo.",
        "Limitação de responsabilidade" to "Na medida permitida pela lei, os autores não se responsabilizam por danos ao veículo, ao adaptador, ao aparelho ou por decisões tomadas com base nas informações do app.",
        "Marcas" to "Nomes de fabricantes e modelos de veículos e adaptadores são citados apenas para identificação e pertencem aos seus donos. O app não é afiliado a nenhum deles.",
        "Mudanças" to "Estes termos podem ser atualizados em novas versões do app. Continuar usando o app depois de uma atualização significa concordar com a versão nova.",
    )

    val PRIVACY = "Política de privacidade" to listOf(
        "Resumo" to "Tudo fica no seu aparelho. O Scanner Max não tem servidor, não cria conta, não usa publicidade e não envia seus dados para ninguém.",
        "Atualizações" to "O app consulta periodicamente o site do Scanner Max para saber se há versão nova. Essa consulta não envia nenhum dado seu.",
        "O que fica salvo no aparelho" to "Perfis dos veículos (nome, chassi lido da central, cilindrada, combustível, preço), viagens (distância, tempo, consumo estimado e gráficos), resultados de testes, último diagnóstico, lembretes de manutenção, preferências e o log técnico da sessão.",
        "Localização" to "O app não usa GPS. Em Android 11 ou anterior, o sistema exige a permissão de localização para procurar adaptadores Bluetooth LE; ela é usada só para essa busca.",
        "Permissões" to "Instalar apps: baixar e instalar as atualizações do Scanner Max (o Android sempre pede sua confirmação). Bluetooth: conectar ao adaptador. Dispositivos próximos/Localização: encontrar adaptadores BLE. Notificações: mostrar a conexão e os alertas do carro. USB: usar adaptadores e cabos ligados pelo OTG. Rede: conectar a adaptadores Wi-Fi na rede local deles.",
        "Quando algo sai do aparelho" to "Só quando você escolhe: ao compartilhar um relatório, CSV ou log, ao abrir uma pesquisa de código no navegador, ao buscar os dados do motor pelo chassi (o número do chassi vai para a base pública da NHTSA) ou ao abrir um relato de problema/sugestão no GitHub. Nesses casos vale a política do serviço que você escolheu.",
        "Apagar seus dados" to "Você pode apagar viagens e testes no próprio app. Para apagar tudo, use Configurações do Android → Apps → Scanner Max → Armazenamento → Limpar dados, ou desinstale o app.",
    )
}

@Composable
fun LegalScreen(doc: Pair<String, List<Pair<String, String>>>) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(doc.first, style = MaterialTheme.typography.headlineSmall)
        Hint("Versão do app ${AppInfo.version}")
        doc.second.forEach { (t, body) ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(t, style = MaterialTheme.typography.titleMedium, color = Accent)
                Text(body, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

fun Uri.safe() = this
