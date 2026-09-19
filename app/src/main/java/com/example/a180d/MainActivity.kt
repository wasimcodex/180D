package com.example.a180d

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.a180d.ui.theme.AppTheme
import com.example.a180d.ui.theme.HeroNumberStyle
import com.example.a180d.ui.theme.SpaceGroteskFamily
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
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
            var themeMode by remember { mutableStateOf(userSettings.loadThemeMode()) }
            AppTheme(themeMode = themeMode) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    var zoneSettings by remember { mutableStateOf(userSettings.load()) }
                    var keepScreenOnEnabled by remember { mutableStateOf(userSettings.loadKeepScreenOn()) }
                    val settings = zoneSettings
                    if (settings == null) {
                        ZoneSetupScreen(
                            onSave = { new ->
                                userSettings.save(new)
                                zoneSettings = new
                            },
                        )
                    } else {
                        AppRoot(
                            service = boundService,
                            zoneSettings = settings,
                            userSettings = userSettings,
                            onSettingsChange = { zoneSettings = it },
                            onStartSession = { if (hasRequiredPermissions()) startSession() else permissionLauncher.launch(REQUIRED_PERMISSIONS) },
                            onEndSession = ::endSession,
                            keepScreenOnEnabled = keepScreenOnEnabled,
                            onToggleKeepScreenOn = {
                                keepScreenOnEnabled = !keepScreenOnEnabled
                                userSettings.saveKeepScreenOn(keepScreenOnEnabled)
                            },
                            themeMode = themeMode,
                            onThemeModeChange = { mode ->
                                userSettings.saveThemeMode(mode)
                                themeMode = mode
                            },
                        )

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
private val EMPTY_RECENT_SAMPLES_FLOW = MutableStateFlow<List<HeartRateSample>>(emptyList())
private val EMPTY_ERROR_FLOW = MutableStateFlow<String?>(null)
private val EMPTY_SESSION_START_FLOW = MutableStateFlow(0L)
private val EMPTY_PENDING_SESSION_FLOW = MutableStateFlow<SessionEntity?>(null)
private val EMPTY_DEVICE_LIST_FLOW = MutableStateFlow<List<BluetoothDevice>>(emptyList())

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
            .padding(24.dp)
            .verticalScroll(rememberScrollState())
            .imePadding(),
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

/** Hosts the drawer (profile + session history) and swaps in the session-detail screen when a row is tapped. */
@Composable
private fun AppRoot(
    service: BleHeartRateService?,
    zoneSettings: ZoneSettings,
    userSettings: UserSettings,
    onSettingsChange: (ZoneSettings) -> Unit,
    onStartSession: () -> Unit,
    onEndSession: () -> Unit,
    keepScreenOnEnabled: Boolean,
    onToggleKeepScreenOn: () -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
) {
    val context = LocalContext.current
    val historyRepository = remember { SessionRepository(context.applicationContext) }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var selectedSession by remember { mutableStateOf<SessionEntity?>(null) }
    var showEditProfile by remember { mutableStateOf(false) }

    val session = selectedSession
    if (session != null) {
        SessionDetailScreen(
            session = session,
            repository = historyRepository,
            zoneSettings = zoneSettings,
            onBack = { selectedSession = null },
        )
    } else {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                AppDrawerContent(
                    zoneSettings = zoneSettings,
                    repository = historyRepository,
                    onEditProfile = { showEditProfile = true },
                    onSessionClick = {
                        selectedSession = it
                        scope.launch { drawerState.close() }
                    },
                    onClose = { scope.launch { drawerState.close() } },
                    themeMode = themeMode,
                    onThemeModeChange = onThemeModeChange,
                )
            },
        ) {
            HeartRateScreen(
                service = service,
                zoneSettings = zoneSettings,
                onStartSession = onStartSession,
                onEndSession = onEndSession,
                keepScreenOnEnabled = keepScreenOnEnabled,
                onToggleKeepScreenOn = onToggleKeepScreenOn,
                onOpenMenu = { scope.launch { drawerState.open() } },
            )
        }
    }

    if (showEditProfile) {
        EditProfileDialog(
            current = zoneSettings,
            onDismiss = { showEditProfile = false },
            onSave = { updated ->
                userSettings.save(updated)
                onSettingsChange(updated)
                showEditProfile = false
            },
        )
    }
}

@Composable
private fun HeartRateScreen(
    service: BleHeartRateService?,
    zoneSettings: ZoneSettings,
    onStartSession: () -> Unit,
    onEndSession: () -> Unit,
    keepScreenOnEnabled: Boolean,
    onToggleKeepScreenOn: () -> Unit,
    onOpenMenu: () -> Unit,
) {
    val connectionState by (service?.connectionState ?: EMPTY_STATE_FLOW).collectAsStateWithLifecycle()
    val sample by (service?.latestSample ?: EMPTY_SAMPLE_FLOW).collectAsStateWithLifecycle()
    val recentSamples by (service?.recentSamples ?: EMPTY_RECENT_SAMPLES_FLOW).collectAsStateWithLifecycle()
    val error by (service?.lastError ?: EMPTY_ERROR_FLOW).collectAsStateWithLifecycle()
    val sessionStartMs by (service?.sessionStartMs ?: EMPTY_SESSION_START_FLOW).collectAsStateWithLifecycle()
    val discoveredDevices by (service?.discoveredDevices ?: EMPTY_DEVICE_LIST_FLOW).collectAsStateWithLifecycle()

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
    val window = (LocalContext.current as Activity).window
    DisposableEffect(sessionActive, keepScreenOnEnabled) {
        if (sessionActive && keepScreenOnEnabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    val elapsedText = if (sessionStartMs > 0L) BleHeartRateService.formatElapsed(now, sessionStartMs) else null
    val zone = sample?.let { HeartRateZones.computeZone(it.bpm, zoneSettings.age, zoneSettings.restingHr) }
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SquareIconButton(onClick = onOpenMenu) { MenuGlyph(tint = MaterialTheme.colorScheme.onSurface) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (sessionActive) {
                    SquareIconButton(onClick = onToggleKeepScreenOn) {
                        KeepAwakeGlyph(
                            tint = if (keepScreenOnEnabled) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                }
                ConnectionStatusPill(connectionState)
            }
        }

        Crossfade(
            targetState = isLandscape,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            animationSpec = tween(300),
            label = "heartRateBodyOrientation",
        ) { landscape ->
            if (landscape) {
                HeartRateLandscapeBody(
                    sample = sample,
                    zone = zone,
                    isStale = isStale,
                    ageMs = ageMs,
                    elapsedText = elapsedText,
                    error = error,
                    recentSamples = recentSamples,
                    zoneSettings = zoneSettings,
                    sessionActive = sessionActive,
                    onStartSession = onStartSession,
                    onEndSession = onEndSession,
                )
            } else {
                HeartRatePortraitBody(
                    sample = sample,
                    zone = zone,
                    isStale = isStale,
                    ageMs = ageMs,
                    elapsedText = elapsedText,
                    error = error,
                    recentSamples = recentSamples,
                    zoneSettings = zoneSettings,
                    sessionActive = sessionActive,
                    onStartSession = onStartSession,
                    onEndSession = onEndSession,
                )
            }
        }
    }

    if (connectionState == ConnectionState.AWAITING_DEVICE_SELECTION && discoveredDevices.isNotEmpty()) {
        DevicePickerDialog(
            devices = discoveredDevices,
            onSelect = { service?.selectDevice(it) },
            onCancel = { service?.cancelDeviceSelection() },
        )
    }
}

@Composable
private fun HeartRatePortraitBody(
    sample: HeartRateSample?,
    zone: Double?,
    isStale: Boolean,
    ageMs: Long?,
    elapsedText: String?,
    error: String?,
    recentSamples: List<HeartRateSample>,
    zoneSettings: ZoneSettings,
    sessionActive: Boolean,
    onStartSession: () -> Unit,
    onEndSession: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            ZoneDial(
                bpm = sample?.bpm,
                zone = zone,
                isStale = isStale,
                modifier = Modifier.fillMaxWidth(0.72f).aspectRatio(1f),
            )

            Spacer(Modifier.height(14.dp))

            HeartRateInfoRow(elapsedText = elapsedText, zone = zone)

            if (isStale) {
                ageMs?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(text = "Stale — last reading ${it / 1000}s ago", color = MaterialTheme.colorScheme.error)
                }
            }
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(text = it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
            }
        }

        TrendGraphCard(samples = recentSamples, currentZone = zone, zoneSettings = zoneSettings, modifier = Modifier.padding(horizontal = 20.dp))

        Spacer(Modifier.height(18.dp))

        Box(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 30.dp)) {
            SessionActionButton(active = sessionActive, onClick = if (sessionActive) onEndSession else onStartSession)
        }
    }
}

@Composable
private fun HeartRateLandscapeBody(
    sample: HeartRateSample?,
    zone: Double?,
    isStale: Boolean,
    ageMs: Long?,
    elapsedText: String?,
    error: String?,
    recentSamples: List<HeartRateSample>,
    zoneSettings: ZoneSettings,
    sessionActive: Boolean,
    onStartSession: () -> Unit,
    onEndSession: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(0.45f).fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            ZoneDial(
                bpm = sample?.bpm,
                zone = zone,
                isStale = isStale,
                modifier = Modifier.fillMaxHeight(0.75f).aspectRatio(1f),
            )

            Spacer(Modifier.height(14.dp))

            HeartRateInfoRow(elapsedText = elapsedText, zone = zone)

            if (isStale) {
                ageMs?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(text = "Stale — last reading ${it / 1000}s ago", color = MaterialTheme.colorScheme.error)
                }
            }
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(text = it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
            }
        }

        Spacer(Modifier.width(20.dp))

        Column(
            modifier = Modifier.weight(0.55f).fillMaxHeight(),
            verticalArrangement = Arrangement.Center,
        ) {
            TrendGraphCard(samples = recentSamples, currentZone = zone, zoneSettings = zoneSettings)

            Spacer(Modifier.height(20.dp))

            SessionActionButton(active = sessionActive, onClick = if (sessionActive) onEndSession else onStartSession)
        }
    }
}

@Composable
private fun HeartRateInfoRow(elapsedText: String?, zone: Double?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        elapsedText?.let {
            InfoPill {
                ClockGlyph(tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                Spacer(Modifier.width(7.dp))
                Text(it, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f))
            }
            Spacer(Modifier.width(10.dp))
            Text("·", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
            Spacer(Modifier.width(10.dp))
        }
        InfoPill {
            Text(
                text = zone?.let { "Zone %.1f".format(it) } ?: "Heart Rate",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
            )
        }
    }
}

@Composable
private fun ConnectionStatusPill(connectionState: ConnectionState) {
    val pulse by rememberInfiniteTransition(label = "statusPulse").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(700), repeatMode = RepeatMode.Reverse),
        label = "statusPulseAlpha",
    )
    val dotAlpha = if (connectionState.isInProgress()) pulse else 1f

    InfoPill {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(connectionState.indicatorColor().copy(alpha = dotAlpha)),
        )
        Spacer(Modifier.width(7.dp))
        Text(connectionState.toDisplayLabel(), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

private fun ConnectionState.toDisplayLabel(): String = when (this) {
    ConnectionState.DISCONNECTED -> "Not connected"
    ConnectionState.SCANNING -> "Looking for heart rate tracker…"
    ConnectionState.AWAITING_DEVICE_SELECTION -> "Choose a device…"
    ConnectionState.CONNECTING, ConnectionState.DISCOVERING -> "Connecting…"
    ConnectionState.CONNECTED -> "Connected"
    ConnectionState.RECONNECTING -> "Reconnecting…"
    ConnectionState.LINK_LOST_UNRECOVERABLE -> "Connection lost"
}

private fun ConnectionState.indicatorColor(): Color = when (this) {
    ConnectionState.DISCONNECTED -> Color(0xFF9E9E9E)
    ConnectionState.SCANNING,
    ConnectionState.AWAITING_DEVICE_SELECTION,
    ConnectionState.CONNECTING,
    ConnectionState.DISCOVERING,
    ConnectionState.RECONNECTING,
    -> Color(0xFFFFA726)
    ConnectionState.CONNECTED -> Color(0xFF66BB6A)
    ConnectionState.LINK_LOST_UNRECOVERABLE -> Color(0xFFEF5350)
}

private fun ConnectionState.isInProgress(): Boolean = this == ConnectionState.SCANNING ||
    this == ConnectionState.AWAITING_DEVICE_SELECTION ||
    this == ConnectionState.CONNECTING ||
    this == ConnectionState.DISCOVERING ||
    this == ConnectionState.RECONNECTING

private val ZONE_COLORS = listOf(
    Color(0xFF4FC3F7), // Zone 1
    Color(0xFF66BB6A), // Zone 2
    Color(0xFFFFEE58), // Zone 3
    Color(0xFFFFA726), // Zone 4
    Color(0xFFEF5350), // Zone 5
)

/** Deepened per-zone colors for text sitting on a light background, where the raw zone hue (esp. yellow) is too washed out to read. */
private val ZONE_CHIP_TEXT_LIGHT = listOf(
    Color(0xFF0288D1),
    Color(0xFF2E7D32),
    Color(0xFF8D6E00),
    Color(0xFFE65100),
    Color(0xFFC62828),
)

/** -1 when there's no reading yet; otherwise 0..4 into [ZONE_COLORS], using the same fraction-of-arc mapping as the dial's marker. */
private fun zoneSegmentIndex(zone: Double?): Int {
    if (zone == null) return -1
    val fraction = ((zone - 1.0) / 5.0).coerceIn(0.0, 1.0)
    return (fraction * ZONE_COLORS.size).toInt().coerceIn(0, ZONE_COLORS.size - 1)
}

private fun zoneSegmentColor(zone: Double?): Color {
    val index = zoneSegmentIndex(zone)
    return if (index >= 0) ZONE_COLORS[index] else Color(0xFF9E9E9E)
}

@Composable
private fun zoneChipTextColor(index: Int): Color =
    if (isSystemInDarkTheme()) ZONE_COLORS[index] else ZONE_CHIP_TEXT_LIGHT[index]

@Composable
private fun ZoneChip(index: Int, label: String, modifier: Modifier = Modifier) {
    val bgAlpha = if (isSystemInDarkTheme()) 0.16f else 0.22f
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(ZONE_COLORS[index].copy(alpha = bgAlpha))
            .padding(horizontal = 9.dp, vertical = 3.dp),
    ) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = zoneChipTextColor(index))
    }
}

private const val DIAL_START_ANGLE = 135f
private const val DIAL_SWEEP_ANGLE = 270f
private const val DIAL_SEGMENT_GAP_DEGREES = 3f

/**
 * Circular zone gauge (270° sweep, gap at the bottom) with BPM/zone readout in
 * the center, plus a soft glow tinted to the current zone. Same fraction
 * mapping as before ((zone-1.0)/5.0 across zoneIndex 1..5 = Z1..Z5), just
 * restyled: theme-aware track/marker so it reads on both light and dark.
 */
@Composable
private fun ZoneDial(bpm: Int?, zone: Double?, isStale: Boolean, modifier: Modifier = Modifier) {
    val alpha = if (isStale || zone == null) 0.35f else 1f
    val fraction = zone?.let { ((it - 1.0) / 5.0).coerceIn(0.0, 1.0).toFloat() }
    val glowColor = zoneSegmentColor(zone)
    val trackColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f)
    val backgroundColor = MaterialTheme.colorScheme.background
    val markerDotColor = MaterialTheme.colorScheme.onBackground

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = size.minDimension * 0.07f
            val arcDiameter = size.minDimension - strokeWidth
            val arcSize = Size(arcDiameter, arcDiameter)
            val topLeft = Offset((size.width - arcDiameter) / 2f, (size.height - arcDiameter) / 2f)
            val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            val radius = arcDiameter / 2f
            val arcCenter = Offset(topLeft.x + radius, topLeft.y + radius)

            if (!isStale && zone != null) {
                val glowRadius = radius * 1.35f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(glowColor.copy(alpha = 0.18f), glowColor.copy(alpha = 0f)),
                        center = arcCenter,
                        radius = glowRadius,
                    ),
                    radius = glowRadius,
                    center = arcCenter,
                )
            }

            drawArc(
                color = trackColor,
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
                val markerCenter = Offset(
                    arcCenter.x + radius * cos(angleRad).toFloat(),
                    arcCenter.y + radius * sin(angleRad).toFloat(),
                )
                val markerRadius = strokeWidth * 0.62f
                drawCircle(color = backgroundColor, radius = markerRadius, center = markerCenter)
                drawCircle(color = markerDotColor, radius = markerRadius * 0.6f, center = markerCenter)
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = bpm?.toString() ?: "--",
                style = HeroNumberStyle,
                color = if (isStale || bpm == null) {
                    MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f)
                } else {
                    MaterialTheme.colorScheme.onBackground
                },
            )
            Text(
                text = "BPM",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f),
            )
            Spacer(Modifier.height(12.dp))
            if (zone != null && !isStale) {
                val index = zoneSegmentIndex(zone)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(ZONE_COLORS[index].copy(alpha = if (isSystemInDarkTheme()) 0.16f else 0.24f))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Text("ZONE ${"%.1f".format(zone)}", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = zoneChipTextColor(index))
                }
            } else {
                Text("Zone --", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f))
            }
        }
    }
}

@Composable
private fun TrendGraphCard(samples: List<HeartRateSample>, currentZone: Double?, zoneSettings: ZoneSettings, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(24.dp))
            .padding(18.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("TREND", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f))
            Text("LAST 5 MIN", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f))
        }
        Spacer(Modifier.height(10.dp))
        if (samples.size < 2) {
            Box(Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Trend appears once a few readings arrive",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            InteractiveBpmChart(
                samples = samples,
                lineColor = zoneSegmentColor(currentZone),
                zoneSettings = zoneSettings,
                highlightLatest = true,
                modifier = Modifier.fillMaxWidth().height(110.dp),
            )
        }
    }
}

private data class ChartBounds(val minTs: Long, val maxTs: Long, val bpmMin: Double, val bpmMax: Double)

/** Same axis padding/scaling both [drawBpmChart] and its interactive overlay need to agree on. */
private fun computeChartBounds(samples: List<HeartRateSample>, zoneBoundariesBpm: List<Double>?): ChartBounds {
    val dataMin = samples.minOf { it.bpm }
    val dataMax = samples.maxOf { it.bpm }
    return ChartBounds(
        minTs = samples.first().timestampMs,
        maxTs = samples.last().timestampMs,
        bpmMin = zoneBoundariesBpm?.first()?.coerceAtMost(dataMin - 4.0) ?: (dataMin - 4).toDouble(),
        bpmMax = zoneBoundariesBpm?.last()?.coerceAtLeast(dataMax + 4.0) ?: (dataMax + 4).toDouble(),
    )
}

/** Maps a touch x-position (0..widthPx) back to a timestamp, using the same bounds as [computeChartBounds]. */
private fun timestampForX(xPx: Float, samples: List<HeartRateSample>, widthPx: Int): Long {
    val minTs = samples.first().timestampMs
    val maxTs = samples.last().timestampMs
    val fraction = if (widthPx > 0) (xPx / widthPx).coerceIn(0f, 1f) else 0f
    return minTs + (fraction * (maxTs - minTs)).toLong()
}

/** Nearest sample to [targetTs] by binary search; samples must be timestamp-ascending. */
private fun nearestSampleIndex(samples: List<HeartRateSample>, targetTs: Long): Int {
    var lo = 0
    var hi = samples.lastIndex
    while (lo < hi) {
        val mid = (lo + hi) / 2
        if (samples[mid].timestampMs < targetTs) lo = mid + 1 else hi = mid
    }
    if (lo > 0 && kotlin.math.abs(samples[lo - 1].timestampMs - targetTs) <= kotlin.math.abs(samples[lo].timestampMs - targetTs)) {
        return lo - 1
    }
    return lo
}

/**
 * A [drawBpmChart] wrapped with press/drag-to-scrub: touching the chart shows
 * a crosshair and a floating tooltip with the BPM/time/zone at that point,
 * following the finger; lifting clears it. The gesture responds from the
 * initial touch-down rather than waiting for a drag threshold.
 */
@Composable
private fun InteractiveBpmChart(
    samples: List<HeartRateSample>,
    lineColor: Color,
    zoneSettings: ZoneSettings,
    highlightLatest: Boolean,
    zoneBoundariesBpm: List<Double>? = null,
    modifier: Modifier = Modifier,
) {
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val surfaceColor = MaterialTheme.colorScheme.surface
    var selectedIndex by remember { mutableStateOf<Int?>(null) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val latestSamples = rememberUpdatedState(samples)

    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .matchParentSize()
                .onSizeChanged { canvasSize = it }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        fun select(x: Float) {
                            val current = latestSamples.value
                            selectedIndex = if (current.size < 2 || size.width == 0) {
                                null
                            } else {
                                nearestSampleIndex(current, timestampForX(x, current, size.width))
                            }
                        }

                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        select(down.position.x)
                        val pointerId = down.id
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                            if (!change.pressed) break
                            change.consume()
                            select(change.position.x)
                        }
                        selectedIndex = null
                    }
                },
        ) {
            drawBpmChart(
                samples = samples,
                lineColor = lineColor,
                textColor = onSurfaceColor,
                surfaceColor = surfaceColor,
                zoneBoundariesBpm = zoneBoundariesBpm,
                highlightLatest = highlightLatest,
                selectedIndex = selectedIndex,
            )
        }

        val index = selectedIndex
        if (index != null && index in samples.indices && canvasSize.width > 0) {
            val bounds = computeChartBounds(samples, zoneBoundariesBpm)
            val tsRange = (bounds.maxTs - bounds.minTs).coerceAtLeast(1L)
            val bpmRange = (bounds.bpmMax - bounds.bpmMin).coerceAtLeast(1.0)
            val sample = samples[index]
            val pointPx = Offset(
                x = canvasSize.width * (sample.timestampMs - bounds.minTs).toFloat() / tsRange,
                y = canvasSize.height - (canvasSize.height * ((sample.bpm - bounds.bpmMin) / bpmRange)).toFloat(),
            )
            ScrubTooltip(sample = sample, zoneSettings = zoneSettings, pointPx = pointPx, canvasSize = canvasSize)
        }
    }
}

@Composable
private fun ScrubTooltip(sample: HeartRateSample, zoneSettings: ZoneSettings, pointPx: Offset, canvasSize: IntSize) {
    val density = LocalDensity.current
    val zoneIndex = zoneSegmentIndex(HeartRateZones.computeZone(sample.bpm, zoneSettings.age, zoneSettings.restingHr)).coerceAtLeast(0)
    val widthDp = 122.dp
    val heightDp = 54.dp

    val offsetX: androidx.compose.ui.unit.Dp
    val offsetY: androidx.compose.ui.unit.Dp
    with(density) {
        val widthPx = widthDp.toPx()
        val heightPx = heightDp.toPx()
        val gapPx = 10.dp.toPx()
        val x = (pointPx.x - widthPx / 2f).coerceIn(0f, (canvasSize.width - widthPx).coerceAtLeast(0f))
        val yAbove = pointPx.y - heightPx - gapPx
        val y = if (yAbove >= 0f) yAbove else (pointPx.y + gapPx).coerceAtMost((canvasSize.height - heightPx).coerceAtLeast(0f))
        offsetX = x.toDp()
        offsetY = y.toDp()
    }

    Box(
        modifier = Modifier
            .offset(x = offsetX, y = offsetY)
            .width(widthDp)
            .height(heightDp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(sample.bpm.toString(), fontFamily = SpaceGroteskFamily, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.width(3.dp))
                Text("bpm", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
            }
            Text(
                text = "${timeLabelWithSeconds(sample.timestampMs)} · Z${zoneIndex + 1}",
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = zoneChipTextColor(zoneIndex),
            )
        }
    }
}

/**
 * Shared line/area BPM chart. With [zoneBoundariesBpm] it shades 5 zone bands
 * behind the line (session-detail's full view); without it, draws two plain
 * gridlines (the live trend card, where zone-accurate bands aren't worth the
 * clutter at that size). [highlightLatest] marks the most recent point
 * (live trend); otherwise the peak is marked (session detail) — both only
 * when [selectedIndex] is null, since a scrub in progress replaces the
 * highlight with a crosshair at the touched sample instead.
 */
private fun DrawScope.drawBpmChart(
    samples: List<HeartRateSample>,
    lineColor: Color,
    textColor: Color,
    surfaceColor: Color,
    zoneBoundariesBpm: List<Double>? = null,
    highlightLatest: Boolean,
    selectedIndex: Int? = null,
) {
    val bounds = computeChartBounds(samples, zoneBoundariesBpm)
    val tsRange = (bounds.maxTs - bounds.minTs).coerceAtLeast(1L)
    val bpmMin = bounds.bpmMin
    val bpmMax = bounds.bpmMax
    val bpmRange = (bpmMax - bpmMin).coerceAtLeast(1.0)

    fun xFor(ts: Long) = size.width * (ts - bounds.minTs).toFloat() / tsRange
    fun yFor(bpm: Double) = size.height - (size.height * ((bpm - bpmMin) / bpmRange)).toFloat()

    if (zoneBoundariesBpm != null && zoneBoundariesBpm.size >= 6) {
        val labels = listOf("Z1", "Z2", "Z3", "Z4", "Z5")
        for (index in 0 until 5) {
            val yTop = yFor(zoneBoundariesBpm[index + 1])
            val yBottom = yFor(zoneBoundariesBpm[index])
            drawRect(
                color = ZONE_COLORS[index].copy(alpha = 0.08f),
                topLeft = Offset(0f, yTop),
                size = Size(size.width, (yBottom - yTop).coerceAtLeast(0f)),
            )
            drawContext.canvas.nativeCanvas.drawText(
                labels[index],
                size.width - 4.dp.toPx(),
                yBottom - 4.dp.toPx(),
                textPaint(textColor.copy(alpha = 0.4f), 9.sp.toPx(), android.graphics.Paint.Align.RIGHT),
            )
        }
    } else {
        listOf(0.25f, 0.75f).forEach { fractionOfHeight ->
            val y = size.height * fractionOfHeight
            drawLine(
                color = textColor.copy(alpha = 0.12f),
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 4.dp.toPx())),
            )
            val bpmAtLine = (bpmMax - bpmRange * fractionOfHeight).roundToInt()
            drawContext.canvas.nativeCanvas.drawText(
                bpmAtLine.toString(),
                size.width,
                y + 3.dp.toPx(),
                textPaint(textColor.copy(alpha = 0.5f), 9.sp.toPx(), android.graphics.Paint.Align.RIGHT),
            )
        }
    }

    val linePath = Path()
    samples.forEachIndexed { index, sample ->
        val x = xFor(sample.timestampMs)
        val y = yFor(sample.bpm.toDouble())
        if (index == 0) linePath.moveTo(x, y) else linePath.lineTo(x, y)
    }
    val fillPath = Path().apply {
        addPath(linePath)
        lineTo(size.width, size.height)
        lineTo(0f, size.height)
        close()
    }
    drawPath(fillPath, brush = Brush.verticalGradient(listOf(lineColor.copy(alpha = 0.3f), lineColor.copy(alpha = 0f))))
    drawPath(linePath, color = lineColor, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

    if (selectedIndex != null && selectedIndex in samples.indices) {
        val selected = samples[selectedIndex]
        val sx = xFor(selected.timestampMs)
        val sy = yFor(selected.bpm.toDouble())
        drawLine(
            color = textColor.copy(alpha = 0.25f),
            start = Offset(sx, 0f),
            end = Offset(sx, size.height),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx())),
        )
        drawCircle(surfaceColor, radius = 7.dp.toPx(), center = Offset(sx, sy))
        drawCircle(lineColor, radius = 4.dp.toPx(), center = Offset(sx, sy))
    } else {
        val highlight = if (highlightLatest) samples.last() else samples.maxByOrNull { it.bpm }!!
        val hx = xFor(highlight.timestampMs)
        val hy = yFor(highlight.bpm.toDouble())
        drawCircle(surfaceColor, radius = 7.dp.toPx(), center = Offset(hx, hy))
        drawCircle(lineColor, radius = 4.dp.toPx(), center = Offset(hx, hy))
        drawContext.canvas.nativeCanvas.drawText(
            highlight.bpm.toString(),
            hx.coerceIn(16.dp.toPx(), size.width - 16.dp.toPx()),
            (hy - 10.dp.toPx()).coerceAtLeast(12.dp.toPx()),
            textPaint(textColor, 12.sp.toPx(), android.graphics.Paint.Align.CENTER, bold = true),
        )
    }
}

private fun textPaint(color: Color, textSizePx: Float, align: android.graphics.Paint.Align, bold: Boolean = false) =
    android.graphics.Paint().apply {
        this.color = color.toArgb()
        this.textSize = textSizePx
        this.textAlign = align
        this.isAntiAlias = true
        this.isFakeBoldText = bold
    }

@Composable
private fun SessionActionButton(active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    if (active) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.5.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f), RoundedCornerShape(999.dp))
                .clickable(onClick = onClick),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(11.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.error))
            Spacer(Modifier.width(10.dp))
            Text("End Session", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
        }
    } else {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(MaterialTheme.colorScheme.primary)
                .clickable(onClick = onClick),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Start Session", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onPrimary)
        }
    }
}

@Composable
private fun SquareIconButton(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
        content = { content() },
    )
}

@Composable
private fun InfoPill(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(999.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = { content() },
    )
}

// ---- Hand-drawn glyphs (no icon-font dependency; thin-stroke, 24x24-authored) ----

@Composable
private fun MenuGlyph(tint: Color, modifier: Modifier = Modifier.size(20.dp)) {
    Canvas(modifier = modifier) {
        val strokeWidth = 1.75.dp.toPx()
        listOf(0.22f, 0.5f, 0.78f).forEach { fractionOfHeight ->
            val y = size.height * fractionOfHeight
            drawLine(tint, Offset(0f, y), Offset(size.width, y), strokeWidth, cap = StrokeCap.Round)
        }
    }
}

@Composable
private fun KeepAwakeGlyph(tint: Color, modifier: Modifier = Modifier.size(20.dp)) {
    Canvas(modifier = modifier) {
        val strokeWidth = 1.75.dp.toPx()
        val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        drawArc(color = tint, startAngle = 200f, sweepAngle = 140f, useCenter = false, style = stroke)
        drawArc(color = tint, startAngle = 20f, sweepAngle = 140f, useCenter = false, style = stroke)
        drawCircle(color = tint, radius = size.minDimension * 0.14f, center = center)
    }
}

@Composable
private fun CloseGlyph(tint: Color, modifier: Modifier = Modifier.size(16.dp)) {
    Canvas(modifier = modifier) {
        val strokeWidth = 1.9.dp.toPx()
        drawLine(tint, Offset(0f, 0f), Offset(size.width, size.height), strokeWidth, cap = StrokeCap.Round)
        drawLine(tint, Offset(size.width, 0f), Offset(0f, size.height), strokeWidth, cap = StrokeCap.Round)
    }
}

@Composable
private fun ChevronLeftGlyph(tint: Color, modifier: Modifier = Modifier.size(18.dp)) {
    Canvas(modifier = modifier) {
        val path = Path().apply {
            moveTo(size.width * 0.62f, size.height * 0.1f)
            lineTo(size.width * 0.3f, size.height * 0.5f)
            lineTo(size.width * 0.62f, size.height * 0.9f)
        }
        drawPath(path, tint, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
private fun ClockGlyph(tint: Color, modifier: Modifier = Modifier.size(14.dp)) {
    Canvas(modifier = modifier) {
        val strokeWidth = 1.6.dp.toPx()
        val radius = size.minDimension / 2f - strokeWidth
        drawCircle(tint, radius = radius, center = center, style = Stroke(width = strokeWidth))
        drawLine(tint, center, Offset(center.x, center.y - radius * 0.55f), strokeWidth, cap = StrokeCap.Round)
        drawLine(tint, center, Offset(center.x + radius * 0.4f, center.y + radius * 0.15f), strokeWidth, cap = StrokeCap.Round)
    }
}

@Composable
private fun PencilGlyph(tint: Color, modifier: Modifier = Modifier.size(16.dp)) {
    Canvas(modifier = modifier) {
        val s = size.width / 24f
        fun p(x: Float, y: Float) = Offset(x * s, y * s)
        val strokeWidth = 1.9.dp.toPx()
        drawLine(tint, p(12f, 20f), p(21f, 20f), strokeWidth, cap = StrokeCap.Round)
        val body = Path().apply {
            moveTo(16.5f * s, 3.5f * s)
            quadraticTo(19.6f * s, 3.2f * s, 19.6f * s, 6.6f * s)
            lineTo(7f * s, 19f * s)
            lineTo(3f * s, 20f * s)
            lineTo(4f * s, 16f * s)
            close()
        }
        drawPath(body, tint, style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
private fun DownloadGlyph(tint: Color, modifier: Modifier = Modifier.size(15.dp)) {
    Canvas(modifier = modifier) {
        val s = size.width / 24f
        fun p(x: Float, y: Float) = Offset(x * s, y * s)
        val strokeWidth = 1.9.dp.toPx()
        drawLine(tint, p(12f, 3f), p(12f, 15f), strokeWidth, cap = StrokeCap.Round)
        val arrow = Path().apply {
            moveTo(7f * s, 10f * s)
            lineTo(12f * s, 15f * s)
            lineTo(17f * s, 10f * s)
        }
        drawPath(arrow, tint, style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawLine(tint, p(5f, 21f), p(19f, 21f), strokeWidth, cap = StrokeCap.Round)
    }
}

@Composable
private fun TrashGlyph(tint: Color, modifier: Modifier = Modifier.size(16.dp)) {
    Canvas(modifier = modifier) {
        val s = size.width / 24f
        fun p(x: Float, y: Float) = Offset(x * s, y * s)
        val strokeWidth = 1.9.dp.toPx()
        drawLine(tint, p(4f, 7f), p(20f, 7f), strokeWidth, cap = StrokeCap.Round)
        drawLine(tint, p(10f, 11f), p(10f, 17f), strokeWidth, cap = StrokeCap.Round)
        drawLine(tint, p(14f, 11f), p(14f, 17f), strokeWidth, cap = StrokeCap.Round)
        val bin = Path().apply {
            moveTo(6f * s, 7f * s)
            lineTo(7f * s, 20f * s)
            quadraticTo(7f * s, 22f * s, 9f * s, 22f * s)
            lineTo(15f * s, 22f * s)
            quadraticTo(17f * s, 22f * s, 17f * s, 20f * s)
            lineTo(18f * s, 7f * s)
        }
        drawPath(bin, tint, style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
        val lid = Path().apply {
            moveTo(9f * s, 7f * s)
            lineTo(9f * s, 4f * s)
            quadraticTo(9f * s, 3f * s, 10f * s, 3f * s)
            lineTo(14f * s, 3f * s)
            quadraticTo(15f * s, 3f * s, 15f * s, 4f * s)
            lineTo(15f * s, 7f * s)
        }
        drawPath(lid, tint, style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
private fun HeartGlyph(tint: Color, modifier: Modifier = Modifier.size(22.dp)) {
    Canvas(modifier = modifier) {
        val strokeWidth = 1.75.dp.toPx()
        val cx = size.width / 2f
        val topY = size.height * 0.32f
        val w = size.width * 0.42f
        val path = Path().apply {
            moveTo(cx, size.height * 0.82f)
            cubicTo(cx - w, size.height * 0.55f, cx - w, topY, cx - w * 0.42f, topY)
            cubicTo(cx - w * 0.1f, topY, cx, topY + size.height * 0.12f, cx, topY + size.height * 0.2f)
            cubicTo(cx, topY + size.height * 0.12f, cx + w * 0.1f, topY, cx + w * 0.42f, topY)
            cubicTo(cx + w, topY, cx + w, size.height * 0.55f, cx, size.height * 0.82f)
            close()
        }
        drawPath(path, tint, style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
private fun SunGlyph(tint: Color, modifier: Modifier = Modifier.size(16.dp)) {
    Canvas(modifier = modifier) {
        val strokeWidth = 1.6.dp.toPx()
        val radius = size.minDimension * 0.22f
        drawCircle(tint, radius = radius, center = center, style = Stroke(width = strokeWidth))
        val rayInner = radius + size.minDimension * 0.08f
        val rayOuter = rayInner + size.minDimension * 0.14f
        for (i in 0 until 8) {
            val angle = Math.toRadians((i * 45).toDouble())
            val cosA = cos(angle).toFloat()
            val sinA = sin(angle).toFloat()
            drawLine(
                tint,
                Offset(center.x + rayInner * cosA, center.y + rayInner * sinA),
                Offset(center.x + rayOuter * cosA, center.y + rayOuter * sinA),
                strokeWidth,
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun MoonGlyph(tint: Color, modifier: Modifier = Modifier.size(16.dp)) {
    Canvas(modifier = modifier) {
        val radius = size.minDimension * 0.32f
        val fullCircle = Path().apply { addOval(Rect(center = center, radius = radius)) }
        val cutout = Path().apply {
            addOval(Rect(center = Offset(center.x + radius * 0.55f, center.y - radius * 0.4f), radius = radius * 0.82f))
        }
        val crescent = Path().apply { op(fullCircle, cutout, PathOperation.Difference) }
        drawPath(crescent, tint)
    }
}

@Composable
private fun AutoGlyph(tint: Color, modifier: Modifier = Modifier.size(16.dp)) {
    Canvas(modifier = modifier) {
        val radius = size.minDimension * 0.32f
        val strokeWidth = 1.6.dp.toPx()
        drawCircle(tint, radius = radius, center = center, style = Stroke(width = strokeWidth))
        val halfDisc = Path().apply {
            addArc(Rect(center = center, radius = radius), startAngleDegrees = 90f, sweepAngleDegrees = 180f)
            close()
        }
        drawPath(halfDisc, tint)
    }
}

@Composable
private fun ThemeModeSelector(selected: ThemeMode, onSelect: (ThemeMode) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ThemeModeOption(label = "System", mode = ThemeMode.SYSTEM, selected = selected, onSelect = onSelect, modifier = Modifier.weight(1f)) { AutoGlyph(tint = it) }
        ThemeModeOption(label = "Light", mode = ThemeMode.LIGHT, selected = selected, onSelect = onSelect, modifier = Modifier.weight(1f)) { SunGlyph(tint = it) }
        ThemeModeOption(label = "Dark", mode = ThemeMode.DARK, selected = selected, onSelect = onSelect, modifier = Modifier.weight(1f)) { MoonGlyph(tint = it) }
    }
}

@Composable
private fun ThemeModeOption(
    label: String,
    mode: ThemeMode,
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable (Color) -> Unit,
) {
    val isSelected = selected == mode
    val tint = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(11.dp))
            .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent)
            .clickable { onSelect(mode) }
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        icon(tint)
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = tint)
    }
}

// ---- Side menu: profile edit + session history table ----

@Composable
private fun AppDrawerContent(
    zoneSettings: ZoneSettings,
    repository: SessionRepository,
    onEditProfile: () -> Unit,
    onSessionClick: (SessionEntity) -> Unit,
    onClose: () -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
) {
    val summaries by repository.sessionSummaries.collectAsStateWithLifecycle(initialValue = emptyList())

    ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.width(320.dp),
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 22.dp, vertical = 16.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { HeartGlyph(tint = MaterialTheme.colorScheme.primary) }
                    SquareIconButton(onClick = onClose) { CloseGlyph(tint = MaterialTheme.colorScheme.onSurface) }
                }

                Spacer(Modifier.height(18.dp))
                Text("Your Profile", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(14.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp))
                        .padding(horizontal = 16.dp),
                ) {
                    ProfileRow(label = "AGE", value = zoneSettings.age.toString(), onEdit = onEditProfile)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    ProfileRow(label = "RESTING HR", value = "${zoneSettings.restingHr} bpm", onEdit = onEditProfile)
                }

                Spacer(Modifier.height(20.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(16.dp))

                Text("Appearance", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                ThemeModeSelector(selected = themeMode, onSelect = onThemeModeChange)

                Spacer(Modifier.height(20.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(16.dp))

                Text("Session History", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(
                    text = if (summaries.isEmpty()) "No sessions yet" else "${summaries.size} sessions logged",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f),
                )
                Spacer(Modifier.height(10.dp))

                if (summaries.isEmpty()) {
                    Text(
                        "Sessions you save will show up here as a table.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                } else {
                    SessionTableHeader()
                }
            }

            items(summaries, key = { it.session.id }) { summary ->
                SessionTableRow(summary = summary, zoneSettings = zoneSettings, onClick = { onSessionClick(summary.session) })
            }

            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun ProfileRow(label: String, value: String, onEdit: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f))
            Spacer(Modifier.height(3.dp))
            Text(value, fontFamily = SpaceGroteskFamily, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                .clickable(onClick = onEdit),
            contentAlignment = Alignment.Center,
        ) { PencilGlyph(tint = MaterialTheme.colorScheme.primary) }
    }
}

@Composable
private fun SessionTableHeader() {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text("DATE", modifier = Modifier.weight(1.5f), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
        Text("DURATION", modifier = Modifier.weight(1.1f), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
        Text("AVG", modifier = Modifier.weight(0.7f), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
        Text("ZONE", modifier = Modifier.weight(0.65f), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
    }
}

@Composable
private fun SessionTableRow(summary: SessionSummary, zoneSettings: ZoneSettings, onClick: () -> Unit) {
    val dateFormat = remember { SimpleDateFormat("MMM d", Locale.getDefault()) }
    val timeFormat = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    val avgBpm = summary.stats?.avgBpm?.roundToInt()
    val avgZone = avgBpm?.let { HeartRateZones.computeZone(it, zoneSettings.age, zoneSettings.restingHr) }
    val zoneIndex = zoneSegmentIndex(avgZone)

    Column {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1.5f)) {
                Text(dateFormat.format(Date(summary.session.startedAtMs)), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(timeFormat.format(Date(summary.session.startedAtMs)), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f))
            }
            Text(
                formatDuration(summary.session.endedAtMs - summary.session.startedAtMs),
                modifier = Modifier.weight(1.1f),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
            )
            Text(
                avgBpm?.toString() ?: "--",
                modifier = Modifier.weight(0.7f),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
            )
            Box(Modifier.weight(0.65f)) {
                if (zoneIndex >= 0) {
                    ZoneChip(index = zoneIndex, label = "Z${zoneIndex + 1}")
                } else {
                    Text("--", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun DevicePickerDialog(devices: List<BluetoothDevice>, onSelect: (BluetoothDevice) -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Choose a heart rate device") },
        text = {
            Column {
                Text(
                    "More than one nearby device is broadcasting heart rate data. Pick one — " +
                        "future sessions will reconnect to it automatically.",
                )
                Spacer(Modifier.height(12.dp))
                devices.forEach { device ->
                    Text(
                        text = device.name ?: device.address,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(device) }
                            .padding(vertical = 12.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun EditProfileDialog(current: ZoneSettings, onDismiss: () -> Unit, onSave: (ZoneSettings) -> Unit) {
    var ageText by remember { mutableStateOf(current.age.toString()) }
    var restingHrText by remember { mutableStateOf(current.restingHr.toString()) }
    val age = ageText.toIntOrNull()
    val restingHr = restingHrText.toIntOrNull()
    val isValid = age != null && age in 1..120 && restingHr != null && restingHr in 30..120

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit profile") },
        text = {
            Column {
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
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(ZoneSettings(age!!, restingHr!!)) }, enabled = isValid) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---- Session detail: full graph + time-in-zone ----

@Composable
private fun SessionDetailScreen(
    session: SessionEntity,
    repository: SessionRepository,
    zoneSettings: ZoneSettings,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var samples by remember(session.id) { mutableStateOf<List<SampleEntity>?>(null) }
    var stats by remember(session.id) { mutableStateOf<SessionStats?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(session.id) {
        stats = repository.statsForSession(session.id)
        samples = repository.samplesForSession(session.id)
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SquareIconButton(onClick = onBack) { ChevronLeftGlyph(tint = MaterialTheme.colorScheme.onSurface) }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(session.title.ifBlank { "Session" }, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(formatSessionSubtitle(session), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f))
            }
            SquareIconButton(onClick = { scope.launch { shareCsv(context, repository.exportCsv(session)) } }) {
                DownloadGlyph(tint = MaterialTheme.colorScheme.onSurface)
            }
        }

        val currentSamples = samples
        val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        when {
            currentSamples == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Loading…", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            }
            currentSamples.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No samples recorded for this session.", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            }
            else -> {
                Crossfade(
                    targetState = isLandscape,
                    modifier = Modifier.weight(1f),
                    animationSpec = tween(300),
                    label = "sessionDetailBodyOrientation",
                ) { landscape ->
                    if (landscape) {
                        SessionDetailLandscapeBody(currentSamples, stats, session, zoneSettings)
                    } else {
                        SessionDetailPortraitBody(currentSamples, stats, session, zoneSettings)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(999.dp))
                            .clickable { scope.launch { shareCsv(context, repository.exportCsv(session)) } },
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DownloadGlyph(tint = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.width(8.dp))
                        Text("Export CSV", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .border(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f), RoundedCornerShape(999.dp))
                            .clickable { showDeleteConfirm = true },
                        contentAlignment = Alignment.Center,
                    ) { TrashGlyph(tint = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete this session?") },
            text = { Text("This permanently removes the recorded samples. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    scope.launch {
                        repository.deleteSession(session)
                        onBack()
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SessionDetailStatsRow(stats: SessionStats?, session: SessionEntity, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatTile("AVG", stats?.avgBpm?.roundToInt()?.toString() ?: "--", Modifier.weight(1f))
        StatTile("MAX", stats?.maxBpm?.toString() ?: "--", Modifier.weight(1f), color = zoneChipTextColor(3))
        StatTile("MIN", stats?.minBpm?.toString() ?: "--", Modifier.weight(1f), color = zoneChipTextColor(0))
        StatTile("DURATION", formatDuration(session.endedAtMs - session.startedAtMs), Modifier.weight(1f))
    }
}

@Composable
private fun SessionDetailChartCard(currentSamples: List<SampleEntity>, session: SessionEntity, zoneSettings: ZoneSettings, modifier: Modifier = Modifier) {
    val zoneBoundaries = remember(zoneSettings) {
        HeartRateZones.zoneBandBoundariesBpm(zoneSettings.age, zoneSettings.restingHr)
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(24.dp))
            .padding(18.dp),
    ) {
        Text("HEART RATE", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f))
        Spacer(Modifier.height(10.dp))
        InteractiveBpmChart(
            samples = currentSamples.map { HeartRateSample(it.bpm, it.timestampMs) },
            lineColor = MaterialTheme.colorScheme.primary,
            zoneSettings = zoneSettings,
            highlightLatest = false,
            zoneBoundariesBpm = zoneBoundaries,
            modifier = Modifier.fillMaxWidth().height(190.dp),
        )
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(timeLabel(session.startedAtMs), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f))
            Text(timeLabel(session.endedAtMs), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f))
        }
    }
}

@Composable
private fun SessionDetailPortraitBody(currentSamples: List<SampleEntity>, stats: SessionStats?, session: SessionEntity, zoneSettings: ZoneSettings) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SessionDetailStatsRow(stats, session, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp))

        Spacer(Modifier.height(16.dp))

        SessionDetailChartCard(currentSamples, session, zoneSettings, modifier = Modifier.padding(horizontal = 20.dp))

        Spacer(Modifier.height(14.dp))

        val breakdown = remember(currentSamples, zoneSettings) { zoneBreakdownMs(currentSamples, zoneSettings) }
        ZoneBreakdownCard(breakdown, modifier = Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun SessionDetailLandscapeBody(currentSamples: List<SampleEntity>, stats: SessionStats?, session: SessionEntity, zoneSettings: ZoneSettings) {
    Row(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Column(Modifier.weight(0.5f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
            SessionDetailChartCard(currentSamples, session, zoneSettings)
        }

        Spacer(Modifier.width(20.dp))

        Column(Modifier.weight(0.5f).fillMaxHeight().verticalScroll(rememberScrollState())) {
            SessionDetailStatsRow(stats, session, modifier = Modifier.fillMaxWidth())

            Spacer(Modifier.height(16.dp))

            val breakdown = remember(currentSamples, zoneSettings) { zoneBreakdownMs(currentSamples, zoneSettings) }
            ZoneBreakdownCard(breakdown)
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f))
        Spacer(Modifier.height(4.dp))
        Text(
            value,
            fontFamily = SpaceGroteskFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 19.sp,
            color = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurface else color,
        )
    }
}

/** Bucket time between consecutive samples into the zone of the later sample; gaps (e.g. a reconnect) are capped at [STALE_AFTER_MS] so they don't skew one zone. */
private fun zoneBreakdownMs(samples: List<SampleEntity>, settings: ZoneSettings): LongArray {
    val totals = LongArray(5)
    for (index in 1 until samples.size) {
        val previous = samples[index - 1]
        val current = samples[index]
        val delta = (current.timestampMs - previous.timestampMs).coerceIn(0L, STALE_AFTER_MS)
        if (delta <= 0L) continue
        val zone = HeartRateZones.computeZone(current.bpm, settings.age, settings.restingHr)
        totals[zoneSegmentIndex(zone).coerceAtLeast(0)] += delta
    }
    return totals
}

@Composable
private fun ZoneBreakdownCard(breakdownMs: LongArray, modifier: Modifier = Modifier) {
    val total = breakdownMs.sum().coerceAtLeast(1L)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(24.dp))
            .padding(18.dp),
    ) {
        Text("TIME IN ZONE", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f))
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().height(14.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            breakdownMs.forEachIndexed { index, ms ->
                val segmentWeight = (ms.toFloat() / total).coerceAtLeast(0.002f)
                Box(
                    modifier = Modifier
                        .weight(segmentWeight)
                        .fillMaxHeight()
                        .clip(
                            RoundedCornerShape(
                                topStart = if (index == 0) 7.dp else 0.dp,
                                bottomStart = if (index == 0) 7.dp else 0.dp,
                                topEnd = if (index == breakdownMs.lastIndex) 7.dp else 0.dp,
                                bottomEnd = if (index == breakdownMs.lastIndex) 7.dp else 0.dp,
                            ),
                        )
                        .background(ZONE_COLORS[index]),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            breakdownMs.forEachIndexed { index, ms ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(ZONE_COLORS[index]))
                    Spacer(Modifier.width(6.dp))
                    Text("Z${index + 1} · ${formatDuration(ms)}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f))
                }
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}

private fun timeLabel(ms: Long): String = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ms))

private fun timeLabelWithSeconds(ms: Long): String = SimpleDateFormat("h:mm:ss a", Locale.getDefault()).format(Date(ms))

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
