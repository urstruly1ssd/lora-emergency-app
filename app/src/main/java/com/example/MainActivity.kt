package com.example

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.example.ui.screens.LoRaEmergencyApp
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.MainActivityViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MainActivityViewModel by viewModels()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Permissions successfully checked on startup
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Request essential runtime permissions on startup to avoid SecurityException crashes.
        // We only request permissions with protection level 'dangerous' (runtime permissions).
        // BLUETOOTH and BLUETOOTH_ADMIN are normal permissions on < Android 12, so requesting them dynamically is invalid and can cause crashes.
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        try {
            requestPermissionLauncher.launch(permissions.toTypedArray())
        } catch (e: Exception) {
            // Safeguard against OS launch failures
        }

        // Full Edge-to-Edge bleed screen
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                // Monitor if SOS Beacon mode is active
                val isBeaconActive by viewModel.isBeaconActive.collectAsState()
                
                // If SOS is active, override screen timeout safely via a view property setter
                val view = androidx.compose.ui.platform.LocalView.current
                androidx.compose.runtime.DisposableEffect(isBeaconActive) {
                    try {
                        view.keepScreenOn = isBeaconActive
                    } catch (e: Exception) {
                        // Prevent any crashes
                    }
                    onDispose {}
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize()
                ) { innerPadding ->
                    // Main landing content
                    LoRaEmergencyApp(viewModel)
                }
            }
        }
    }
}
