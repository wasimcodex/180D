package com.example.a180d

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.a180d.ui.theme.AppTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

private val REQUIRED_PERMISSIONS: Array<String> = buildList {
    add(Manifest.permission.BLUETOOTH_CONNECT)
    add(Manifest.permission.BLUETOOTH_SCAN)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}.toTypedArray()

class MainActivity : ComponentActivity() {

    private val userSettings by lazy { UserSettings(this) }
    private var boundService by mutableStateOf<BleHeartRateService?>(null)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            boundService = (binder as BleHeartRateService.LocalBinder).service
        }
        override fun onServiceDisconnected(name: ComponentName) {
            boundService = null
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        if (results.values.all { it }) startSession()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    var zoneSettings by remember { mutableStateOf(userSettings.load()) }
                    val settings = zoneSettings
                    if (settings == null) {
                        ZoneSetupScreen(
                            onSave = { new ->
                                userSettings.save(new)
                                zoneSettings = new
                            },
                        )
                    } else {
                        HeartRateScreen(
                            service = boundService,
                            zoneSettings = settings,
                            onStartSession = { if (hasRequiredPermissions()) startSession() else permissionLauncher.launch(REQUIRED_PERMISSIONS) },
                            onEndSession = ::endSession,
                            onEditZoneSettings = { zoneSettings = null },
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, BleHeartRateService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        unbindService(serviceConnection)
        boundService = null
    }

    private fun startSession() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, BleHeartRateService::class.java).setAction(BleHeartRateService.ACTION_START),
        )
    }

    private fun endSession() {
        startService(Intent(this, BleHeartRateService::class.java).setAction(BleHeartRateService.ACTION_STOP))
    }

    private fun hasRequiredPermissions(): Boolean = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }
}

private val EMPTY_STATE_FLOW = MutableStateFlow(ConnectionState.DISCONNECTED)
private val EMPTY_SAMPLE_FLOW = MutableStateFlow<HeartRateSample?>(null)
private val EMPTY_ERROR_FLOW = MutableStateFlow<String?>(null)

private const val STALE_AFTER_MS = 5_000L

@Composable
private fun ZoneSetupScreen(onSave: (ZoneSettings) -> Unit) {
    var ageText by remember { mutableStateOf("") }
    var restingHrText by remember { mutableStateOf("") }
    val age = ageText.toIntOrNull()
    val restingHr = restingHrText.toIntOrNull()
    val isValid = age != null && age in 1..120 && restingHr != null && restingHr in 30..120

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = "Set up heart rate zones", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Used to calculate your zones from heart rate reserve.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = ageText,
            onValueChange = { ageText = it.filter(Char::isDigit).take(3) },
            label = { Text("Age") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = restingHrText,
            onValueChange = { restingHrText = it.filter(Char::isDigit).take(3) },
            label = { Text("Resting heart rate (bpm)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = { onSave(ZoneSettings(age!!, restingHr!!)) }, enabled = isValid) {
            Text("Save")
        }
    }
}

@Composable
private fun HeartRateScreen(
    service: BleHeartRateService?,
    zoneSettings: ZoneSettings,
    onStartSession: () -> Unit,
    onEndSession: () -> Unit,
    onEditZoneSettings: () -> Unit,
) {
    val connectionState by (service?.connectionState ?: EMPTY_STATE_FLOW).collectAsStateWithLifecycle()
    val sample by (service?.latestSample ?: EMPTY_SAMPLE_FLOW).collectAsStateWithLifecycle()
    val error by (service?.lastError ?: EMPTY_ERROR_FLOW).collectAsStateWithLifecycle()

    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }

    val ageMs = sample?.let { now - it.timestampMs }
    val isStale = ageMs != null && ageMs > STALE_AFTER_MS
    val sessionActive = connectionState != ConnectionState.DISCONNECTED

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = sample?.bpm?.toString() ?: "--",
            fontSize = 96.sp,
            fontWeight = FontWeight.Bold,
            color = if (isStale || sample == null) {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
            } else {
                MaterialTheme.colorScheme.primary
            },
        )
        Text(text = "bpm", style = MaterialTheme.typography.titleMedium)

        Spacer(Modifier.height(8.dp))

        val zone = sample?.let { HeartRateZones.computeZone(it.bpm, zoneSettings.age, zoneSettings.restingHr) }
        Text(
            text = zone?.let { "Zone ${"%.1f".format(it)}" } ?: "Zone --",
            style = MaterialTheme.typography.titleMedium,
            color = if (isStale || zone == null) {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
            } else {
                MaterialTheme.colorScheme.secondary
            },
        )

        Spacer(Modifier.height(16.dp))

        if (isStale) {
            ageMs?.let {
                Text(
                    text = "Stale — last reading ${it / 1000}s ago",
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(8.dp))
            }
        }

        Text(text = connectionState.toDisplayLabel())

        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(text = it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        }

        Spacer(Modifier.height(32.dp))

        Button(onClick = if (sessionActive) onEndSession else onStartSession) {
            Text(if (sessionActive) "End session" else "Start session")
        }

        if (!sessionActive) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onEditZoneSettings) {
                Text("Edit age / resting HR")
            }
        }
    }
}

private fun ConnectionState.toDisplayLabel(): String = when (this) {
    ConnectionState.DISCONNECTED -> "Not connected"
    ConnectionState.SCANNING -> "Looking for Fitbit Air…"
    ConnectionState.CONNECTING, ConnectionState.DISCOVERING -> "Connecting…"
    ConnectionState.CONNECTED -> "Connected"
    ConnectionState.RECONNECTING -> "Reconnecting…"
    ConnectionState.LINK_LOST_UNRECOVERABLE -> "Connection lost"
}
