package pt.pixellusitano.aguenta.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import pt.pixellusitano.aguenta.AguentaApp
import pt.pixellusitano.aguenta.notify.Alerts

class MainActivity : ComponentActivity() {
    private var openAdjust by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A app é sempre escura: ícones claros na barra de estado mesmo com o sistema em tema claro.
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        handle(intent)
        setContent {
            AguentaTheme {
                val vm = viewModel { MainViewModel(application as AguentaApp) }
                val state by vm.state.collectAsStateWithLifecycle()
                var listenerOn by remember { mutableStateOf(true) }
                LifecycleResumeEffect(Unit) {
                    listenerOn = packageName in NotificationManagerCompat.getEnabledListenerPackages(this@MainActivity)
                    onPauseOrDispose {}
                }
                val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                AppRoot(
                    state = state,
                    onIntent = vm::onIntent,
                    listenerOn = listenerOn,
                    openListenerSettings = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                    openAdjust = openAdjust,
                    onAdjustShown = { openAdjust = false },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent) {
        if (!intent.getBooleanExtra(Alerts.EXTRA_ADJUST, false)) return
        openAdjust = true
        // Abrir "Ajustar Saldo" conta como ter visto o alerta: solta a notificação presa.
        lifecycleScope.launch {
            Alerts.acknowledge(this@MainActivity)
            Alerts.refresh(application as AguentaApp)
        }
    }
}
