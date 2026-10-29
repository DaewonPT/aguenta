package pt.pixellusitano.aguenta.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpendingAdvisorTest {
    private fun p(merchant: String?, cents: Long, category: String = "Outros") = Purchase(merchant, cents, category)

    @Test fun continhasRepetidasNoPingoDoce() {
        // 73 idas: 32 de 1,39 € e 41 de valores variados; nomes como vêm do banco.
        val purchases = List(32) { p("Pingo Doce", 139, "Supermercado") } +
            List(41) { p("PINGO DOCE LISBOA 0${it % 3}", 800L + it, "Supermercado") }
        val first = SpendingAdvisor.advise(purchases).first { it.title == "Continhas repetidas" }
        assertEquals("Pingo Doce: foste lá 73 vezes, 32 delas para gastar 1,39 €. São 44,48 € em continhas — podíamos cortar aqui!", first.text)
    }

    @Test fun visitasAMaisCategoriaETrocos() {
        val purchases = List(12) { p("Uber", 900L + it * 10, "Transporte") } + List(15) { p("Café ${'A' + it}", 120, "Restauração") }
        val insights = SpendingAdvisor.advise(purchases)
        assertEquals(listOf("Visitas a mais", "Onde vai o dinheiro", "Trocos"), insights.map { it.title })
        assertEquals("Uber: 12 visitas, 114,60 € no total (9,55 € de média). Se fores metade das vezes, poupas 57,30 €.", insights[0].text)
        assertTrue(insights[1].text.startsWith("Transporte levou 86% dos teus gastos"))
    }

    @Test fun cicloSemExagerosNaoDaConselhos() {
        assertEquals(emptyList<Insight>(), SpendingAdvisor.advise(List(4) { p("Lidl", 2000, "Supermercado") } + p(null, 3000) + p("Renda", 40000)))
        assertEquals(emptyList<Insight>(), SpendingAdvisor.advise(emptyList()))
    }
}
