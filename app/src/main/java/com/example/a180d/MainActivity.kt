package com.example.a180d

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

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
                        var showHistory by remember { mutableStateOf(false) }
                        if (showHistory) {
                            SessionHistoryScreen(
                                service = boundService,
                                onBack = { showHistory = false },
                            )
                        } else {
                            HeartRateScreen(
                                service = boundService,
                                zoneSettings = settings,
                                onStartSession = { if (hasRequiredPermissions()) startSession() else permissionLauncher.launch(REQUIRED_PERMISSIONS) },
                                onEndSession = ::endSession,
                                onEditZoneSettings = { zoneSettings = null },
                                onShowHistory = { showHistory = true },
                            )
                        }

                        val pendingSession by (boundService?.sessionRepository?.pendingSession ?: EMPTY_PENDING_SESSION_FLOW)
                            .collectAsStateWithLifecycle()
                        val scope = rememberCoroutineScope()
                        pendingSession?.let { session ->
                            SaveSessionDialog(
                                session = session,
                                onDiscard = { scope.launch { boundService?.sessionRepository?.discardPendingSession() } },
                                onSave = { title -> scope.launch { boundService?.sessionRepository?.savePendingSession(title) } },
                            )
                        }
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
private val EMPTY_SESSION_START_FLOW = MutableStateFlow(0L)
private val EMPTY_PENDING_SESSION_FLOW = MutableStateFlow<SessionEntity?>(null)
private val EMPTY_SESSIONS_FLOW = MutableStateFlow<List<SessionEntity>>(emptyList())

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
    onShowHistory: () -> Unit,
) {
    val connectionState by (service?.connectionState ?: EMPTY_STATE_FLOW).collectAsStateWithLifecycle()
    val sample by (service?.latestSample ?: EMPTY_SAMPLE_FLOW).collectAsStateWithLifecycle()
    val error by (service?.lastError ?: EMPTY_ERROR_FLOW).collectAsStateWithLifecycle()
    val sessionStartMs by (service?.sessionStartMs ?: EMPTY_SESSION_START_FLOW).collectAsStateWithLifecycle()

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
    val elapsedText = if (sessionStartMs > 0L) BleHeartRateService.formatElapsed(now, sessionStartMs) else null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val zone = sample?.let { HeartRateZones.computeZone(it.bpm, zoneSettings.age, zoneSettings.restingHr) }

        ZoneDial(
            bpm = sample?.bpm,
            zone = zone,
            isStale = isStale,
            modifier = Modifier
                .fillMaxWidth(0.68f)
                .aspectRatio(1f),
        )

        elapsedText?.let {
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Elapsed $it",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }

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

        ConnectionStatusRow(connectionState)

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
            TextButton(onClick = onShowHistory) {
                Text("Session history")
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

private fun ConnectionState.indicatorColor(): Color = when (this) {
    ConnectionState.DISCONNECTED -> Color(0xFF9E9E9E)
    ConnectionState.SCANNING,
    ConnectionState.CONNECTING,
    ConnectionState.DISCOVERING,
    ConnectionState.RECONNECTING,
    -> Color(0xFFFFA726)
    ConnectionState.CONNECTED -> Color(0xFF66BB6A)
    ConnectionState.LINK_LOST_UNRECOVERABLE -> Color(0xFFEF5350)
}

private fun ConnectionState.isInProgress(): Boolean = this == ConnectionState.SCANNING ||
    this == ConnectionState.CONNECTING ||
    this == ConnectionState.DISCOVERING ||
    this == ConnectionState.RECONNECTING

@Composable
private fun ConnectionStatusRow(connectionState: ConnectionState) {
    val pulse by rememberInfiniteTransition(label = "statusPulse").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(700), repeatMode = RepeatMode.Reverse),
        label = "statusPulseAlpha",
    )
    val dotAlpha = if (connectionState.isInProgress()) pulse else 1f

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(connectionState.indicatorColor().copy(alpha = dotAlpha)),
        )
        Spacer(Modifier.width(8.dp))
        Text(text = connectionState.toDisplayLabel())
    }
}

private val ZONE_COLORS = listOf(
    Color(0xFF4FC3F7), // Zone 1
    Color(0xFF66BB6A), // Zone 2
    Color(0xFFFFEE58), // Zone 3
    Color(0xFFFFA726), // Zone 4
    Color(0xFFEF5350), // Zone 5
)

private const val DIAL_START_ANGLE = 135f
private const val DIAL_SWEEP_ANGLE = 270f
private const val DIAL_SEGMENT_GAP_DEGREES = 3f

/**
 * Circular zone gauge (270° sweep, gap at the bottom) with BPM/zone readout
 * in the center. Same fraction mapping as the old bar version
 * ((zone-1.0)/5.0 across zoneIndex 1..5 = Z1..Z5) just bent from a straight
 * line into an arc; below the Z1 floor (zone < 1.0) the marker pins to the
 * start of the arc rather than getting its own segment.
 */
@Composable
private fun ZoneDial(bpm: Int?, zone: Double?, isStale: Boolean, modifier: Modifier = Modifier) {
    val alpha = if (isStale || zone == null) 0.35f else 1f
    val fraction = zone?.let { ((it - 1.0) / 5.0).coerceIn(0.0, 1.0).toFloat() }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = size.minDimension * 0.07f
            val arcDiameter = size.minDimension - strokeWidth
            val arcSize = Size(arcDiameter, arcDiameter)
            val topLeft = Offset((size.width - arcDiameter) / 2f, (size.height - arcDiameter) / 2f)
            val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)

            drawArc(
                color = Color.White.copy(alpha = 0.08f),
                startAngle = DIAL_START_ANGLE,
                sweepAngle = DIAL_SWEEP_ANGLE,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = stroke,
            )

            val segmentSweep = (DIAL_SWEEP_ANGLE - DIAL_SEGMENT_GAP_DEGREES * (ZONE_COLORS.size - 1)) / ZONE_COLORS.size
            ZONE_COLORS.forEachIndexed { index, color ->
                val segmentStart = DIAL_START_ANGLE + index * (segmentSweep + DIAL_SEGMENT_GAP_DEGREES)
                drawArc(
                    color = color.copy(alpha = alpha),
                    startAngle = segmentStart,
                    sweepAngle = segmentSweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = stroke,
                )
            }

            if (fraction != null) {
                val angleRad = Math.toRadians((DIAL_START_ANGLE + fraction * DIAL_SWEEP_ANGLE).toDouble())
                val radius = arcDiameter / 2f
                val center = Offset(topLeft.x + radius, topLeft.y + radius)
                val markerCenter = Offset(
                    center.x + radius * cos(angleRad).toFloat(),
                    center.y + radius * sin(angleRad).toFloat(),
                )
                val markerRadius = strokeWidth * 0.65f
                drawCircle(color = Color.Black.copy(alpha = 0.55f), radius = markerRadius, center = markerCenter)
                drawCircle(color = Color.White, radius = markerRadius * 0.6f, center = markerCenter)
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = bpm?.toString() ?: "--",
                fontSize = 76.sp,
                fontWeight = FontWeight.Bold,
                color = if (isStale || bpm == null) {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
            Text(text = "bpm", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                text = zone?.let { "Zone ${"%.1f".format(it)}" } ?: "Zone --",
                style = MaterialTheme.typography.titleMedium,
                color = if (isStale || zone == null) {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                } else {
                    MaterialTheme.colorScheme.secondary
                },
            )
        }
    }
}

private fun formatSessionSubtitle(session: SessionEntity): String {
    val dateFormat = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
    val durationSec = ((session.endedAtMs - session.startedAtMs) / 1000).coerceAtLeast(0)
    return "${dateFormat.format(Date(session.startedAtMs))} • ${durationSec / 60}m ${durationSec % 60}s"
}

private fun shareCsv(context: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Export session"))
}

/** Blocks dismissal via back/outside-tap so the user makes an explicit save-or-discard choice. */
@Composable
private fun SaveSessionDialog(session: SessionEntity, onDiscard: () -> Unit, onSave: (String) -> Unit) {
    var titleText by remember(session.id) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Save this session?") },
        text = {
            Column {
                Text("Recorded ${formatSessionSubtitle(session)}.")
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = titleText,
                    onValueChange = { titleText = it },
                    label = { Text("Title (optional)") },
                    singleLine = true,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(titleText) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDiscard) { Text("Discard") } },
    )
}

@Composable
private fun SessionHistoryScreen(service: BleHeartRateService?, onBack: () -> Unit) {
    val sessions by (service?.sessionRepository?.sessions ?: EMPTY_SESSIONS_FLOW)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(24.dp),
    ) {
        TextButton(onClick = onBack) { Text("← Back") }
        Spacer(Modifier.height(8.dp))
        Text(text = "Session history", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))

        if (sessions.isEmpty()) {
            Text(
                text = "No saved sessions yet.",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(sessions, key = { it.id }) { session ->
                    SessionRow(
                        session = session,
                        onExport = {
                            scope.launch {
                                val uri = service?.sessionRepository?.exportCsv(session) ?: return@launch
                                shareCsv(context, uri)
                            }
                        },
                        onDelete = { scope.launch { service?.sessionRepository?.deleteSession(session) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionRow(session: SessionEntity, onExport: () -> Unit, onDelete: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
            .padding(16.dp),
    ) {
        Text(text = session.title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            text = formatSessionSubtitle(session),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(8.dp))
        Row {
            TextButton(onClick = onExport) { Text("Export CSV") }
            TextButton(onClick = onDelete) { Text("Delete") }
        }
    }
}
