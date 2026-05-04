package com.example.flightcue

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AirplanemodeActive
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.flightcue.data.SettingsStore
import com.example.flightcue.service.FlightDetectionService
import com.example.flightcue.ui.DevScreen
import com.example.flightcue.ui.HistoryScreen
import com.example.flightcue.ui.HomeScreen
import com.example.flightcue.ui.SettingsScreen
import com.example.flightcue.ui.theme.FlightCueTheme
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

enum class MainTab { Home, History, Settings, Dev }

class MainActivity : ComponentActivity() {

    private lateinit var settings: SettingsStore

    private var notifRequestInFlight = false
    private var pendingStartAfterNotifGrant = false

    private val requestNotifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notifRequestInFlight = false

        if (granted) {
            Toast.makeText(this, getString(R.string.main_notif_granted_toast), Toast.LENGTH_SHORT).show()

            if (pendingStartAfterNotifGrant) {
                pendingStartAfterNotifGrant = false
                FlightDetectionService.start(applicationContext)
            }
        } else {
            pendingStartAfterNotifGrant = false
            Toast.makeText(this, getString(R.string.main_notif_denied_toast), Toast.LENGTH_LONG).show()
            FlightDetectionService.stop(applicationContext)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        settings = SettingsStore(applicationContext)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                settings.detectionEnabled.collectLatest { enabled ->
                    if (enabled) {
                        if (Build.VERSION.SDK_INT >= 33) {
                            val granted = ContextCompat.checkSelfPermission(
                                this@MainActivity,
                                Manifest.permission.POST_NOTIFICATIONS
                            ) == PackageManager.PERMISSION_GRANTED

                            if (!granted) {
                                pendingStartAfterNotifGrant = true
                                maybeRequestNotificationPermissionOnce()
                                return@collectLatest
                            }
                        }
                        FlightDetectionService.start(applicationContext)
                    } else {
                        pendingStartAfterNotifGrant = false
                        FlightDetectionService.stop(applicationContext)
                    }
                }
            }
        }

        setContent { FlightCueTheme { RootScaffold() } }
    }

    private fun maybeRequestNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < 33) return
        if (notifRequestInFlight) return

        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

        if (!granted) {
            notifRequestInFlight = true
            requestNotifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
private fun RootScaffold() {
    var tab by remember { mutableStateOf(MainTab.Home) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == MainTab.Home,
                    onClick = { tab = MainTab.Home },
                    icon = { Icon(Icons.Outlined.AirplanemodeActive, contentDescription = stringResource(R.string.nav_home)) },
                    label = { Text(stringResource(R.string.nav_home)) }
                )
                NavigationBarItem(
                    selected = tab == MainTab.History,
                    onClick = { tab = MainTab.History },
                    icon = { Icon(Icons.Outlined.History, contentDescription = stringResource(R.string.nav_history)) },
                    label = { Text(stringResource(R.string.nav_history)) }
                )
                NavigationBarItem(
                    selected = tab == MainTab.Settings,
                    onClick = { tab = MainTab.Settings },
                    icon = { Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.nav_settings)) },
                    label = { Text(stringResource(R.string.nav_settings)) }
                )
                NavigationBarItem(
                    selected = tab == MainTab.Dev,
                    onClick = { tab = MainTab.Dev },
                    icon = { Icon(Icons.Outlined.PlayCircle, contentDescription = stringResource(R.string.nav_dev)) },
                    label = { Text(stringResource(R.string.nav_dev)) }
                )
            }
        }
    ) { inner ->
        when (tab) {
            MainTab.Home     -> Box(Modifier.padding(inner)) { HomeScreen() }
            MainTab.History  -> HistoryScreen(Modifier.padding(inner))
            MainTab.Settings -> Box(Modifier.padding(inner)) { SettingsScreen() }
            MainTab.Dev      -> Box(Modifier.padding(inner)) { DevScreen() }
        }
    }
}