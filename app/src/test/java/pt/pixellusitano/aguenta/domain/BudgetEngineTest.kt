package pt.pixellusitano.aguenta.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class BudgetEngineTest {
    private val start = LocalDate.of(2026, 10, 28)
    private val payday = LocalDate.of(2026, 11, 28) // 31 dias
    private val today = LocalDate.of(2026, 11, 8)   // faltam 20

    private fun spend(cents: Long, date: LocalDate = today, essential: Boolean = false) = Tx(date, -cents, TxKind.SPEND, essential)

    @Test fun tetoDiarioEhSaldoDaManhaSobreDiasQueFaltam() {
        val s = BudgetEngine.compute(today, start, payday, 40000, listOf(spend(500)))
        assertEquals(20, s.daysLeft)
        assertEquals(2000L, s.baseCents)      // (40000 - 500 + 500) / 20
        assertEquals(1500L, s.remainingCents)
        assertEquals(Level.SAFE, s.level)
    }

    @Test fun niveis() {
        assertEquals(Level.HALF, BudgetEngine.compute(today, start, payday, 40000, listOf(spend(1000))).level)
        assertEquals(Level.HIGH, BudgetEngine.compute(today, start, payday, 40000, listOf(spend(1600))).level)
        val over = BudgetEngine.compute(today, start, payday, 40000, listOf(spend(2720)))
        assertEquals(Level.EXCEEDED, over.level)
        assertEquals(-720L, over.remainingCents)
    }

    @Test fun essencialAmortizaPelosDiasQueFaltam() {
        val s = BudgetEngine.compute(today, start, payday, 40000, listOf(spend(10000, essential = true)))
        assertEquals(0L, s.spentTodayCents)
        assertEquals(1500L, s.baseCents)      // (40000 - 10000) / 20
        assertEquals(Level.SAFE, s.level)
    }

    @Test fun pistaComMediaDos3Dias() {
        val txs = listOf(spend(3000, today.minusDays(2)), spend(3000, today.minusDays(1)), spend(3000))
        val s = BudgetEngine.compute(today, start, payday, 40000, txs)
        assertEquals(31000L / 3000.0, s.runwayDays!!, 1e-9)
        assertEquals(LocalDate.of(2026, 11, 18), s.zeroDate)
        // base = (31000 + 3000) / 20 = 1700, gastou 3000 hoje.
        assertEquals("Para aí! Já rebentaste o teto diário em 13,00 €. A este ritmo ficas a zeros no dia 18.", BudgetEngine.message(s))
    }

    @Test fun mensagens() {
        val safe = BudgetEngine.compute(today, start, payday, 40000, emptyList())
        assertNull(safe.runwayDays)
        assertEquals("Ainda tens 20,00 € para hoje. Estás seguro até dia 28.", BudgetEngine.message(safe))
        val over = BudgetEngine.compute(today, start, payday, 40000, listOf(spend(2720)))
        // Um só dia mau não chega para a média de 3 dias ameaçar o dia 28.
        assertEquals("Para aí! Já rebentaste o teto diário em 7,20 €.", BudgetEngine.message(over))
        val broke = BudgetEngine.compute(today, start, payday, 0, emptyList())
        assertEquals("Estás a zeros. Nem mais um cêntimo até dia 28.", BudgetEngine.message(broke))
    }

    @Test fun velocidade() {
        // Ritmo ideal 1000/dia; 12 dias decorridos (28/10 a 8/11) com 24000 gastos = o dobro.
        val s = BudgetEngine.compute(today, start, payday, 31000, listOf(spend(24000, start)))
        assertEquals(2.0, s.velocity!!, 0.01)
    }

    @Test fun proximoDiaDeSalario() {
        assertEquals(LocalDate.of(2026, 11, 28), nextPayday(LocalDate.of(2026, 10, 28), 28))
        assertEquals(LocalDate.of(2026, 10, 28), nextPayday(LocalDate.of(2026, 10, 27), 28))
        assertEquals(LocalDate.of(2027, 2, 28), nextPayday(LocalDate.of(2027, 1, 31), 31))
    }

    @Test fun dinheiro() {
        assertEquals("-7,20 €", eur(-720))
        assertEquals(1250L, parseEurosOrNull("12,5"))
        assertEquals(-3000L, parseEurosOrNull("-30"))
        assertNull(parseEurosOrNull("abc"))
    }
}
