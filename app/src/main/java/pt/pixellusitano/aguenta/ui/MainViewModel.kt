package pt.pixellusitano.aguenta.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import pt.pixellusitano.aguenta.AguentaApp
import pt.pixellusitano.aguenta.data.IncomeConfigEntity
import pt.pixellusitano.aguenta.data.TransactionEntity
import pt.pixellusitano.aguenta.domain.Insight
import pt.pixellusitano.aguenta.domain.Snapshot
import pt.pixellusitano.aguenta.notify.Alerts

data class UiState(
    val snapshot: Snapshot? = null,
    val history: List<TransactionEntity> = emptyList(),
    val config: IncomeConfigEntity = IncomeConfigEntity(),
    val survivalUntil: Long = 0,
    val insights: List<Insight>? = null,
) {
    val survival: Boolean get() = survivalUntil > System.currentTimeMillis()
}

sealed interface UiIntent {
    data class AddManual(val cents: Long, val income: Boolean, val merchant: String?) : UiIntent
    data class AdjustBalance(val cents: Long) : UiIntent
    data class SaveConfig(val config: IncomeConfigEntity) : UiIntent
    data class ToggleEssential(val tx: TransactionEntity) : UiIntent
    data class Delete(val id: Long) : UiIntent
    data object Freeze : UiIntent
    data object Unfreeze : UiIntent
}

class MainViewModel(private val app: AguentaApp) : ViewModel() {
    private val repo = app.repo

    val state: StateFlow<UiState> = combine(repo.snapshot, repo.history, repo.config, repo.survivalUntil, repo.insights) { s, history, cfg, until, tips ->
        UiState(s, history, cfg, until, tips)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    fun onIntent(intent: UiIntent) {
        viewModelScope.launch {
            when (intent) {
                is UiIntent.AddManual -> repo.addManual(intent.cents, intent.income, intent.merchant)
                is UiIntent.AdjustBalance -> repo.adjustBalance(intent.cents)
                is UiIntent.SaveConfig -> repo.saveConfig(intent.config)
                is UiIntent.ToggleEssential -> repo.setEssential(intent.tx.id, !intent.tx.essential)
                is UiIntent.Delete -> repo.delete(intent.id)
                UiIntent.Freeze -> repo.setSurvival(System.currentTimeMillis() + Alerts.SURVIVAL_MS)
                UiIntent.Unfreeze -> repo.setSurvival(0)
            }
            Alerts.refresh(app)
        }
    }
}
