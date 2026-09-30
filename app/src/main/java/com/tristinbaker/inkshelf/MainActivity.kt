package com.tristinbaker.inkshelf

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.tristinbaker.inkshelf.core.storage.DownloadFolder
import com.tristinbaker.inkshelf.ui.AppViewModel

class AppViewModelFactory(
    private val locator: ServiceLocator,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(AppViewModel::class.java)) {
            "Unknown ViewModel ${modelClass.name}"
        }
        return AppViewModel(locator) as T
    }
}

class MainActivity : ComponentActivity() {

    private lateinit var locator: ServiceLocator

    /**
     * Playback and downloads are foreground services, so without this their
     * notification is silently dropped on API 33+ and the service is killed
     * within minutes. Asked for here rather than at playback time so the answer
     * does not interrupt the first book.
     */
    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        locator = ServiceLocator.get(this)
        ensureNotificationPermission()
        setContent {
            InkShelfApp(locator) { pickDownloadFolder.launch(null) }
        }
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /**
     * Folder browser for the download location.
     *
     * The picked tree has to survive the process going away, since downloads run
     * in a service, so the grant is made persistable here before the URI is
     * handed on.
     */
    private val pickDownloadFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                DownloadFolder.persist(this, uri)
                viewModel.setDownloadFolder(uri, this)
            }
        }

    private val viewModel: AppViewModel by lazy {
        ViewModelProvider(this, AppViewModelFactory(locator))[AppViewModel::class.java]
    }

    override fun onResume() {
        super.onResume()
        // The panel does not reliably retain its display mode across app
        // restarts, so re-assert it as soon as we are foregrounded.
        locator.meink.init()
        locator.meink.reapply(locator.settings.einkMode)
    }
}
