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
    const val CGD = "pt.cgd.caixadirecta"
    const val WISE = "com.transferwise.android"
    const val MANUAL = "manual"
    val PACKAGES = SAMSUNG + CGD + WISE

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

    private val TOPUP = Regex("""^(?:Adicionou|You added)""", RegexOption.IGNORE_CASE)
    private val OWN_ACCOUNT_KEYWORDS = listOf("caixa geral", "cgd", "transferwise", "wise payments", "wise europe")

    fun parse(pkg: String, title: String?, text: String?, timestamp: Long = System.currentTimeMillis()): TransactionEvent? {
        val body = listOfNotNull(title, text).joinToString("\n")
        fun event(m: MatchResult, income: Boolean, internal: Boolean = false): TransactionEvent {
            val merchant = cleanMerchant(m.groupValues.getOrNull(2))
            return TransactionEvent(toCents(m.groupValues[1]), merchant, income, pkg, timestamp, internal || isInternal(merchant))
        }
        return when (pkg) {
            in SAMSUNG -> SAMSUNG_WALLET_REGEX.find(body)?.let { event(it, income = false) }
            CGD -> CGD_REGEX.find(body)?.let { event(it, income = false) }
            // Carregar a Wise a partir do banco é dinheiro que muda de conta, não rendimento.
            WISE -> WISE_INCOME_REGEX.find(body)?.let { event(it, income = true, internal = TOPUP.containsMatchIn(it.value)) }
                ?: WISE_SPEND_REGEX.find(body)?.let { event(it, income = false) }
            else -> null
        }
    }

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

    private fun cleanMerchant(raw: String?): String? {
        val m = raw?.trim()?.trimEnd('.', '!')?.take(60) ?: return null
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
