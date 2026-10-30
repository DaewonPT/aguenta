package pt.pixellusitano.aguenta.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.content.ClipData
import android.content.ClipboardManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pt.pixellusitano.aguenta.BuildConfig
import pt.pixellusitano.aguenta.service.NotificationLog
import pt.pixellusitano.aguenta.update.NewVersion
import pt.pixellusitano.aguenta.update.Updates
import pt.pixellusitano.aguenta.data.CycleAnchor
import pt.pixellusitano.aguenta.data.IncomeConfigEntity
import pt.pixellusitano.aguenta.data.IncomeType
import pt.pixellusitano.aguenta.data.TransactionEntity
import pt.pixellusitano.aguenta.domain.BankingNotificationParser
import pt.pixellusitano.aguenta.domain.BudgetEngine
import pt.pixellusitano.aguenta.domain.Insight
import pt.pixellusitano.aguenta.domain.TxKind
import pt.pixellusitano.aguenta.domain.eur
import pt.pixellusitano.aguenta.domain.parseEurosOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

private enum class Tab(val label: String) { RADAR("Radar"), STREAM("Movimentos"), CYCLE("Ciclo") }
enum class Entry { SPEND, INCOME, BALANCE }

private val PT = Locale.forLanguageTag("pt-PT")

@Composable
fun AppRoot(
    state: UiState,
    onIntent: (UiIntent) -> Unit,
    listenerOn: Boolean,
    openListenerSettings: () -> Unit,
    openAdjust: Boolean,
    onAdjustShown: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(Tab.RADAR) }
    val tabStates = rememberSaveableStateHolder()
    var entry by remember { mutableStateOf<Entry?>(null) }
    LaunchedEffect(openAdjust) {
        if (openAdjust) {
            entry = Entry.BALANCE
            onAdjustShown()
        }
    }
    Backdrop(if (state.survival) Coral else levelColor(state.snapshot?.level)) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 16.dp)) {
            Box(Modifier.weight(1f)) {
                AnimatedContent(
                    targetState = tab,
                    transitionSpec = {
                        // Desliza para o lado do separador escolhido.
                        val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                        (slideInHorizontally(tween(320)) { it * dir / 3 } + fadeIn(tween(320))) togetherWith
                            (slideOutHorizontally(tween(320)) { -it * dir / 3 } + fadeOut(tween(200)))
                    },
                    modifier = Modifier.fillMaxSize(),
                    label = "tab",
                ) { t ->
                    // Cada separador guarda os filtros e a posição do scroll quando se sai dele.
                    tabStates.SaveableStateProvider(t.name) {
                        when (t) {
                            Tab.RADAR -> RadarScreen(state, listenerOn, openListenerSettings, onIntent) { entry = it }
                            Tab.STREAM -> StreamScreen(state.history, onIntent)
                            Tab.CYCLE -> CycleScreen(state.config, listenerOn, openListenerSettings, onIntent) { entry = it }
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 10.dp).glass(RoundedCornerShape(50)).padding(6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                Tab.entries.forEach { Pill(it.label, tab == it) { tab = it } }
            }
        }
    }
    entry?.let { e ->
        QuickEntryDialog(e, onDismiss = { entry = null }) {
            onIntent(it)
            entry = null
        }
    }
    UpdatePrompt()
}

/** No máximo uma vez por dia, avisa que há uma versão nova no GitHub e instala-a com um toque. */
@Composable
private fun UpdatePrompt() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var version by remember { mutableStateOf<NewVersion?>(null) }
    LaunchedEffect(Unit) { version = runCatching { Updates.check(ctx) }.getOrNull() }
    val v = version ?: return
    AlertDialog(
        onDismissRequest = { version = null },
        containerColor = Charcoal,
        shape = RoundedCornerShape(28.dp),
        title = { Text("Nova versão ${v.tag.removePrefix("v")}") },
        text = { Text(v.notes.ifBlank { "Há uma versão nova do Aguenta." }.take(600), color = Dim, fontSize = 13.sp) },
        confirmButton = {
            TextButton(onClick = {
                version = null
                scope.launch {
                    runCatching { Updates.install(ctx, v) }
                        .onFailure { Toast.makeText(ctx, "Não consegui descarregar a atualização. Tenta mais logo.", Toast.LENGTH_LONG).show() }
                }
            }) { Text("Atualizar") }
        },
        dismissButton = { TextButton(onClick = { version = null }) { Text("Agora não") } },
    )
}

@Composable
private fun Header(text: String) =
    Text(text, fontSize = 28.sp, fontWeight = FontWeight.Black, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))

@Composable
private fun RadarScreen(
    state: UiState,
    listenerOn: Boolean,
    openListenerSettings: () -> Unit,
    onIntent: (UiIntent) -> Unit,
    onEntry: (Entry) -> Unit,
) = Column(
    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(16.dp),
) {
    Header("Radar Diário")
    if (!listenerOn) {
        Column(Modifier.fillMaxWidth().glass().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("O Aguenta ainda não lê as notificações da Samsung Wallet, CGD e Wise.", fontWeight = FontWeight.SemiBold)
            Pill("Dar acesso às notificações", true, Amber, onClick = openListenerSettings)
        }
    }
    if (state.survival) {
        Column(Modifier.fillMaxWidth().glass().background(Coral.copy(alpha = 0.12f)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("🧊 Modo Sobrevivência até ${hhmm(state.survivalUntil)}", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text("Alertas em silêncio, exceto se rebentares o teto. Nada de compras que não sejam essenciais.", color = Dim, fontSize = 13.sp)
            Pill("Terminar", false, Coral) { onIntent(UiIntent.Unfreeze) }
        }
    }
    state.snapshot?.let { s ->
        Gauge(s)
        Text(BudgetEngine.message(s), color = levelColor(s.level), textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold)
        RunwayBar(s)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Metric("Saldo", eur(s.balanceCents), Color.White, Modifier.weight(1f))
            Metric(
                "Velocidade", s.velocity?.let { String.format(PT, "×%.1f", it) } ?: "—",
                when { (s.velocity ?: 0.0) > 1.15 -> Coral; (s.velocity ?: 0.0) > 1.0 -> Amber; else -> Cyan }, Modifier.weight(1f),
            )
            Metric("Gasto hoje", eur(s.spentTodayCents), Color.White, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("+ Gasto", false) { onEntry(Entry.SPEND) }
            Pill("+ Entrada", false) { onEntry(Entry.INCOME) }
            Pill("Ajustar saldo", false) { onEntry(Entry.BALANCE) }
        }
        if (!state.survival) Pill("Aguenta! Congelar gastos", true, Coral) { onIntent(UiIntent.Freeze) }
    }
    AdviceCard(state.insights, state.snapshot?.payday?.dayOfMonth)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun AdviceCard(insights: List<Insight>?, payday: Int?) =
    Column(Modifier.fillMaxWidth().glass().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("💡 Lições do ciclo passado", fontWeight = FontWeight.Bold)
        when {
            insights == null -> Text(
                "Ainda estou a aprender os teus hábitos. ${payday?.let { "No dia $it" } ?: "No próximo ciclo"} digo-te onde dá para cortar.",
                color = Dim, fontSize = 13.sp,
            )
            insights.isEmpty() -> Text("Ciclo passado sem exageros à vista. Continua assim!", color = Dim, fontSize = 13.sp)
            else -> insights.forEach {
                Column {
                    Text(it.title, color = Amber, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text(it.text, fontSize = 14.sp)
                }
            }
        }
    }

@Composable
private fun Metric(label: String, value: String, color: Color, modifier: Modifier) =
    Column(modifier.glass(RoundedCornerShape(22.dp)).padding(14.dp)) {
        Text(label, color = Dim, fontSize = 12.sp)
        Text(value, color = color, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }

private enum class Period(val label: String, val days: Long) { WEEK("7 dias", 7), MONTH("30 dias", 30), ALL("90 dias", 90) }

/** Entradas e ajustes ficam à parte; os gastos agrupam pela categoria da loja. */
private fun group(t: TransactionEntity) = when (t.kind) {
    TxKind.INCOME -> "Entradas"
    TxKind.ADJUST -> "Ajustes"
    TxKind.SPEND -> t.category
}

// Ordem dos chips: categorias de gasto primeiro, da que levou mais dinheiro para a que levou menos; entradas e ajustes no fim.
private fun categoryChips(txs: List<TransactionEntity>) = txs.groupBy(::group).entries.sortedWith(
    compareBy<Map.Entry<String, List<TransactionEntity>>> { it.key == "Entradas" || it.key == "Ajustes" }
        .thenByDescending { (_, ts) -> ts.sumOf { abs(it.deltaCents) } },
).map { it.key }

@Composable
private fun StreamScreen(history: List<TransactionEntity>, onIntent: (UiIntent) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var period by rememberSaveable { mutableStateOf(Period.ALL) }
    val focus = LocalFocusManager.current
    val inPeriod = remember(history, period) {
        val from = System.currentTimeMillis() - period.days * 86_400_000L
        history.filter { it.timestamp >= from }
    }
    val chips = remember(inPeriod) { categoryChips(inPeriod) }
    // Se a categoria escolhida deixou de existir no período, o filtro cai sozinho em vez de esconder tudo.
    val active = category?.takeIf { it in chips }
    // Sempre por data, do mais recente para o mais antigo: a consulta já vem assim e o filter mantém a ordem.
    val shown = remember(inPeriod, query, active) {
        val q = query.trim().lowercase()
        inPeriod.filter { t ->
            (active == null || group(t) == active) &&
                (q.isEmpty() || listOfNotNull(t.merchant, group(t), eur(abs(t.deltaCents))).any { q in it.lowercase() })
        }
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
        item(key = "header") {
            Column {
                Header("Movimentos")
                Text("O Aguenta guarda só os últimos 90 dias.", color = Dim, fontSize = 12.sp)
            }
        }
        item(key = "search") {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Pesquisar loja, categoria ou valor") },
                leadingIcon = { Text("🔍") },
                trailingIcon = {
                    if (query.isNotEmpty()) Text("✕", Modifier.clickable(onClickLabel = "Limpar pesquisa") { query = "" }.padding(12.dp))
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                shape = RoundedCornerShape(50),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item(key = "periods") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Period.entries.forEach { p ->
                    Pill(p.label, period == p) {
                        period = p
                        focus.clearFocus()
                    }
                }
            }
        }
        if (chips.isNotEmpty()) {
            item(key = "chips") {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("Tudo", active == null) {
                        category = null
                        focus.clearFocus()
                    }
                    chips.forEach { c ->
                        Pill("${emoji(c)} $c", active == c) {
                            category = if (active == c) null else c
                            focus.clearFocus()
                        }
                    }
                }
            }
        }
        if (shown.isEmpty()) {
            item(key = "empty") {
                Text(
                    if (history.isEmpty()) "Ainda nada. Paga com Samsung Wallet, CGD ou Wise e aparece aqui." else "Nada encontrado com estes filtros.",
                    color = Dim, modifier = Modifier.animateItem(),
                )
            }
        }
        if (shown.isNotEmpty()) {
            item(key = "summary") {
                val spent = shown.filter { it.kind == TxKind.SPEND }.sumOf { -it.deltaCents }
                Text(
                    "${if (shown.size == 1) "1 movimento" else "${shown.size} movimentos"} · ${eur(spent)} em gastos",
                    color = Dim, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp).animateItem(),
                )
            }
        }
        items(shown, key = { it.id }) { TxCard(it, onIntent, Modifier.animateItem()) }
    }
}

@Composable
private fun TxCard(t: TransactionEntity, onIntent: (UiIntent) -> Unit, modifier: Modifier = Modifier) = Row(
    modifier.fillMaxWidth().glass(RoundedCornerShape(22.dp)).padding(14.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Text(emoji(group(t)), fontSize = 22.sp)
    Spacer(Modifier.width(12.dp))
    Column(Modifier.weight(1f)) {
        Text(t.merchant ?: if (t.kind == TxKind.INCOME) "Entrada" else "Pagamento", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${source(t.sourcePackage)} · ${dayTime(t.timestamp)}", color = Dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text((if (t.deltaCents > 0) "+" else "") + eur(t.deltaCents), color = if (t.deltaCents < 0) Color.White else Cyan, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (t.kind == TxKind.SPEND) Pill(if (t.essential) "Essencial ✔" else "Essencial?", t.essential, Amber) { onIntent(UiIntent.ToggleEssential(t)) }
            Pill("✕", false) { onIntent(UiIntent.Delete(t.id)) }
        }
    }
}

@Composable
private fun CycleScreen(
    cfg: IncomeConfigEntity,
    listenerOn: Boolean,
    openListenerSettings: () -> Unit,
    onIntent: (UiIntent) -> Unit,
    onEntry: (Entry) -> Unit,
) {
    var type by remember(cfg) { mutableStateOf(cfg.type) }
    var day by remember(cfg) { mutableStateOf(cfg.paydayDay.toString()) }
    var base by remember(cfg) { mutableStateOf(centsText(cfg.baseCents)) }
    var fixed by remember(cfg) { mutableStateOf(centsText(cfg.fixedCostsCents)) }
    var anchor by remember(cfg) { mutableStateOf(cfg.anchor) }
    var names by remember(cfg) { mutableStateOf(cfg.ownNames) }
    var error by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }
    LaunchedEffect(saved) {
        if (saved) {
            delay(2_500)
            saved = false
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Header("Salário e Ciclo")
        Column(Modifier.fillMaxWidth().glass().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Tipo de rendimento", color = Dim)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Salário fixo", type == IncomeType.FIXED) { type = IncomeType.FIXED }
                Pill("Variável", type == IncomeType.VARIABLE) { type = IncomeType.VARIABLE }
            }
            Field(day, "Dia do salário (1–31)", KeyboardType.Number) { day = it }
            Field(base, if (type == IncomeType.FIXED) "Salário líquido (€)" else "Rendimento típico (€)") { base = it }
            Field(fixed, "Custos fixos do mês (€): renda, contas…") { fixed = it }
            Text("O ciclo recomeça", color = Dim)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("No dia do mês", anchor == CycleAnchor.DAY) { anchor = CycleAnchor.DAY }
                Pill("Quando o salário cai na Wise", anchor == CycleAnchor.WISE_INCOME) { anchor = CycleAnchor.WISE_INCOME }
            }
            Text(
                when {
                    anchor == CycleAnchor.WISE_INCOME -> "Uma entrada na Wise de pelo menos metade do valor acima abre um ciclo novo."
                    type == IncomeType.FIXED -> "No dia do salário o Aguenta soma-o ao saldo. Se o salário entra na Wise, escolhe a outra opção para não contar a dobrar."
                    else -> "Cada entrada na Wise soma ao saldo do ciclo."
                },
                color = Dim, fontSize = 12.sp,
            )
            Field(names, "Os teus nomes, separados por vírgulas", KeyboardType.Text) { names = it }
            Text("Envios para estes nomes são transferências entre contas tuas e ficam de fora.", color = Dim, fontSize = 12.sp)
            error?.let { Text(it, color = Coral) }
            Pill("Guardar", true) {
                val d = day.toIntOrNull()
                val b = if (base.isBlank()) 0L else parseEurosOrNull(base)
                val f = if (fixed.isBlank()) 0L else parseEurosOrNull(fixed)
                if (d == null || d !in 1..31 || b == null || b < 0 || f == null || f < 0) {
                    error = "Confirma o dia (1–31) e os valores."
                } else {
                    error = null
                    saved = true
                    onIntent(UiIntent.SaveConfig(cfg.copy(type = type, paydayDay = d, baseCents = b, fixedCostsCents = f, anchor = anchor, ownNames = names.trim())))
                }
            }
            if (saved) Text("Guardado ✔", color = Cyan, fontWeight = FontWeight.SemiBold)
        }
        Column(Modifier.fillMaxWidth().glass().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill("Definir saldo atual", false) { onEntry(Entry.BALANCE) }
            Pill(if (listenerOn) "Acesso às notificações: ativo" else "Dar acesso às notificações", listenerOn, onClick = openListenerSettings)
        }
        NotificationsDiagnostic()
        Text("Pixel Lusitano · contact@pixellusitano.com", color = Dim, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Field(value: String, label: String, type: KeyboardType = KeyboardType.Decimal, onChange: (String) -> Unit) = OutlinedTextField(
    value = value,
    onValueChange = onChange,
    label = { Text(label) },
    singleLine = true,
    keyboardOptions = KeyboardOptions(keyboardType = type),
    shape = RoundedCornerShape(16.dp),
    modifier = Modifier.fillMaxWidth(),
)

@Composable
private fun QuickEntryDialog(entry: Entry, onDismiss: () -> Unit, onConfirm: (UiIntent) -> Unit) {
    var amount by remember { mutableStateOf("") }
    var merchant by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Charcoal,
        shape = RoundedCornerShape(28.dp),
        title = { Text(when (entry) { Entry.SPEND -> "Novo gasto"; Entry.INCOME -> "Nova entrada"; Entry.BALANCE -> "Ajustar saldo" }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (entry == Entry.BALANCE) Text("Quanto tens agora para gastar até ao salário, já sem os custos fixos?", color = Dim, fontSize = 13.sp)
                Field(amount, "Valor (€)") { amount = it }
                if (entry != Entry.BALANCE) Field(merchant, if (entry == Entry.SPEND) "Onde?" else "De onde?", KeyboardType.Text) { merchant = it }
                error?.let { Text(it, color = Coral) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val c = parseEurosOrNull(amount)
                if (c == null || (entry != Entry.BALANCE && c <= 0)) {
                    error = "Valor inválido"
                } else {
                    onConfirm(
                        when (entry) {
                            Entry.SPEND -> UiIntent.AddManual(c, income = false, merchant = merchant)
                            Entry.INCOME -> UiIntent.AddManual(c, income = true, merchant = merchant)
                            Entry.BALANCE -> UiIntent.AdjustBalance(c)
                        },
                    )
                }
            }) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

private fun centsText(c: Long) = if (c == 0L) "" else eur(c).removeSuffix(" €")

private fun hhmm(millis: Long) = DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
private fun dayTime(millis: Long) = DateTimeFormatter.ofPattern("dd/MM HH:mm").format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

private fun source(pkg: String) = when (pkg) {
    in BankingNotificationParser.SAMSUNG -> "Samsung Wallet"
    BankingNotificationParser.CGD -> "CGD"
    BankingNotificationParser.MBWAY -> "MB WAY"
    BankingNotificationParser.WISE -> "Wise"
    else -> "Manual"
}

/** O que o serviço viu nas últimas notificações dos bancos, para afinar o reconhecimento com os textos reais. */
@Composable
private fun NotificationsDiagnostic() {
    val ctx = LocalContext.current
    val seen by NotificationLog.seen.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth().glass().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("🔎 Notificações dos bancos", fontWeight = FontWeight.Bold)
        Text(
            "O que o Aguenta viu desde que abriu. Se uma compra ficou por reconhecer, copia a lista para afinar o reconhecimento.",
            color = Dim, fontSize = 12.sp,
        )
        if (seen.isEmpty()) Text("Ainda nenhuma.", color = Dim, fontSize = 13.sp)
        seen.forEach { n ->
            val ok = n.result != "não reconhecida" && n.result != "app não suportada"
            Column {
                Text(
                    "${if (ok) "✔" else "✖"} ${if (n.pkg in BankingNotificationParser.PACKAGES) source(n.pkg) else n.pkg} · ${dayTime(n.time)} · ${n.result}",
                    color = if (ok) Cyan else Amber, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                )
                Text(listOfNotNull(n.title, n.text).joinToString(" — "), fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        if (seen.isNotEmpty()) {
            Pill("Copiar lista", false) {
                val report = "Aguenta ${BuildConfig.VERSION_NAME}\n" +
                    seen.joinToString("\n") { "[${it.pkg} ${dayTime(it.time)}] ${it.title} | ${it.text} → ${it.result}" }
                ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Aguenta", report))
                Toast.makeText(ctx, "Lista copiada", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

private fun emoji(group: String) = when (group) {
    "Entradas" -> "💶"
    "Ajustes" -> "⚖️"
    "Supermercado" -> "🛒"
    "Restauração" -> "🍔"
    "Transporte" -> "🚗"
    "Compras" -> "🛍️"
    "Subscrições" -> "📺"
    else -> "💸"
}
