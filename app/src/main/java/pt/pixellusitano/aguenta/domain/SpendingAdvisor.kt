package pt.pixellusitano.aguenta.domain

data class Purchase(val merchant: String?, val cents: Long, val category: String)

data class Insight(val title: String, val text: String, val savingsCents: Long)

/** Olha para os gastos de um ciclo fechado e diz onde dava para cortar, ordenado pelo que se poupava. */
object SpendingAdvisor {
    fun advise(purchases: List<Purchase>): List<Insight> {
        val total = purchases.sumOf { it.cents }
        if (total <= 0L) return emptyList()
        val out = mutableListOf<Insight>()

        purchases.filter { !it.merchant.isNullOrBlank() }.groupBy { key(it.merchant!!) }.filterKeys { it.isNotEmpty() }.values.forEach { visits ->
            val name = visits.groupingBy { it.merchant!! }.eachCount().maxBy { it.value }.key
            val n = visits.size
            val (amount, times) = visits.groupingBy { it.cents }.eachCount().maxBy { it.value }
            if (times >= 5 && amount <= 1500) {
                out += Insight(
                    "Continhas repetidas",
                    "$name: foste lá $n vezes, $times delas para gastar ${eur(amount)}. São ${eur(amount * times)} em continhas — podíamos cortar aqui!",
                    amount * times,
                )
            } else if (n >= 10) {
                val sum = visits.sumOf { it.cents }
                out += Insight(
                    "Visitas a mais",
                    "$name: $n visitas, ${eur(sum)} no total (${eur(sum / n)} de média). Se fores metade das vezes, poupas ${eur(sum / 2)}.",
                    sum / 2,
                )
            }
        }

        purchases.filter { it.category != "Outros" }.groupBy { it.category }
            .mapValues { (_, ps) -> ps.sumOf { it.cents } }
            .maxByOrNull { it.value }
            ?.let { (category, sum) ->
                val pct = sum * 100 / total
                if (pct >= 30) out += Insight("Onde vai o dinheiro", "$category levou $pct% dos teus gastos (${eur(sum)}). Cortar um quarto dava ${eur(sum / 4)}.", sum / 4)
            }

        purchases.filter { it.cents < 500 }.takeIf { it.size >= 15 }?.let { small ->
            val sum = small.sumOf { it.cents }
            out += Insight("Trocos", "Fizeste ${small.size} compras abaixo de 5 €, que somam ${eur(sum)}. Os trocos também contam!", sum / 2)
        }

        return out.sortedByDescending { it.savingsCents }.take(4)
    }

    // "PINGO DOCE LISBOA 0123" e "Pingo Doce" são a mesma loja.
    private fun key(merchant: String) =
        merchant.lowercase().replace(Regex("""[^\p{L} ]"""), " ").trim().split(Regex("""\s+""")).take(2).joinToString(" ")
}
