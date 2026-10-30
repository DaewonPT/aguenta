package pt.pixellusitano.aguenta.domain

enum class TxKind { SPEND, INCOME, ADJUST }

// Valores em cêntimos (Long): somar Doubles de euros acumula erros de arredondamento.
data class TransactionEvent(
    val amountCents: Long,
    val merchant: String?,
    val isIncome: Boolean,
    val sourcePackage: String,
    val timestamp: Long = System.currentTimeMillis(),
    val internal: Boolean = false,
    val currency: String = "EUR",
)

object BankingNotificationParser {
    val SAMSUNG = setOf("com.samsung.android.spay", "com.samsung.android.spayfw")
    const val CGD = "cgd.pt.caixadirectaparticulares" // app Caixadirecta; o "pt.cgd.caixadirecta" da especificação não existe
    const val MBWAY = "pt.sibs.android.mbway"
    const val WISE = "com.transferwise.android"
    const val MANUAL = "manual"
    val PACKAGES = SAMSUNG + CGD + MBWAY + WISE

    // "1.250,00" | "12,50" | "12.50" | "45". O formato com milhares vem primeiro para "1.250,00" não parar em "1.25".
    private const val AMT = """(\d{1,3}(?:\.\d{3})+(?:,\d{2})?|\d+(?:[,.]\d{2})?)"""
    private val opts = setOf(RegexOption.IGNORE_CASE)

    // "Pagamento de 14,80 € em Pingo Doce" | "Compra aprovada: 23,00 € - Continente"
    val SAMSUNG_WALLET_REGEX = Regex(
        """(?:Pagamento|Compra|Transa[çc][ãa]o)(?:\s+(?:aprovad[ao]|de|no valor de))*\s*:?\s*(?:€\s*)?$AMT\s*(?:€|EUR)?\s+(?:em|no|na|-)\s+(.+)""",
        opts,
    )

    // "Debito de 45,00 EUR no seu cartao" | "MB WAY: Pagamento de 15,20 EUR"
    val CGD_REGEX = Regex(
        """(?:D[eé]bito|Pagamento|Compra)\s*(?:MB\s*WAY)?\s*:?\s*(?:de|no valor de)?\s*(?:€\s*)?$AMT\s*(?:EUR|€)(?:\s+(?:em|a|no|na)\s+(.+))?""",
        opts,
    )

    // "Gastou 12,30 EUR em Uber" (e a versão inglesa, caso o telemóvel esteja em inglês)
    val WISE_SPEND_REGEX = Regex(
        """(?:Gastou|Pagou|Enviou|You spent|You paid|You sent)\s*(?:€\s*)?$AMT\s*(?:EUR|€)\s+(?:em|a|para|at|to)\s+(.+)""",
        opts,
    )

    // "Recebeu 1.250,00 EUR de Empresa X" | "Adicionou 100,00 EUR"
    val WISE_INCOME_REGEX = Regex(
        """(?:Recebeu|Adicionou|You received|You added)\s*(?:€\s*)?$AMT\s*(?:EUR|€)(?:\s+(?:de|from))?\s*(.*)""",
        opts,
    )

    // Os textos reais da Caixa, MB WAY e Samsung ainda não estão confirmados: além do formato conhecido,
    // aceita qualquer notificação desses bancos com uma palavra de gasto e um valor em euros.
    private val SPEND_WORD = Regex(
        """\b(?:compra|pagamento|pagou|pagaste|d[eé]bito|transa[çc][ãa]o|levantamento|gastou|gastaste|enviou|enviaste|transferiu|transferiste)\b""",
        opts,
    )
    // Pedidos por pagar (MB WAY), operações falhadas e dinheiro a entrar não são gastos.
    private val NOT_A_SPEND = Regex(
        """\b(?:pedido|recusad\w*|cancelad\w*|falh\w*|n[ãa]o autorizad\w*|receb\w*|creditad\w*|reembolso|devolu[çc][ãa]o)\b""",
        opts,
    )
    private val EUR_AMOUNT = Regex("""€\s*$AMT|$AMT\s*(?:€|EUR\b)""", opts)
    // A loja vem depois do valor, a seguir a "em"/"a"/"-" e com maiúscula: "… com o cartão ****1234 em GLOVO".
    private val MERCHANT_AFTER = Regex("""(?:^|\s)(?:-|em|a|no|na|ao|à|at)\s+(\p{Lu}[^\n.;,]*)""")

    private val TOPUP = Regex("""^(?:Adicionou|You added)""", RegexOption.IGNORE_CASE)
    private val OWN_ACCOUNT_KEYWORDS = listOf("caixa geral", "cgd", "transferwise", "wise payments", "wise europe")

    fun parse(pkg: String, title: String?, text: String?, timestamp: Long = System.currentTimeMillis()): TransactionEvent? {
        val body = listOfNotNull(title, text).joinToString("\n")
        fun event(m: MatchResult, income: Boolean, internal: Boolean = false): TransactionEvent {
            val merchant = cleanMerchant(m.groupValues.getOrNull(2))
            return TransactionEvent(toCents(m.groupValues[1]), merchant, income, pkg, timestamp, internal || isInternal(merchant))
        }
        val known = when (pkg) {
            in SAMSUNG -> SAMSUNG_WALLET_REGEX
            CGD, MBWAY -> CGD_REGEX
            else -> null
        }
        return when {
            known != null -> bankSpend(known, body)?.let { (cents, merchant) ->
                TransactionEvent(cents, merchant, false, pkg, timestamp, isInternal(merchant))
            }
            // Carregar a Wise a partir do banco é dinheiro que muda de conta, não rendimento.
            pkg == WISE -> WISE_INCOME_REGEX.find(body)?.let { event(it, income = true, internal = TOPUP.containsMatchIn(it.value)) }
                ?: WISE_SPEND_REGEX.find(body)?.let { event(it, income = false) }
            else -> null
        }
    }

    /** Samsung Wallet, Caixa e MB WAY: o formato conhecido primeiro; senão, uma palavra de gasto e o primeiro valor em euros. */
    private fun bankSpend(known: Regex, body: String): Pair<Long, String?>? {
        if (NOT_A_SPEND.containsMatchIn(body)) return null
        known.find(body)?.let { m ->
            val merchant = cleanMerchant(m.groupValues.getOrNull(2)) ?: cleanMerchant(merchantAfter(body, m.range.last + 1))
            return toCents(m.groupValues[1]) to merchant
        }
        if (!SPEND_WORD.containsMatchIn(body)) return null
        val m = EUR_AMOUNT.find(body) ?: return null
        val cents = toCents(m.groupValues[1].ifEmpty { m.groupValues[2] })
        return if (cents > 0) cents to cleanMerchant(merchantAfter(body, m.range.last + 1)) else null
    }

    private fun merchantAfter(body: String, from: Int) = MERCHANT_AFTER.find(body, from.coerceAtMost(body.length))?.groupValues?.get(1)

    /** Para o diagnóstico: notificações de outras apps que parecem pagamentos (por exemplo, de um banco ainda não suportado). */
    fun looksLikePayment(body: String) = SPEND_WORD.containsMatchIn(body) && EUR_AMOUNT.containsMatchIn(body)

    fun isInternal(merchant: String?, ownNames: List<String> = emptyList()): Boolean {
        val m = merchant?.lowercase() ?: return false
        return (OWN_ACCOUNT_KEYWORDS + ownNames.map { it.trim().lowercase() }.filter { it.length >= 3 }).any { it in m }
    }

    fun toCents(raw: String): Long {
        val s = raw.trim()
        val normalized = when {
            ',' in s -> s.replace(".", "").replace(',', '.')
            Regex("""\d+\.\d{2}""").matches(s) -> s
            else -> s.replace(".", "")
        }
        return normalized.toBigDecimal().movePointRight(2).toLong()
    }

    // "GLOVO efetuado com sucesso" → "GLOVO"
    private val STATUS_TAIL = Regex("""\s+(?:foi\s+)?(?:efetuad|realizad|aprovad|autorizad|conclu[ií]d|com sucesso)\w*.*$""", RegexOption.IGNORE_CASE)

    private fun cleanMerchant(raw: String?): String? {
        val m = raw?.replace(STATUS_TAIL, "")?.trim()?.trimEnd('.', '!')?.take(60) ?: return null
        return m.takeUnless { it.isBlank() || it.startsWith("seu ", true) || it.startsWith("o seu ", true) }
    }

    private val CATEGORIES = linkedMapOf(
        "Supermercado" to listOf("pingo doce", "continente", "lidl", "aldi", "minipreço", "minipreco", "mercadona", "auchan", "intermarché", "intermarche", "spar"),
        "Restauração" to listOf("uber eats", "glovo", "bolt food", "mcdonald", "burger", "kfc", "telepizza", "restaurante", "café", "cafe", "pastelaria", "starbucks"),
        "Transporte" to listOf("uber", "bolt", "carris", "metro", "cp ", "comboios", "galp", "repsol", "bp ", "prio", "via verde", "fertagus"),
        "Compras" to listOf("amazon", "worten", "fnac", "zara", "primark", "ikea", "decathlon", "shein", "aliexpress"),
        "Subscrições" to listOf("netflix", "spotify", "disney", "hbo", "apple", "google", "playstation", "steam"),
    )

    fun categorize(merchant: String?): String {
        val m = " ${merchant?.lowercase() ?: return "Outros"} "
        return CATEGORIES.entries.firstOrNull { (_, keys) -> keys.any { it in m } }?.key ?: "Outros"
    }
}
