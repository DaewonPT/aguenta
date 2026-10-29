package pt.pixellusitano.aguenta.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit.DAYS
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

data class Tx(val date: LocalDate, val deltaCents: Long, val kind: TxKind, val essential: Boolean = false)

enum class Level { SAFE, HALF, HIGH, EXCEEDED }

data class Snapshot(
    val today: LocalDate,
    val payday: LocalDate,
    val balanceCents: Long,
    val baseCents: Long,
    val spentTodayCents: Long,
    val remainingCents: Long,
    val daysLeft: Int,
    val totalDays: Int,
    val velocity: Double?,
    val runwayDays: Double?,
    val level: Level,
) {
    val zeroDate: LocalDate? get() = runwayDays?.let { today.plusDays(floor(it).toLong()) }
    val safeUntilPayday: Boolean get() = runwayDays == null || runwayDays >= daysLeft
}

object BudgetEngine {
    /** [payday] é o dia em que o próximo salário entra (fim exclusivo do ciclo). */
    fun compute(today: LocalDate, cycleStart: LocalDate, payday: LocalDate, openingCents: Long, txs: List<Tx>): Snapshot {
        val dLeft = max(1L, DAYS.between(today, payday)).toInt()
        val dTotal = max(1L, DAYS.between(cycleStart, payday)).toInt()
        val balance = openingCents + txs.sumOf { it.deltaCents }
        val spends = txs.filter { it.kind == TxKind.SPEND }
        val spentToday = spends.filter { it.date == today && !it.essential }.sumOf { -it.deltaCents }
        // Gastos essenciais saem do saldo mas não do teto de hoje: ficam amortizados por todos os dias que faltam.
        val base = (balance + spentToday) / dLeft
        val remaining = base - spentToday

        val elapsed = (DAYS.between(cycleStart, today) + 1).toInt().coerceIn(1, dTotal)
        val spentCycle = spends.sumOf { -it.deltaCents }
        val ideal = (balance + spentCycle).toDouble() / dTotal
        val velocity = if (ideal > 0) (spentCycle.toDouble() / elapsed) / ideal else null

        val window = min(3, elapsed)
        val trailing = spends.filter { it.date > today.minusDays(window.toLong()) && !it.date.isAfter(today) }.sumOf { -it.deltaCents }
        val avg = trailing.toDouble() / window
        val runway = if (avg > 0) max(0L, balance) / avg else null

        val level = when {
            base <= 0 || remaining < 0 -> Level.EXCEEDED
            spentToday >= base * 0.8 -> Level.HIGH
            spentToday >= base * 0.5 -> Level.HALF
            else -> Level.SAFE
        }
        return Snapshot(today, payday, balance, base, spentToday, remaining, dLeft, dTotal, velocity, runway, level)
    }

    fun message(s: Snapshot): String {
        val zeros = s.zeroDate?.takeUnless { s.safeUntilPayday }?.let { " A este ritmo ficas a zeros no dia ${it.dayOfMonth}." } ?: ""
        return when {
            s.baseCents <= 0 -> "Estás a zeros. Nem mais um cêntimo até dia ${s.payday.dayOfMonth}."
            s.level == Level.EXCEEDED -> "Para aí! Já rebentaste o teto diário em ${eur(-s.remainingCents)}.$zeros"
            s.level == Level.HIGH -> "Cuidado: só restam ${eur(s.remainingCents)} para hoje.$zeros"
            s.level == Level.HALF -> "Atenção: Já gastaste metade da tua folga de hoje. Restam ${eur(s.remainingCents)}.$zeros"
            zeros.isEmpty() -> "Ainda tens ${eur(s.remainingCents)} para hoje. Estás seguro até dia ${s.payday.dayOfMonth}."
            else -> "Ainda tens ${eur(s.remainingCents)} para hoje.$zeros"
        }
    }
}

fun nextPayday(from: LocalDate, day: Int): LocalDate {
    fun inMonth(d: LocalDate) = d.withDayOfMonth(min(day, d.lengthOfMonth()))
    val candidate = inMonth(from)
    return if (candidate.isAfter(from)) candidate else inMonth(from.plusMonths(1).withDayOfMonth(1))
}

fun eur(cents: Long): String {
    val a = abs(cents)
    return "${if (cents < 0) "-" else ""}${a / 100},${(a % 100).toString().padStart(2, '0')} €"
}

/** Lê o que a pessoa escreve ("12,5", "-30", "1250.00"); null se não for um valor. */
fun parseEurosOrNull(input: String): Long? =
    input.trim().replace(" ", "").replace("€", "")
        .takeIf { Regex("""-?\d+([,.]\d{1,2})?""").matches(it) }
        ?.replace(',', '.')?.toBigDecimal()?.movePointRight(2)?.toLong()
