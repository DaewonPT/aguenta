package pt.pixellusitano.aguenta

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import pt.pixellusitano.aguenta.data.AguentaDb
import pt.pixellusitano.aguenta.data.Repository
import pt.pixellusitano.aguenta.data.TransactionBus
import pt.pixellusitano.aguenta.notify.Alerts
import pt.pixellusitano.aguenta.notify.DailyWorker

class AguentaApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var repo: Repository
        private set

    override fun onCreate() {
        super.onCreate()
        repo = Repository(this, AguentaDb.open(this).dao())
        Alerts.createChannels(this)
        // UNDISPATCHED: subscreve já, antes de o serviço ligar; um SharedFlow sem subscritores deita fora o evento.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            TransactionBus.events.collect {
                if (repo.ingest(it)) {
                    Alerts.refresh(this@AguentaApp)
                    Alerts.adviseNewCycle(this@AguentaApp) // o salário na Wise pode ter acabado de abrir um ciclo novo
                }
            }
        }
        scope.launch { repo.prune() }
        DailyWorker.schedule(this)
    }
}
