package pt.pixellusitano.aguenta.notify

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.datastore.preferences.core.edit
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import pt.pixellusitano.aguenta.AguentaApp
import pt.pixellusitano.aguenta.R
import pt.pixellusitano.aguenta.data.Prefs
import pt.pixellusitano.aguenta.data.dataStore
import pt.pixellusitano.aguenta.domain.BudgetEngine
import pt.pixellusitano.aguenta.domain.Level
import pt.pixellusitano.aguenta.domain.eur
import pt.pixellusitano.aguenta.ui.MainActivity
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

object Alerts {
    private const val CH_RADAR = "radar"
    private const val CH_ALARM = "alarme"
    private const val ID = 1
    private const val ADVICE_ID = 2
    const val ACTION_ESSENTIAL = "pt.pixellusitano.aguenta.ESSENTIAL"
    const val ACTION_FREEZE = "pt.pixellusitano.aguenta.FREEZE"
    const val EXTRA_ADJUST = "open_adjust"
    const val SURVIVAL_MS = 6 * 3_600_000L

    fun createChannels(ctx: Context) {
        NotificationManagerCompat.from(ctx).createNotificationChannelsCompat(
            listOf(
                NotificationChannelCompat.Builder(CH_RADAR, NotificationManagerCompat.IMPORTANCE_LOW).setName("Radar diário").build(),
                NotificationChannelCompat.Builder(CH_ALARM, NotificationManagerCompat.IMPORTANCE_HIGH).setName("Alertas de teto")
                    .setVibrationEnabled(true).setVibrationPattern(longArrayOf(0, 250, 120, 250)).build(),
            ),
        )
    }

    @SuppressLint("MissingPermission") // verificado por areNotificationsEnabled()
    suspend fun refresh(app: AguentaApp) {
        val nm = NotificationManagerCompat.from(app)
        if (!nm.areNotificationsEnabled()) return
        val s = app.repo.snapshotNow()
        val st = app.dataStore.data.first()
        val sameDay = st[Prefs.ALERT_DAY] == s.today.toEpochDay()
        val lastLevel = if (sameDay) st[Prefs.ALERT_LEVEL] ?: -1 else -1
        val ackLevel = if (sameDay) st[Prefs.ACK_LEVEL] ?: -1 else -1
        // Modo sobrevivência: só o teto rebentado fura o silêncio.
        if ((st[Prefs.SURVIVAL_UNTIL] ?: 0L) > System.currentTimeMillis() && s.level != Level.EXCEEDED) {
            nm.cancel(ID)
            return
        }
        app.dataStore.edit {
            it[Prefs.ALERT_DAY] = s.today.toEpochDay()
            it[Prefs.ALERT_LEVEL] = s.level.ordinal
            if (!sameDay) it.remove(Prefs.ACK_LEVEL)
        }
        val urgent = s.level >= Level.HIGH
        val msg = BudgetEngine.message(s)
        val n = NotificationCompat.Builder(app, if (urgent) CH_ALARM else CH_RADAR)
            .setSmallIcon(R.drawable.ic_stat)
            .setColor(levelArgb(s.level))
            .setContentTitle("${eur(s.remainingCents)} para hoje")
            .setContentText(msg)
            .setStyle(NotificationCompat.BigTextStyle().bigText(msg))
            .setOnlyAlertOnce(s.level.ordinal <= lastLevel) // só toca quando o nível sobe
            .setPriority(if (urgent) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
            .setCategory(if (urgent) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_STATUS)
            .setOngoing(urgent && ackLevel < s.level.ordinal) // fica presa até carregar num botão
            .setContentIntent(activity(app, adjust = false))
            .addAction(0, "Foi Essencial", broadcast(app, ACTION_ESSENTIAL))
            .addAction(0, "Aguenta! Congelar Gastos", broadcast(app, ACTION_FREEZE))
            .addAction(0, "Ajustar Saldo", activity(app, adjust = true))
            .build()
        nm.notify(ID, n)
    }

    /** Uma vez por ciclo novo: o que dava para cortar no anterior. */
    @SuppressLint("MissingPermission")
    suspend fun adviseNewCycle(app: AguentaApp) {
        val nm = NotificationManagerCompat.from(app)
        if (!nm.areNotificationsEnabled()) return
        val (cycleId, insights) = app.repo.lastCycleAdvice() ?: return
        if (insights.isEmpty() || app.dataStore.data.first()[Prefs.ADVICE_CYCLE] == cycleId) return
        app.dataStore.edit { it[Prefs.ADVICE_CYCLE] = cycleId }
        val n = NotificationCompat.Builder(app, CH_RADAR)
            .setSmallIcon(R.drawable.ic_stat)
            .setColor(levelArgb(Level.SAFE))
            .setContentTitle("💡 Lições do ciclo passado")
            .setContentText(insights.first().text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(insights.joinToString("\n\n") { it.text }))
            .setContentIntent(activity(app, adjust = false))
            .setAutoCancel(true)
            .build()
        nm.notify(ADVICE_ID, n)
    }

    suspend fun acknowledge(ctx: Context) {
        ctx.dataStore.edit { it[Prefs.ACK_LEVEL] = it[Prefs.ALERT_LEVEL] ?: -1 }
    }

    fun levelArgb(l: Level): Int = when (l) {
        Level.SAFE -> 0xFF00F5D4
        Level.HALF, Level.HIGH -> 0xFFFFB703
        Level.EXCEEDED -> 0xFFFF0054
    }.toInt()

    private fun activity(ctx: Context, adjust: Boolean) = PendingIntent.getActivity(
        ctx, if (adjust) 1 else 0,
        Intent(ctx, MainActivity::class.java).putExtra(EXTRA_ADJUST, adjust).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun broadcast(ctx: Context, action: String) = PendingIntent.getBroadcast(
        ctx, action.hashCode(), Intent(ctx, AlertActionReceiver::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE,
    )
}

class AlertActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext as AguentaApp
        val pending = goAsync()
        app.scope.launch {
            try {
                when (intent.action) {
                    Alerts.ACTION_ESSENTIAL -> app.repo.markLastEssential()
                    Alerts.ACTION_FREEZE -> app.repo.setSurvival(System.currentTimeMillis() + Alerts.SURVIVAL_MS)
                }
                Alerts.acknowledge(app)
                Alerts.refresh(app)
            } finally {
                pending.finish()
            }
        }
    }
}

/** De manhã: vira o ciclo se for dia de salário e mostra o teto do dia. */
class DailyWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as AguentaApp
        app.repo.prune()
        Alerts.refresh(app)
        Alerts.adviseNewCycle(app)
        return Result.success()
    }

    companion object {
        fun schedule(ctx: Context) {
            val now = LocalDateTime.now()
            val nine = now.toLocalDate().atTime(9, 0).let { if (it.isAfter(now)) it else it.plusDays(1) }
            val req = PeriodicWorkRequestBuilder<DailyWorker>(1, TimeUnit.DAYS).setInitialDelay(Duration.between(now, nine)).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("radar_diario", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
