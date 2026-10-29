package pt.pixellusitano.aguenta.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.pixellusitano.aguenta.domain.BankingNotificationParser.CGD
import pt.pixellusitano.aguenta.domain.BankingNotificationParser.WISE
import pt.pixellusitano.aguenta.domain.BankingNotificationParser.parse

class BankingNotificationParserTest {
    private val samsung = "com.samsung.android.spay"

    @Test fun samsungPagamento() {
        val e = parse(samsung, "Samsung Wallet", "Pagamento de 14,80 € em Pingo Doce")!!
        assertEquals(1480L, e.amountCents)
        assertEquals("Pingo Doce", e.merchant)
        assertFalse(e.isIncome)
    }

    @Test fun samsungCompraAprovadaComHifen() {
        val e = parse(samsung, null, "Compra aprovada: 23,00 € - Continente")!!
        assertEquals(2300L, e.amountCents)
        assertEquals("Continente", e.merchant)
    }

    @Test fun samsungEuroAntesDoValor() {
        assertEquals(1250L, parse(samsung, null, "Pagamento de € 12,50 em Lidl")!!.amountCents)
    }

    @Test fun cgdDebito() {
        val e = parse(CGD, "Caixadirecta", "Debito de 45,00 EUR no seu cartao")!!
        assertEquals(4500L, e.amountCents)
        assertNull(e.merchant)
    }

    @Test fun cgdMbWay() {
        assertEquals(1520L, parse(CGD, null, "MB WAY: Pagamento de 15,20 EUR")!!.amountCents)
    }

    @Test fun wiseGasto() {
        val e = parse(WISE, null, "Gastou 12,30 EUR em Uber")!!
        assertEquals(1230L, e.amountCents)
        assertEquals("Uber", e.merchant)
        assertFalse(e.isIncome)
    }

    @Test fun wiseRendimentoComMilhares() {
        val e = parse(WISE, "Dinheiro recebido", "Recebeu 1.250,00 EUR de Empresa X")!!
        assertEquals(125000L, e.amountCents)
        assertTrue(e.isIncome)
        assertFalse(e.internal)
        assertEquals("Empresa X", e.merchant)
    }

    @Test fun wiseEmIngles() {
        assertEquals(1230L, parse(WISE, null, "You spent 12.30 EUR at Uber")!!.amountCents)
    }

    @Test fun carregamentoWiseEhInterno() {
        assertTrue(parse(WISE, null, "Adicionou 100,00 EUR")!!.internal)
    }

    @Test fun transferenciaParaCgdEhInterna() {
        assertTrue(parse(WISE, null, "Enviou 200,00 EUR a Caixa Geral de Depositos")!!.internal)
        assertTrue(BankingNotificationParser.isInternal("João Silva", listOf("joão silva")))
    }

    @Test fun ignoraOutrasMoedasEPublicidade() {
        assertNull(parse(WISE, null, "Gastou 12,30 GBP em Tesco"))
        assertNull(parse(CGD, "Novidade", "Conheça o novo crédito pessoal da Caixa"))
        assertNull(parse("com.whatsapp", null, "Pagamento de 14,80 € em Pingo Doce"))
    }

    @Test fun categorias() {
        assertEquals("Supermercado", BankingNotificationParser.categorize("Pingo Doce"))
        assertEquals("Transporte", BankingNotificationParser.categorize("Uber"))
        assertEquals("Restauração", BankingNotificationParser.categorize("Uber Eats"))
        assertEquals("Outros", BankingNotificationParser.categorize(null))
    }
}
