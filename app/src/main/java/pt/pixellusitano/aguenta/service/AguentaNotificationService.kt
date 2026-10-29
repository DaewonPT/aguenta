package pt.pixellusitano.aguenta.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import pt.pixellusitano.aguenta.BuildConfig
import pt.pixellusitano.aguenta.data.TransactionBus
import pt.pixellusitano.aguenta.domain.BankingNotificationParser

class AguentaNotificationService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // Só em debug: "adb shell cmd notification post <pacote-do-banco> <texto>" chega como com.android.shell, com o pacote na tag.
        val pkg = if (BuildConfig.DEBUG && sbn.packageName == "com.android.shell") sbn.tag ?: return else sbn.packageName
        if (pkg !in BankingNotificationParser.PACKAGES) return
        val extras = sbn.notification.extras
        // getCharSequence e não getString: os bancos mandam muitas vezes SpannableString.
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()
        val event = BankingNotificationParser.parse(pkg, title, text, sbn.postTime)
        if (event != null) {
            TransactionBus.events.tryEmit(event)
        } else if (BuildConfig.DEBUG) {
            // Para afinar as regex com notificações reais: adb logcat -s Aguenta
            Log.i("Aguenta", "Sem correspondência [$pkg] $title | $text")
        }
    }
}
