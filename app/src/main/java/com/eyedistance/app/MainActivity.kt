package com.eyedistance.app

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(
                        onStartService = { startEyeGuardService() },
                        onOpenOverlaySettings = { openOverlaySettings() },
                        onOpenUsageSettings = { openUsageSettings() }
                    )
                }
            }
        }
    }

    private fun hasOverlayPermission(): Boolean = Settings.canDrawOverlays(this)

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        } else {
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun openOverlaySettings() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun openUsageSettings() {
        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }

    private fun startEyeGuardService() {
        if (!hasOverlayPermission()) {
            Toast.makeText(this, "Please grant Overlay Permission first", Toast.LENGTH_SHORT).show()
            openOverlaySettings()
            return
        }
        val intent = Intent(this, DistanceService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Toast.makeText(this, "Eye Guard Service Started!", Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun MainScreen(
    onStartService: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onOpenUsageSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = "🛡️ Eye Distance Guard", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(32.dp))

        Button(onClick = onOpenOverlaySettings, modifier = Modifier.fillMaxWidth()) {
            Text("1. Grant 'Display Over Apps' Permission")
        }
        Spacer(modifier = Modifier.height(12.dp))

        Button(onClick = onOpenUsageSettings, modifier = Modifier.fillMaxWidth()) {
            Text("2. Grant 'Usage Access' Permission")
        }
        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onStartService,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Text("▶ Start Background Protection")
        }
    }
}
