package pt.pixellusitano.aguenta.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import pt.pixellusitano.aguenta.BuildConfig
import pt.pixellusitano.aguenta.data.TransactionBus
import pt.pixellusitano.aguenta.domain.BankingNotificationParser
import pt.pixellusitano.aguenta.domain.TransactionEvent
import pt.pixellusitano.aguenta.domain.eur

/** Uma notificação de banco vista pelo serviço e o que o Aguenta fez com ela (para o diagnóstico no separador Ciclo). */
data class SeenNotification(val time: Long, val pkg: String, val title: String?, val text: String?, val result: String)

/** Só em memória: desaparece quando a app fecha e nunca sai do telemóvel, a não ser que a pessoa copie a lista. */
object NotificationLog {
    private val _seen = MutableStateFlow<List<SeenNotification>>(emptyList())
    val seen: StateFlow<List<SeenNotification>> = _seen

    fun add(n: SeenNotification) = _seen.update { list ->
        (list + n).distinctBy { Triple(it.time, it.pkg, it.text) }.sortedByDescending { it.time }.take(20)
    }
}

class AguentaNotificationService : NotificationListenerService() {
    // As que já estão na barra entram só no diagnóstico: não voltam a lançar compras antigas a cada religação.
    override fun onListenerConnected() {
        runCatching { activeNotifications }.getOrNull()?.forEach { handle(it, emit = false) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = handle(sbn, emit = true)

    private fun handle(sbn: StatusBarNotification, emit: Boolean) {
        // Só em debug: "adb shell cmd notification post <pacote-do-banco> <texto>" chega como com.android.shell, com o pacote na tag.
        val pkg = if (BuildConfig.DEBUG && sbn.packageName == "com.android.shell") sbn.tag ?: return else sbn.packageName
        if (pkg == packageName) return
        val extras = sbn.notification.extras
        // getCharSequence e não getString: os bancos mandam muitas vezes SpannableString.
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()
        val watched = pkg in BankingNotificationParser.PACKAGES
        if (!watched && !BankingNotificationParser.looksLikePayment(listOfNotNull(title, text).joinToString("\n"))) return

        val event = if (watched) BankingNotificationParser.parse(pkg, title, text, sbn.postTime) else null
        NotificationLog.add(SeenNotification(sbn.postTime, pkg, title, text, describe(event, watched)))
        if (event != null && emit) TransactionBus.events.tryEmit(event)
        if (event == null && BuildConfig.DEBUG) Log.i("Aguenta", "Sem correspondência [$pkg] $title | $text")
    }

    private fun describe(e: TransactionEvent?, watched: Boolean) = when {
        !watched -> "app não suportada"
        e == null -> "não reconhecida"
        e.internal -> "transferência entre contas (ignorada)"
        e.isIncome -> "entrada de ${eur(e.amountCents)}"
        else -> "gasto de ${eur(e.amountCents)}${e.merchant?.let { " em $it" } ?: ""}"
    }
}
