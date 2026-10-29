package br.obd.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import br.obd.app.Alerts
import br.obd.app.Garage
import br.obd.app.ObdManager
import br.obd.app.Settings
import br.obd.app.TestRunner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ObdManager.init(this)
        ThemeState.apply(Settings.prefs.value.theme)
        PendingGo.value = intent?.getStringExtra("go")
        br.obd.app.ReminderWorker.opened(this)
        setContent {
            val prefs by Settings.prefs.collectAsState()
            LaunchedEffect(prefs.theme) {
                ThemeState.apply(prefs.theme)
                val dark = ThemeState.current.dark
                enableEdgeToEdge(
                    statusBarStyle = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                        else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
                    navigationBarStyle = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                        else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
                )
            }
            LaunchedEffect(prefs.keepScreenOn) {
                if (prefs.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            ScannerTheme { AppRoot() }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra("go")?.let { PendingGo.value = it }
    }


    override fun onStart() { super.onStart(); ObdManager.setVisible(true); br.obd.app.Updater.foreground = true }
    override fun onStop() { ObdManager.setVisible(false); br.obd.app.Updater.foreground = false; super.onStop() }
}

/** Tela pedida por um atalho do ícone ou por uma notificação. */
object PendingGo { var value by mutableStateOf<String?>(null) }

// ------------------------------------------------------------------ navegação

/** Uma tela empilhada sobre as abas. */
data class Nav(val route: String, val arg: String? = null)

/** Navegação simples: pilha de telas + aba atual. */
class Navigator {
    val stack = mutableStateListOf<Nav>()
    var tab by mutableIntStateOf(0)
    fun go(route: String, arg: String? = null) { stack.add(Nav(route, arg)) }
    fun back(): Boolean = if (stack.isNotEmpty()) { stack.removeAt(stack.lastIndex); true } else false
    fun home(tab: Int) { stack.clear(); this.tab = tab }
    val top get() = stack.lastOrNull()
}

private data class Tab(val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("Painel", Icons.Filled.Speed),
    Tab("Viagens", Icons.Filled.Route),
    Tab("Testes", Icons.Filled.Science),
    Tab("Diagnóstico", Icons.Filled.Warning),
)

private fun pageTitle(n: Nav): String = when (n.route) {
    "connect" -> "Conectar adaptador"
    "vehicle" -> "Meu veículo"
    "adapter" -> "Adaptador"
    "customize" -> "Personalizar"
    "lookup" -> "Consultar código"
    "dtc" -> n.arg ?: "Código"
    "flex" -> "Etanol ou gasolina?"
    "perf" -> "Desempenho"
    "maint" -> "Manutenção"
    "terminal" -> "Terminal"
    "log" -> "Log e exportação"
    "cable" -> "Central do fabricante"
    "about" -> "Sobre o app"
    "terms" -> "Termos de uso"
    "privacy" -> "Privacidade"
    "test" -> br.obd.app.TestCatalog.byId(n.arg ?: "")?.title ?: "Teste"
    "result" -> "Resultado"
    "trip" -> "Viagem"
    "tools" -> "Ferramentas"
    "health" -> "Saúde do motor"
    "hud" -> "Modo HUD"
    "graphs" -> "Gráficos ao vivo"
    "custom" -> "Sensores personalizados"
    "updates" -> "Atualizações"
    else -> ""
}

const val REPO = "https://github.com/JohneGabriel/Scanner-Max"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot() {
    val context = LocalContext.current
    val connected by ObdManager.connected.collectAsState()
    val status by ObdManager.status.collectAsState()
    val busy by ObdManager.busy.collectAsState()
    val alerts by Alerts.active.collectAsState()
    val prefs by Settings.prefs.collectAsState()
    val nav = remember { Navigator() }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var splash by rememberSaveable { mutableStateOf(true) }

    val onShare: (File, String) -> Unit = { file, mime ->
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Compartilhar ${file.name}"))
    }
    val openUrl: (String) -> Unit = { url ->
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }
    // Bluetooth desligado: pede ao Android para ligar (o próprio sistema mostra o diálogo de consentimento)
    var btPending by remember { mutableStateOf<Pair<Boolean, () -> Unit>?>(null) }
    val btLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) {
        val p = btPending; btPending = null
        if (p != null) {
            if (ObdManager.bluetoothOn()) { br.obd.app.Feedback.success(); p.second() }
            else if (!p.first) p.second()
            else ObdManager.notifyStatus("O Bluetooth continua desligado. Ligue para conectar ao adaptador.")
        }
    }
    /** Garante o Bluetooth ligado antes de [action]. [required] = sem Bluetooth não adianta seguir. */
    val ensureBt: (Boolean, () -> Unit) -> Unit = { required, action ->
        val hasAdapter = context.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter != null
        if (!hasAdapter || ObdManager.bluetoothOn() || !Permissions.hasBluetooth(context)) action()
        else {
            btPending = required to action
            runCatching { btLauncher.launch(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
                .onFailure { btPending = null; action() }
        }
    }
    val app = AppActions(nav, onShare, openUrl, onConnect = { ensureBt(false) { nav.go("connect") } }, ensureBt = ensureBt)

    // reconecta sozinho ao último adaptador ao abrir (se o usuário quiser)
    LaunchedEffect(Unit) {
        val t = ObdManager.savedTarget
        if (prefs.onboarded && prefs.autoConnect && t != null && !connected && t.kind != br.obd.app.link.LinkKind.USB &&
            Permissions.hasBluetooth(context)) {
            delay(900)
            ObdManager.connect(t)
        }
    }
    // atalhos do ícone e toques nas notificações
    val go = PendingGo.value
    LaunchedEffect(go, splash, prefs.onboarded) {
        if (go == null || splash || !prefs.onboarded) return@LaunchedEffect
        PendingGo.value = null
        when (go) {
            "connect" -> if (connected) nav.home(0) else app.onConnect()
            "codes" -> { nav.home(3); if (connected) ObdManager.readDiagnostics() }
            "tests" -> nav.home(2)
            "trips" -> nav.home(1)
            "hud" -> nav.go("hud")
            "maint" -> nav.go("maint")
            "vehicle" -> nav.go("vehicle")
            "updates" -> nav.go("updates")
            "demo" -> if (!connected) ObdManager.connect(br.obd.app.link.LinkTarget.DEMO)
        }
    }
    LaunchedEffect(prefs.quickNotif) { br.obd.app.QuickNotif.refresh(context) }

    // procura versão nova (no máximo a cada 12 h)
    LaunchedEffect(splash) { if (!splash) br.obd.app.Updater.check() }
    // versão nova já baixada: instala ao abrir, antes de conectar (o app reabre sozinho na versão nova)
    val upd by br.obd.app.Updater.state.collectAsState()
    var autoInstalled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(upd, splash) {
        if (splash || autoInstalled || upd !is br.obd.app.UpdState.Ready) return@LaunchedEffect
        delay(600)
        if (br.obd.app.Updater.safeNow() && br.obd.app.Updater.canInstall()) { autoInstalled = true; br.obd.app.Updater.install() }
    }

    // um teste começou em outra tela: abre a tela dele
    val live by TestRunner.live.collectAsState()
    LaunchedEffect(live?.testId) {
        val id = live?.testId ?: return@LaunchedEffect
        if (nav.top?.route != "test") nav.go("test", id)
    }

    var confirmExit by remember { mutableStateOf(false) }
    BackHandler(enabled = !splash && prefs.onboarded) {
        when {
            drawer.isOpen -> scope.launch { drawer.close() }
            nav.back() -> Unit
            nav.tab != 0 -> nav.tab = 0
            else -> { confirmExit = true; br.obd.app.Feedback.tick() }
        }
    }
    if (confirmExit) ExitDialog(connected, onStay = { confirmExit = false }, onExit = { disconnect ->
        confirmExit = false
        if (disconnect) ObdManager.disconnect()
        (context as? android.app.Activity)?.finish()
    })

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        ModalNavigationDrawer(
            drawerState = drawer,
            gesturesEnabled = nav.stack.isEmpty() || drawer.isOpen,
            drawerContent = {
                ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    DrawerContent(app) { route -> scope.launch { drawer.close() }; nav.go(route) }
                }
            },
        ) {
            Scaffold(
                topBar = {
                    Column {
                        val page = nav.top
                        TopAppBar(
                            navigationIcon = {
                                if (page != null) IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar") }
                                else IconButton(onClick = { scope.launch { drawer.open() } }) {
                                    Box {
                                        Icon(Icons.Filled.Menu, "Menu")
                                        if (alerts.any { it.level == br.obd.app.Level.BAD }) Box(Modifier.align(Alignment.TopEnd)) { PulseDot(Bad, 6) }
                                    }
                                }
                            },
                            title = {
                                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(if (page != null) pageTitle(page) else "Scanner Max", style = MaterialTheme.typography.titleLarge, maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                    if (page == null) {
                                        val pillColor = when {
                                            busy != null -> Accent
                                            connected && status == "Conectado" -> Good
                                            connected -> Warn
                                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                        StatusPill(busy ?: status, pillColor)
                                    }
                                }
                            },
                            actions = {
                                IconButton(onClick = { if (connected) nav.go("adapter") else nav.go("connect") }) {
                                    Icon(
                                        if (connected) Icons.Filled.BluetoothConnected else Icons.Filled.Bluetooth,
                                        if (connected) "Adaptador" else "Conectar",
                                        tint = if (connected) Good else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                        )
                        AnimatedVisibility(busy != null) { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    }
                },
                bottomBar = {
                    AnimatedVisibility(nav.stack.isEmpty(), enter = fadeIn(), exit = fadeOut()) {
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                            tabs.forEachIndexed { i, t ->
                                NavigationBarItem(
                                    selected = nav.tab == i, onClick = { if (nav.tab != i) br.obd.app.Feedback.tick(); nav.tab = i },
                                    icon = { Icon(t.icon, null) },
                                    label = { Text(t.label, maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, fontSize = 11.sp) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = Accent, selectedTextColor = Accent,
                                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                    ),
                                )
                            }
                        }
                    }
                },
                containerColor = MaterialTheme.colorScheme.background,
            ) { pad ->
                Box(Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad).imePadding()) {
                    val target: Any = nav.top ?: nav.tab
                    AnimatedContent(
                        targetState = target,
                        transitionSpec = {
                            if (targetState is Int && initialState is Int)
                                fadeIn(tween(220)) togetherWith fadeOut(tween(160))
                            else (fadeIn(tween(260)) + slideInHorizontally(tween(260)) { it / 10 }) togetherWith
                                (fadeOut(tween(180)) + slideOutHorizontally(tween(220)) { -it / 12 })
                        },
                        label = "nav",
                    ) { s ->
                        if (s is Nav) Page(s, app)
                        else when (s as Int) {
                            0 -> HomeScreen(app)
                            1 -> TripsScreen(app)
                            2 -> TestsScreen(app)
                            else -> DiagnosticsScreen(app)
                        }
                    }
                }
            }
        }

        if (nav.stack.isEmpty()) UpdateBanner(Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp).navigationBarsPadding())
        AnimatedVisibility(!splash && !prefs.onboarded, enter = fadeIn(tween(400)), exit = fadeOut(tween(350))) {
            SetupScreen { connect -> if (connect) nav.go("connect") }
        }
        AnimatedVisibility(splash, enter = fadeIn(), exit = fadeOut(tween(600))) { Splash { splash = false } }
    }
}

/** O que as telas precisam fazer fora delas (navegar, compartilhar, abrir links). */
class AppActions(
    val nav: Navigator,
    val share: (File, String) -> Unit,
    val openUrl: (String) -> Unit,
    val onConnect: () -> Unit,
    val ensureBt: (Boolean, () -> Unit) -> Unit = { _, a -> a() },
)

@Composable
private fun Page(p: Nav, app: AppActions) {
    when (p.route) {
        "connect" -> ConnectScreen(app)
        "vehicle" -> VehicleScreen(app)
        "adapter" -> AdapterScreen(app)
        "customize" -> CustomizeScreen()
        "lookup" -> LookupScreen(app)
        "dtc" -> DtcScreen(p.arg ?: "", app)
        "flex" -> FlexScreen()
        "perf" -> PerfScreen(app)
        "maint" -> MaintenanceScreen()
        "terminal" -> TerminalScreen()
        "log" -> LogScreen(app)
        "cable" -> VagScreen()
        "about" -> AboutScreen(app)
        "terms" -> LegalScreen(Legal.TERMS)
        "privacy" -> LegalScreen(Legal.PRIVACY)
        "test" -> TestRunScreen(p.arg ?: "", app)
        "result" -> ResultScreen(p.arg?.toLongOrNull() ?: 0L, app)
        "trip" -> TripDetailScreen(p.arg?.toLongOrNull() ?: 0L, app)
        "tools" -> ToolsHubScreen(app)
        "health" -> HealthScreen(app)
        "hud" -> HudScreen()
        "graphs" -> GraphsScreen(app)
        "custom" -> CustomPidsScreen()
        "updates" -> UpdatesScreen()
    }
}

// ------------------------------------------------------------------ menu lateral

@Composable
private fun DrawerContent(app: AppActions, go: (String) -> Unit) {
    val connected by ObdManager.connected.collectAsState()
    val veh by Garage.current.collectAsState()
    val adapter by ObdManager.adapter.collectAsState()
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 12.dp)) {
        // cabeçalho
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
            LogoMark(46)
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Scanner Max", style = MaterialTheme.typography.titleLarge)
                Hint(veh?.name?.let { "$it · ${if (connected) "conectado" else "desconectado"}" } ?: "Nenhum carro ainda")
            }
        }
        Spacer(Modifier.height(6.dp))
        DrawerGroup("Conexão")
        if (!connected) MenuRow(Icons.Filled.Bluetooth, "Conectar adaptador", "Bluetooth, BLE, Wi-Fi, USB ou demonstração") { go("connect") }
        else MenuRow(Icons.Filled.LinkOff, "Desconectar", adapter.chipName, Bad) { ObdManager.disconnect() }
        MenuRow(Icons.Filled.Memory, "Adaptador", "Chip, versão, recursos e protocolos", Cyan) { go("adapter") }

        DrawerGroup("Veículo")
        MenuRow(Icons.Filled.DirectionsCar, "Meu veículo", "Motor, combustível, preço e calibração") { go("vehicle") }
        MenuRow(Icons.Filled.Build, "Manutenção", "Óleo, filtros, velas, correia") { go("maint") }
        MenuRow(Icons.Filled.Assessment, "Relatório do veículo (PDF)", "Saúde, códigos, testes e uso") { HealthReport.share(app) }

        DrawerGroup("Ferramentas")
        MenuRow(Icons.Filled.Search, "Consultar código de falha", "Explicação e pesquisa com um toque") { go("lookup") }
        MenuRow(Icons.Filled.Timeline, "Gráficos ao vivo", "Até 3 sensores ao mesmo tempo") { go("graphs") }
        MenuRow(Icons.Filled.Speed, "Modo HUD", "Números grandes, espelhado para o para-brisa") { go("hud") }
        MenuRow(Icons.Filled.Terminal, "Sensores personalizados", "PIDs do fabricante com fórmula própria") { go("custom") }
        MenuRow(Icons.Filled.LocalGasStation, "Etanol ou gasolina?", "Qual compensa abastecer") { go("flex") }
        MenuRow(Icons.Filled.Timer, "Desempenho", "0–100, 400 m, frenagem e potência") { go("perf") }
        MenuRow(Icons.Filled.Usb, "Central do fabricante", "Cabo KKL (VW/Audi): blocos de medição", Cyan) { go("cable") }
        MenuRow(Icons.Filled.Terminal, "Terminal", "Comandos AT e OBD") { go("terminal") }

        DrawerGroup("App")
        MenuRow(Icons.Filled.SystemUpdate, "Atualizações", "Versão ${AppInfo.version}") { go("updates") }
        MenuRow(Icons.Filled.Palette, "Personalizar", "Temas, painel, alertas, sons") { go("customize") }
        MenuRow(Icons.Filled.Timeline, "Log e exportação", "Para enviar quando algo der errado") { go("log") }
        MenuRow(Icons.Filled.BugReport, "Reportar problema", "Abre um relato no GitHub", Bad) { Support.bug(app) }
        MenuRow(Icons.Filled.Lightbulb, "Sugerir função", "Conte o que faria diferença", Good) { Support.feature(app) }
        MenuRow(Icons.Filled.Info, "Sobre o app") { go("about") }
        MenuRow(Icons.Filled.Gavel, "Termos de uso", color = MaterialTheme.colorScheme.onSurfaceVariant) { go("terms") }
        MenuRow(Icons.Filled.PrivacyTip, "Privacidade", color = MaterialTheme.colorScheme.onSurfaceVariant) { go("privacy") }
        Spacer(Modifier.height(16.dp))
        Hint("Versão ${AppInfo.version}", Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun DrawerGroup(title: String) {
    HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
    Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = Accent,
        modifier = Modifier.padding(start = 6.dp, bottom = 4.dp, top = 2.dp))
}

// ------------------------------------------------------------------ marca e splash

/** O mostrador do ícone, desenhado (usado no menu e na splash). */
@Composable
fun LogoMark(size: Int, sweep: Float = 0.62f) {
    val accent = Accent
    val track = MaterialTheme.colorScheme.surfaceVariant
    Canvas(Modifier.size(size.dp)) {
        val w = this.size.minDimension * 0.12f
        val r = androidx.compose.ui.geometry.Rect(w, w, this.size.width - w, this.size.height - w)
        drawArc(track, 135f, 270f, false, r.topLeft, r.size, style = Stroke(w, cap = StrokeCap.Round))
        drawArc(Brush.sweepGradient(listOf(accent.copy(alpha = 0.6f), accent)), 135f, 270f * sweep, false,
            r.topLeft, r.size, style = Stroke(w, cap = StrokeCap.Round))
        val ang = Math.toRadians((135 + 270 * sweep).toDouble())
        val c = center
        val len = this.size.minDimension * 0.30f
        drawLine(accent, c, androidx.compose.ui.geometry.Offset(c.x + len * kotlin.math.cos(ang).toFloat(), c.y + len * kotlin.math.sin(ang).toFloat()),
            strokeWidth = w * 0.7f, cap = StrokeCap.Round)
        drawCircle(accent, w * 0.8f, c)
    }
}

@Composable
private fun Splash(onDone: () -> Unit) {
    val sweep = remember { Animatable(0f) }
    val textAlpha = remember { Animatable(0f) }
    val scale = remember { Animatable(0.85f) }
    LaunchedEffect(Unit) {
        launch { scale.animateTo(1f, tween(1000, easing = FastOutSlowInEasing)) }
        delay(150)
        sweep.animateTo(1f, tween(1000, easing = FastOutSlowInEasing))
        sweep.animateTo(0.62f, tween(550, easing = FastOutSlowInEasing))
        textAlpha.animateTo(1f, tween(500))
        delay(1100)
        onDone()
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.scale(scale.value)) {
            LogoMark(120, sweep.value)
            Spacer(Modifier.height(22.dp))
            Text("Scanner Max", fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.alpha(textAlpha.value))
            Spacer(Modifier.height(4.dp))
            Text("Diagnóstico OBD2 para qualquer carro", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.alpha(textAlpha.value))
        }
    }
}


/** Confirmação de saída: cartão próprio com marca, texto curto e botões de largura total. */
@Composable
private fun ExitDialog(connected: Boolean, onStay: () -> Unit, onExit: (disconnect: Boolean) -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onStay) {
        Column(
            Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(64.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Accent.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center) { LogoMark(36) }
            Text("Sair do Scanner Max?", style = MaterialTheme.typography.titleLarge,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text(
                if (connected) "O carro continua conectado em segundo plano: viagem, alertas e notificação seguem funcionando."
                else "Seus dados ficam salvos no aparelho.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            androidx.compose.material3.Button(onClick = { onExit(false) }, Modifier.fillMaxWidth().height(50.dp)) {
                BtnLabel(if (connected) "Sair e manter conectado" else "Sair")
            }
            if (connected) androidx.compose.material3.OutlinedButton(onClick = { onExit(true) }, Modifier.fillMaxWidth().height(50.dp)) {
                BtnLabel("Desconectar e sair")
            }
            androidx.compose.material3.TextButton(onClick = onStay, Modifier.fillMaxWidth()) { BtnLabel("Continuar no app") }
        }
    }
}
