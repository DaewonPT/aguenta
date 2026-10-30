package pt.pixellusitano.aguenta.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import pt.pixellusitano.aguenta.domain.BankingNotificationParser
import pt.pixellusitano.aguenta.domain.BudgetEngine
import pt.pixellusitano.aguenta.domain.Insight
import pt.pixellusitano.aguenta.domain.Purchase
import pt.pixellusitano.aguenta.domain.SpendingAdvisor
import pt.pixellusitano.aguenta.domain.Snapshot
import pt.pixellusitano.aguenta.domain.TransactionEvent
import pt.pixellusitano.aguenta.domain.Tx
import pt.pixellusitano.aguenta.domain.TxKind
import pt.pixellusitano.aguenta.domain.nextPayday
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

val Context.dataStore by preferencesDataStore("aguenta")

object Prefs {
    val SURVIVAL_UNTIL = longPreferencesKey("survival_until")
    val ALERT_DAY = longPreferencesKey("alert_day")
    val ALERT_LEVEL = intPreferencesKey("alert_level")
    val ACK_LEVEL = intPreferencesKey("ack_level")
    val ADVICE_CYCLE = longPreferencesKey("advice_cycle")
    val ONBOARDED = booleanPreferencesKey("onboarded")
}

/** O que o ecrã precisa do DataStore: até quando dura o modo sobrevivência e se as boas-vindas já foram vistas. */
data class AppPrefs(val survivalUntil: Long, val onboarded: Boolean)

/** O serviço de notificações emite aqui; a app recolhe e grava. */
object TransactionBus {
    val events = MutableSharedFlow<TransactionEvent>(extraBufferCapacity = 32)
}

class Repository(private val ctx: Context, private val dao: AguentaDao) {
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val mutex = Mutex()

    val config: Flow<IncomeConfigEntity> = dao.config().map { it ?: IncomeConfigEntity() }
    val appPrefs: Flow<AppPrefs> = ctx.dataStore.data.map { AppPrefs(it[Prefs.SURVIVAL_UNTIL] ?: 0L, it[Prefs.ONBOARDED] ?: false) }

    private val today = flow { while (true) { emit(LocalDate.now()); delay(60_000) } }
        .distinctUntilChanged()
        .onEach { ensureCycle(it) } // vira o ciclo à meia-noite do dia de salário mesmo sem transações novas
    private val cycle = dao.cycle().filterNotNull()

    @OptIn(ExperimentalCoroutinesApi::class)
    val transactions: Flow<List<TransactionEntity>> = cycle.flatMapLatest { dao.txsSince(it.startMillis) }

    val snapshot: Flow<Snapshot> = combine(today, cycle, transactions) { d, c, txs -> compute(c, txs, d) }

    /** O que o separador Movimentos mostra: os últimos 90 dias, não só o ciclo atual. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val history: Flow<List<TransactionEntity>> = today.flatMapLatest { d -> dao.txsSince(startOf(d.minusDays(HISTORY_DAYS - 1))) }

    /** A app só guarda 90 dias. Nunca apaga o ciclo atual nem o anterior: o saldo e os conselhos precisam deles. */
    suspend fun prune(today: LocalDate = LocalDate.now()): Int = mutex.withLock {
        ensureCycleLocked(today) // fecha primeiro os ciclos em atraso, que ainda precisam dos movimentos antigos para o saldo
        val keepFrom = dao.lastTwoCyclesNow().minOf { it.startMillis }
        dao.deleteOlderThan(minOf(startOf(today.minusDays(HISTORY_DAYS - 1)), keepFrom))
    }

    /** Conselhos sobre o ciclo anterior; null enquanto ainda não houve nenhum ciclo fechado. */
    val insights: Flow<List<Insight>?> = dao.lastTwoCycles().map { cs -> if (cs.size < 2) null else adviceFor(prev = cs[1], cur = cs[0]) }

    /** Id do ciclo atual e os conselhos sobre o anterior, para a notificação do primeiro dia. */
    suspend fun lastCycleAdvice(): Pair<Long, List<Insight>>? {
        val cs = dao.lastTwoCyclesNow()
        return if (cs.size < 2) null else cs[0].id to adviceFor(prev = cs[1], cur = cs[0])
    }

    // Gastos marcados como essenciais não entram: ninguém precisa de ouvir que devia cortar na farmácia.
    private suspend fun adviceFor(prev: BudgetCycleEntity, cur: BudgetCycleEntity) = SpendingAdvisor.advise(
        dao.txsBetween(prev.startMillis, cur.startMillis)
            .filter { it.kind == TxKind.SPEND && !it.essential }
            .map { Purchase(it.merchant, -it.deltaCents, it.category) },
    )

    suspend fun snapshotNow(): Snapshot {
        val c = ensureCycle()
        return compute(c, dao.txsSinceNow(c.startMillis), LocalDate.now())
    }

    private fun compute(c: BudgetCycleEntity, txs: List<TransactionEntity>, today: LocalDate) = BudgetEngine.compute(
        today = today,
        cycleStart = dateOf(c.startMillis),
        payday = LocalDate.ofEpochDay(c.endEpochDay),
        openingCents = c.openingCents,
        txs = txs.map { Tx(dateOf(it.timestamp), it.deltaCents, it.kind, it.essential) },
    )

    private fun dateOf(millis: Long) = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
    private fun startOf(d: LocalDate) = d.atStartOfDay(zone).toInstant().toEpochMilli()

    suspend fun ensureCycle(today: LocalDate = LocalDate.now()): BudgetCycleEntity = mutex.withLock { ensureCycleLocked(today) }

    private suspend fun ensureCycleLocked(today: LocalDate): BudgetCycleEntity {
        val cfg = dao.configNow() ?: IncomeConfigEntity()
        var c = dao.currentCycle() ?: run {
            dao.insertCycle(BudgetCycleEntity(startMillis = startOf(today), endEpochDay = nextPayday(today, cfg.paydayDay).toEpochDay(), openingCents = 0))
            dao.currentCycle()!!
        }
        // Com âncora Wise, isto só corre se o salário se atrasar: o dinheiro entra depois como rendimento normal.
        while (today.toEpochDay() >= c.endEpochDay) {
            val end = LocalDate.ofEpochDay(c.endEpochDay)
            val carry = c.openingCents + dao.sumBetween(c.startMillis, startOf(end))
            val salary = if (cfg.type == IncomeType.FIXED && cfg.anchor == CycleAnchor.DAY) cfg.baseCents else 0
            dao.insertCycle(BudgetCycleEntity(startMillis = startOf(end), endEpochDay = nextPayday(end, cfg.paydayDay).toEpochDay(), openingCents = carry + salary - cfg.fixedCostsCents))
            c = dao.currentCycle()!!
        }
        return c
    }

    private suspend fun balanceOf(c: BudgetCycleEntity) = c.openingCents + dao.sumBetween(c.startMillis, Long.MAX_VALUE)

    /** Devolve false se foi ignorado (transferência entre contas próprias ou duplicado). */
    suspend fun ingest(e: TransactionEvent): Boolean = mutex.withLock {
        val cfg = dao.configNow() ?: IncomeConfigEntity()
        if (e.internal || BankingNotificationParser.isInternal(e.merchant, cfg.ownNames.split(','))) return false
        val delta = if (e.isIncome) e.amountCents else -e.amountCents
        // Pagar com Samsung Wallet um cartão da CGD gera duas notificações da mesma compra.
        if (e.sourcePackage != BankingNotificationParser.MANUAL && dao.countSimilar(delta, e.timestamp - DUP_WINDOW_MS, e.timestamp + DUP_WINDOW_MS) > 0) return false

        val today = dateOf(e.timestamp)
        val c = ensureCycleLocked(today)
        val looksLikeSalary = e.isIncome && cfg.anchor == CycleAnchor.WISE_INCOME && cfg.baseCents > 0 && e.amountCents * 2 >= cfg.baseCents
        if (looksLikeSalary && e.timestamp - c.startMillis > MIN_CYCLE_MS) {
            dao.insertCycle(BudgetCycleEntity(startMillis = e.timestamp, endEpochDay = nextPayday(today.plusDays(7), cfg.paydayDay).toEpochDay(), openingCents = balanceOf(c) - cfg.fixedCostsCents))
        }
        dao.insertTx(
            TransactionEntity(
                timestamp = e.timestamp, deltaCents = delta, kind = if (e.isIncome) TxKind.INCOME else TxKind.SPEND,
                merchant = e.merchant, sourcePackage = e.sourcePackage, category = BankingNotificationParser.categorize(e.merchant),
            ),
        )
        true
    }

    suspend fun addManual(cents: Long, isIncome: Boolean, merchant: String?) =
        ingest(TransactionEvent(cents, merchant?.takeIf { it.isNotBlank() }, isIncome, BankingNotificationParser.MANUAL))

    suspend fun adjustBalance(targetCents: Long) = mutex.withLock {
        val c = ensureCycleLocked(LocalDate.now())
        val delta = targetCents - balanceOf(c)
        if (delta != 0L) {
            dao.insertTx(TransactionEntity(timestamp = System.currentTimeMillis(), deltaCents = delta, kind = TxKind.ADJUST, merchant = "Ajuste de saldo", sourcePackage = BankingNotificationParser.MANUAL, category = "Ajuste"))
        }
    }

    suspend fun saveConfig(cfg: IncomeConfigEntity) = mutex.withLock {
        dao.saveConfig(cfg)
        dao.currentCycle()?.let { dao.setCycleEnd(it.id, nextPayday(LocalDate.now(), cfg.paydayDay).toEpochDay()) }
    }

    suspend fun setEssential(id: Long, essential: Boolean) = dao.setEssential(id, essential)
    suspend fun markLastEssential() { dao.lastSpend()?.let { dao.setEssential(it.id, true) } }
    suspend fun delete(id: Long) = dao.deleteTx(id)
    suspend fun setSurvival(until: Long) { ctx.dataStore.edit { it[Prefs.SURVIVAL_UNTIL] = until } }
    suspend fun setOnboarded() { ctx.dataStore.edit { it[Prefs.ONBOARDED] = true } }

    private companion object {
        const val DUP_WINDOW_MS = 3 * 60_000L
        const val MIN_CYCLE_MS = 7 * 24 * 3_600_000L
        const val HISTORY_DAYS = 90L
    }
}
