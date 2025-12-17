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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.example.flightcue.service.FlightDetectionService
import com.example.flightcue.ui.HistoryScreen
import com.example.flightcue.ui.HomeScreen
import com.example.flightcue.ui.DevScreen
import com.example.flightcue.ui.SettingsScreen
import com.example.flightcue.ui.theme.FlightCueTheme

enum class MainTab { Home, History, Settings, Dev }

class MainActivity : ComponentActivity() {

    private val requestNotifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            FlightDetectionService.ensureRunning(applicationContext)
        } else {
            // Keep running service even if notifications are denied.
            FlightDetectionService.ensureRunning(applicationContext)
            Toast.makeText(
                this,
                "Notifications denied. FlightCue still runs in background.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestNotifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                FlightDetectionService.ensureRunning(applicationContext)
            }
        } else {
            FlightDetectionService.ensureRunning(applicationContext)
        }

        setContent { FlightCueTheme { RootScaffold() } }
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
                    icon = { Icon(Icons.Outlined.AirplanemodeActive, contentDescription = "Home") },
                    label = { Text("Home") }
                )
                NavigationBarItem(
                    selected = tab == MainTab.History,
                    onClick = { tab = MainTab.History },
                    icon = { Icon(Icons.Outlined.History, contentDescription = "History") },
                    label = { Text("History") }
                )
                NavigationBarItem(
                    selected = tab == MainTab.Settings,
                    onClick = { tab = MainTab.Settings },
                    icon = { Icon(Icons.Outlined.Settings, contentDescription = "Settings") },
                    label = { Text("Settings") }
                )
                NavigationBarItem(
                    selected = tab == MainTab.Dev,
                    onClick = { tab = MainTab.Dev },
                    icon = { Icon(Icons.Outlined.PlayCircle, contentDescription = "Dev") },
                    label = { Text("Dev") }
                )
            }
        }
    ) { inner ->
        when (tab) {
            MainTab.Home     -> Box(Modifier.padding(inner)) { HomeScreen() }
            MainTab.History  -> HistoryScreen(Modifier.padding(inner))
            MainTab.Settings -> Box(Modifier.padding(inner)) { SettingsScreen() }
            MainTab.Dev   -> Box(Modifier.padding(inner)) { DevScreen() }
        }
    }
}
