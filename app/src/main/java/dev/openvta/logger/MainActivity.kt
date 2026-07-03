package dev.openvta.logger

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.compose.foundation.clickable
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.common.HybridBinarizer
import androidx.core.content.FileProvider
import dev.openvta.logger.domain.AppSettings
import dev.openvta.logger.domain.GpsSample
import dev.openvta.logger.domain.ImuEnhancementPreset
import dev.openvta.logger.domain.ImuEnhancementPresets
import dev.openvta.logger.domain.RecordingSession
import dev.openvta.logger.domain.SessionVisualization
import dev.openvta.logger.domain.UploadState
import dev.openvta.logger.domain.VtaLogParser
import dev.openvta.logger.domain.VtaTraceEnhancer
import dev.openvta.logger.live.LiveRegistrationClient
import dev.openvta.logger.live.LiveRegistrationQrPayload
import dev.openvta.logger.recording.RecordingForegroundService
import dev.openvta.logger.ui.VisualizationCard
import dev.openvta.logger.upload.UploadWorker
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleAutomationIntent(intent)
        val app = application as OpenVtaLoggerApp
        setContent {
            var appSettings by remember { mutableStateOf(app.container.settingsRepository.load()) }
            MaterialTheme(
                colorScheme = if (appSettings.darkMode) darkColorScheme() else lightColorScheme(),
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    OpenVtaLoggerAppScreen(
                        settings = appSettings,
                        onSettingsChange = { appSettings = it },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAutomationIntent(intent)
    }

    private fun handleAutomationIntent(intent: Intent?) {
        if (!BuildConfig.DEBUG || intent == null) return
        if (intent.getBooleanExtra(EXTRA_AUTOMATION_APPLY_SETTINGS, false)) {
            applyAutomationSettings(intent)
        }
        if (intent.getBooleanExtra(EXTRA_AUTOMATION_RETRY_LIVE_UPSTREAM, false)) {
            val app = application as OpenVtaLoggerApp
            app.container.liveUpstreamManager.retryPending()
        }
        when {
            intent.getBooleanExtra(EXTRA_AUTOMATION_START, false) -> {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    ContextCompat.startForegroundService(this, RecordingForegroundService.startIntent(this))
                }
            }
            intent.getBooleanExtra(EXTRA_AUTOMATION_STOP, false) -> {
                startService(RecordingForegroundService.stopIntent(this))
            }
        }
    }

    private fun applyAutomationSettings(intent: Intent) {
        val repository = (application as OpenVtaLoggerApp).container.settingsRepository
        val current = repository.load()
        val updated = current.copy(
            driverId = intent.getStringExtra(EXTRA_AUTOMATION_DRIVER_ID) ?: current.driverId,
            ftpHost = intent.getStringExtra(EXTRA_AUTOMATION_FTP_HOST) ?: current.ftpHost,
            ftpPort = if (intent.hasExtra(EXTRA_AUTOMATION_FTP_PORT)) {
                intent.getIntExtra(EXTRA_AUTOMATION_FTP_PORT, current.ftpPort).coerceIn(1, 65535)
            } else {
                current.ftpPort
            },
            ftpUser = intent.getStringExtra(EXTRA_AUTOMATION_FTP_USER) ?: current.ftpUser,
            ftpPassword = intent.getStringExtra(EXTRA_AUTOMATION_FTP_PASSWORD) ?: current.ftpPassword,
            passiveMode = if (intent.hasExtra(EXTRA_AUTOMATION_PASSIVE_MODE)) {
                intent.getBooleanExtra(EXTRA_AUTOMATION_PASSIVE_MODE, current.passiveMode)
            } else {
                current.passiveMode
            },
            keepLocalFiles = if (intent.hasExtra(EXTRA_AUTOMATION_KEEP_LOCAL_FILES)) {
                intent.getBooleanExtra(EXTRA_AUTOMATION_KEEP_LOCAL_FILES, current.keepLocalFiles)
            } else {
                current.keepLocalFiles
            },
            darkMode = if (intent.hasExtra(EXTRA_AUTOMATION_DARK_MODE)) {
                intent.getBooleanExtra(EXTRA_AUTOMATION_DARK_MODE, current.darkMode)
            } else {
                current.darkMode
            },
            imuPresetId = intent.getStringExtra(EXTRA_AUTOMATION_IMU_PRESET_ID)?.let {
                ImuEnhancementPresets.find(it).id
            } ?: current.imuPresetId,
            liveEnabled = if (intent.hasExtra(EXTRA_AUTOMATION_LIVE_ENABLED)) {
                intent.getBooleanExtra(EXTRA_AUTOMATION_LIVE_ENABLED, current.liveEnabled)
            } else {
                current.liveEnabled
            },
            liveBaseUrl = intent.getStringExtra(EXTRA_AUTOMATION_LIVE_BASE_URL) ?: current.liveBaseUrl,
            liveTenantId = intent.getStringExtra(EXTRA_AUTOMATION_LIVE_TENANT_ID) ?: current.liveTenantId,
            liveDeviceId = intent.getStringExtra(EXTRA_AUTOMATION_LIVE_DEVICE_ID) ?: current.liveDeviceId,
            liveMqttCredential = intent.getStringExtra(EXTRA_AUTOMATION_LIVE_MQTT_CREDENTIAL) ?: current.liveMqttCredential,
            liveWssCredential = intent.getStringExtra(EXTRA_AUTOMATION_LIVE_WSS_CREDENTIAL) ?: current.liveWssCredential,
            liveApiCredential = intent.getStringExtra(EXTRA_AUTOMATION_LIVE_API_CREDENTIAL) ?: current.liveApiCredential,
        )
        repository.save(updated)
        val app = application as OpenVtaLoggerApp
        app.container.liveUpstreamManager.refreshCommandConnection()
        app.container.updateStatus {
            it.copy(lastMessage = "Automation settings applied")
        }
    }

    companion object {
        const val EXTRA_AUTOMATION_APPLY_SETTINGS = "debugApplySettings"
        const val EXTRA_AUTOMATION_START = "debugStartRecording"
        const val EXTRA_AUTOMATION_STOP = "debugStopRecording"
        const val EXTRA_AUTOMATION_RETRY_LIVE_UPSTREAM = "debugRetryLiveUpstream"
        const val EXTRA_AUTOMATION_DRIVER_ID = "debugDriverId"
        const val EXTRA_AUTOMATION_FTP_HOST = "debugFtpHost"
        const val EXTRA_AUTOMATION_FTP_PORT = "debugFtpPort"
        const val EXTRA_AUTOMATION_FTP_USER = "debugFtpUser"
        const val EXTRA_AUTOMATION_FTP_PASSWORD = "debugFtpPassword"
        const val EXTRA_AUTOMATION_PASSIVE_MODE = "debugPassiveMode"
        const val EXTRA_AUTOMATION_KEEP_LOCAL_FILES = "debugKeepLocalFiles"
        const val EXTRA_AUTOMATION_DARK_MODE = "debugDarkMode"
        const val EXTRA_AUTOMATION_IMU_PRESET_ID = "debugImuPresetId"
        const val EXTRA_AUTOMATION_LIVE_ENABLED = "debugLiveEnabled"
        const val EXTRA_AUTOMATION_LIVE_BASE_URL = "debugLiveBaseUrl"
        const val EXTRA_AUTOMATION_LIVE_TENANT_ID = "debugLiveTenantId"
        const val EXTRA_AUTOMATION_LIVE_DEVICE_ID = "debugLiveDeviceId"
        const val EXTRA_AUTOMATION_LIVE_MQTT_CREDENTIAL = "debugLiveMqttCredential"
        const val EXTRA_AUTOMATION_LIVE_WSS_CREDENTIAL = "debugLiveWssCredential"
        const val EXTRA_AUTOMATION_LIVE_API_CREDENTIAL = "debugLiveApiCredential"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OpenVtaLoggerAppScreen(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as OpenVtaLoggerApp
    val status by app.container.status.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedTab by remember { mutableStateOf(AppTab.Dashboard) }
    var sessions by remember {
        mutableStateOf(app.container.recordingRepository.listSessions(), neverEqualPolicy())
    }
    var selectedVisualization by remember {
        mutableStateOf<SessionVisualization?>(null, neverEqualPolicy())
    }
    val coroutineScope = rememberCoroutineScope()
    var busySessionId by remember { mutableStateOf<String?>(null) }
    var liveRegistrationBusy by remember { mutableStateOf(false) }
    val liveRegistrationClient = remember { LiveRegistrationClient() }

    fun registerLivePayload(payload: LiveRegistrationQrPayload, sourceLabel: String) {
        if (!liveRegistrationBusy) {
            coroutineScope.launch {
                liveRegistrationBusy = true
                try {
                    val displayName = listOf(Build.MANUFACTURER, Build.MODEL)
                        .filter { it.isNotBlank() }
                        .joinToString(" ")
                        .ifBlank { "Android device" }
                    val result = withContext(Dispatchers.IO) {
                        liveRegistrationClient.consumeToken(payload.baseUrl, payload.token, displayName, BuildConfig.VERSION_NAME)
                    }
                    val updated = settings.copy(
                        liveEnabled = true,
                        liveBaseUrl = payload.baseUrl,
                        liveTenantId = result.tenantId,
                        liveDeviceId = result.deviceId,
                        liveMqttCredential = result.mqttCredential,
                        liveWssCredential = result.wssCredential,
                        liveApiCredential = result.apiCredential,
                    )
                    onSettingsChange(updated)
                    withContext(Dispatchers.IO) {
                        app.container.settingsRepository.save(updated)
                    }
                    app.container.liveUpstreamManager.refreshCommandConnection()
                    app.container.updateStatus { it.copy(lastMessage = "Live device registered") }
                } catch (exception: Exception) {
                    app.container.updateStatus { it.copy(lastMessage = "$sourceLabel registration failed: ${exception.message}") }
                } finally {
                    liveRegistrationBusy = false
                }
            }
        }
    }

    val registerLiveFromCode: (String, String) -> Unit = { baseUrl, token ->
        runCatching { LiveRegistrationQrPayload.fromManual(baseUrl, token) }
            .onSuccess { registerLivePayload(it, "Live code") }
            .onFailure { exception ->
                app.container.updateStatus { it.copy(lastMessage = "Live code registration failed: ${exception.message}") }
            }
    }

    val registerLiveFromQr: (String) -> Unit = { rawQr ->
        runCatching { LiveRegistrationQrPayload.parse(rawQr, fallbackBaseUrl = settings.liveBaseUrl) }
            .onSuccess { registerLivePayload(it, "Live QR") }
            .onFailure { exception ->
                app.container.updateStatus { it.copy(lastMessage = "Live QR registration failed: ${exception.message}") }
            }
    }

    val liveQrLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (contents.isNullOrBlank()) {
            app.container.updateStatus { it.copy(lastMessage = "Live QR scan cancelled") }
        } else {
            registerLiveFromQr(contents)
        }
    }
    val liveQrImageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) {
            app.container.updateStatus { it.copy(lastMessage = "Live QR image selection cancelled") }
        } else {
            coroutineScope.launch {
                runCatching {
                    withContext(Dispatchers.IO) { decodeQrTextFromImage(context, uri) }
                }
                    .onSuccess(registerLiveFromQr)
                    .onFailure { exception ->
                        app.container.updateStatus { it.copy(lastMessage = "Live QR image failed: ${exception.message}") }
                    }
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val locationGranted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (locationGranted) {
            ContextCompat.startForegroundService(context, RecordingForegroundService.startIntent(context))
            selectedTab = AppTab.Live
        } else {
            app.container.updateStatus {
                it.copy(lastMessage = "Location permission denied. Recording needs GPS access.")
            }
        }
    }

    LaunchedEffect(status.currentSession?.id, status.currentSession?.endedAtMillis) {
        sessions = withContext(Dispatchers.IO) {
            app.container.recordingRepository.listSessions()
        }
    }
    LaunchedEffect(status.lastMessage) {
        val message = status.lastMessage
        if (message.startsWith("Upload ") || message.startsWith("ZIP ")) {
            sessions = withContext(Dispatchers.IO) {
                app.container.recordingRepository.listSessions()
            }
        }
    }

    val startRecording = {
        app.container.settingsRepository.save(settings)
        val permissions = requiredPermissions()
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            ContextCompat.startForegroundService(context, RecordingForegroundService.startIntent(context))
            selectedTab = AppTab.Live
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }
    val stopRecording: () -> Unit = {
        context.startService(RecordingForegroundService.stopIntent(context))
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(selectedTab.title) }) },
        bottomBar = {
            AppNavigationBar(
                selectedTab = selectedTab,
                onTabSelected = { selectedTab = it },
            )
        },
        floatingActionButton = {
            RecordingFab(
                isRecording = status.isRecording,
                onStart = startRecording,
                onStop = stopRecording,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        val contentModifier = Modifier
            .padding(padding)
            .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp)
            .fillMaxSize()

        when (selectedTab) {
            AppTab.Dashboard -> DashboardScreen(
                modifier = contentModifier,
                isRecording = status.isRecording,
                imuPreset = ImuEnhancementPresets.find(settings.imuPresetId),
                gpsFixes = status.gpsFixCount,
                sensorSamples = status.sensorSampleCount,
                speedKmh = status.speedKmh,
                distanceMeters = status.distanceMeters,
                lastGps = status.lastGps,
                message = status.lastMessage,
                onStart = startRecording,
                onStop = stopRecording,
                onOpenLive = { selectedTab = AppTab.Live },
            )
            AppTab.Live -> LiveScreen(
                modifier = contentModifier,
                title = if (status.isRecording) "Live visualization" else "Live visualization idle",
                trace = status.liveTrace.copy(enhancementPresetId = status.currentSession?.imuPresetId ?: settings.imuPresetId),
                followLatest = status.isRecording,
            )
            AppTab.Sessions -> SessionsScreen(
                modifier = contentModifier,
                sessions = sessions,
                selectedVisualization = selectedVisualization,
                message = status.lastMessage,
                busySessionId = busySessionId,
                onView = { session ->
                    coroutineScope.launch {
                        busySessionId = session.id
                        try {
                            val trace = withContext(Dispatchers.IO) {
                                val parsed = VtaLogParser.parse(session.vtaFile)
                                if (parsed.enhancedGpsPoints.isEmpty()) {
                                    VtaTraceEnhancer.enhance(parsed, session.imuPresetId)
                                } else {
                                    parsed.copy(enhancementPresetId = session.imuPresetId)
                                }
                            }
                            selectedVisualization = SessionVisualization(session = session, trace = trace)
                            app.container.updateStatus { status -> status.copy(lastMessage = "Loaded session: ${session.id}") }
                        } catch (exception: Exception) {
                            app.container.updateStatus { status -> status.copy(lastMessage = "View failed: ${exception.message}") }
                        } finally {
                            busySessionId = null
                        }
                    }
                },
                onCloseVisualization = { selectedVisualization = null },
                onZip = { session ->
                    coroutineScope.launch {
                        busySessionId = session.id
                        try {
                            val zipFile = withContext(Dispatchers.IO) {
                                app.container.recordingRepository.zipSession(session)
                            }
                            sessions = withContext(Dispatchers.IO) {
                                app.container.recordingRepository.listSessions()
                            }
                            app.container.updateStatus { status -> status.copy(lastMessage = "ZIP ready: ${zipFile.name}") }
                        } catch (exception: Exception) {
                            app.container.updateStatus { status -> status.copy(lastMessage = "ZIP failed: ${exception.message}") }
                        } finally {
                            busySessionId = null
                        }
                    }
                },
                onUpload = { session ->
                    coroutineScope.launch {
                        busySessionId = session.id
                        try {
                            val savedSettings = withContext(Dispatchers.IO) {
                                app.container.settingsRepository.load()
                            }
                            val validationError = savedSettings.uploadValidationError()
                            if (validationError != null) {
                                app.container.updateStatus { status -> status.copy(lastMessage = validationError) }
                                return@launch
                            }
                            withContext(Dispatchers.IO) {
                                if (!session.zipFile.isFile) app.container.recordingRepository.zipSession(session)
                                app.container.recordingRepository.updateUploadState(session.id, UploadState.Queued)
                            }
                            UploadWorker.enqueue(context, session.id)
                            sessions = withContext(Dispatchers.IO) {
                                app.container.recordingRepository.listSessions()
                            }
                            app.container.updateStatus { status -> status.copy(lastMessage = "Upload queued: ${session.id}") }
                        } catch (exception: Exception) {
                            app.container.updateStatus { status -> status.copy(lastMessage = "Upload queue failed: ${exception.message}") }
                        } finally {
                            busySessionId = null
                        }
                    }
                },
                onShareVta = { session -> shareFile(context, session.vtaFile) },
                onShareZip = { session -> shareFile(context, session.zipFile) },
            )
            AppTab.Settings -> SettingsScreen(
                modifier = contentModifier,
                settings = settings,
                message = status.lastMessage,
                liveRegistrationBusy = liveRegistrationBusy,
                onSettingsChange = onSettingsChange,
                onScanLiveQr = {
                    liveQrLauncher.launch(
                        ScanOptions()
                            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            .setPrompt("Scan OpenVTA Live QR")
                            .setBeepEnabled(false)
                            .setOrientationLocked(false),
                    )
                },
                onSelectLiveQrImage = {
                    liveQrImageLauncher.launch("image/*")
                },
                onRegisterLiveCode = registerLiveFromCode,
                onSave = {
                    app.container.settingsRepository.save(settings)
                    app.container.liveUpstreamManager.refreshCommandConnection()
                    app.container.updateStatus { it.copy(lastMessage = "Settings saved") }
                },
            )
        }
    }
}

@Composable
private fun RecordingFab(
    isRecording: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val label = if (isRecording) "Stop recording" else "Start recording"
    FloatingActionButton(
        modifier = Modifier.semantics { contentDescription = label },
        onClick = if (isRecording) onStop else onStart,
    ) {
        Icon(
            if (isRecording) Icons.Default.Stop else Icons.Default.PlayArrow,
            contentDescription = null,
        )
    }
}

private enum class AppTab(val title: String) {
    Dashboard("Dashboard"),
    Live("Live"),
    Sessions("Sessions"),
    Settings("Settings"),
}

@Composable
private fun AppNavigationBar(
    selectedTab: AppTab,
    onTabSelected: (AppTab) -> Unit,
) {
    NavigationBar {
        AppTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = selectedTab == tab,
                onClick = { onTabSelected(tab) },
                icon = {
                    when (tab) {
                        AppTab.Dashboard -> Icon(Icons.Default.Home, contentDescription = null)
                        AppTab.Live -> Icon(Icons.Default.Map, contentDescription = null)
                        AppTab.Sessions -> Icon(Icons.Default.Folder, contentDescription = null)
                        AppTab.Settings -> Icon(Icons.Default.Settings, contentDescription = null)
                    }
                },
                label = { Text(tab.title) },
            )
        }
    }
}

@Composable
private fun DashboardScreen(
    modifier: Modifier,
    isRecording: Boolean,
    imuPreset: ImuEnhancementPreset,
    gpsFixes: Long,
    sensorSamples: Long,
    speedKmh: Double,
    distanceMeters: Double,
    lastGps: GpsSample?,
    message: String,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenLive: () -> Unit,
) {
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            DashboardCard(
                isRecording = isRecording,
                imuPreset = imuPreset,
                gpsFixes = gpsFixes,
                sensorSamples = sensorSamples,
                speedKmh = speedKmh,
                distanceMeters = distanceMeters,
                lastGps = lastGps,
                message = message,
                onStart = onStart,
                onStop = onStop,
                onOpenLive = onOpenLive,
            )
        }
    }
}

@Composable
private fun LiveScreen(
    modifier: Modifier,
    title: String,
    trace: dev.openvta.logger.domain.VtaTrace,
    followLatest: Boolean,
) {
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            VisualizationCard(
                title = title,
                trace = trace,
                followLatest = followLatest,
            )
        }
    }
}

@Composable
private fun SessionsScreen(
    modifier: Modifier,
    sessions: List<RecordingSession>,
    selectedVisualization: SessionVisualization?,
    message: String,
    busySessionId: String?,
    onView: (RecordingSession) -> Unit,
    onCloseVisualization: () -> Unit,
    onZip: (RecordingSession) -> Unit,
    onUpload: (RecordingSession) -> Unit,
    onShareVta: (RecordingSession) -> Unit,
    onShareZip: (RecordingSession) -> Unit,
) {
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Recent sessions", style = MaterialTheme.typography.titleMedium)
        }
        if (message.isNotBlank()) {
            item {
                Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (sessions.isEmpty()) {
            item {
                Text("No recorded sessions yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(sessions, key = { it.id }) { session ->
            SessionCard(
                session = session,
                onView = { onView(session) },
                onZip = { onZip(session) },
                onUpload = { onUpload(session) },
                onShareVta = { onShareVta(session) },
                onShareZip = { onShareZip(session) },
                isBusy = busySessionId == session.id,
            )
        }
        selectedVisualization?.let { visualization ->
            item {
                StoredVisualizationCard(
                    visualization = visualization,
                    onClose = onCloseVisualization,
                )
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    modifier: Modifier,
    settings: AppSettings,
    message: String,
    liveRegistrationBusy: Boolean,
    onSettingsChange: (AppSettings) -> Unit,
    onScanLiveQr: () -> Unit,
    onSelectLiveQrImage: () -> Unit,
    onRegisterLiveCode: (String, String) -> Unit,
    onSave: () -> Unit,
) {
    var selectedSection by remember { mutableStateOf(SettingsSectionTab.General) }
    LazyColumn(
        modifier = modifier.testTag("settings-list"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SettingsHeaderCard(
                selectedSection = selectedSection,
                message = message,
                onSectionSelected = { selectedSection = it },
                onSave = onSave,
            )
        }
        item {
            when (selectedSection) {
                SettingsSectionTab.General -> GeneralSettingsCard(
                    settings = settings,
                    onSettingsChange = onSettingsChange,
                )
                SettingsSectionTab.Live -> LiveSettingsCard(
                    settings = settings,
                    liveRegistrationBusy = liveRegistrationBusy,
                    onSettingsChange = onSettingsChange,
                    onScanLiveQr = onScanLiveQr,
                    onSelectLiveQrImage = onSelectLiveQrImage,
                    onRegisterLiveCode = onRegisterLiveCode,
                )
                SettingsSectionTab.Ftp -> FtpSettingsCard(
                    settings = settings,
                    onSettingsChange = onSettingsChange,
                )
            }
        }
    }
}

private enum class SettingsSectionTab(val label: String) {
    General("General"),
    Live("Live"),
    Ftp("FTP"),
}

@Composable
private fun DashboardCard(
    isRecording: Boolean,
    imuPreset: ImuEnhancementPreset,
    gpsFixes: Long,
    sensorSamples: Long,
    speedKmh: Double,
    distanceMeters: Double,
    lastGps: GpsSample?,
    message: String,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenLive: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (isRecording) "Recording" else "Ready", style = MaterialTheme.typography.headlineSmall)
            Text(message)
            Text(
                "IMU preset: ${imuPreset.displayName} (${imuPreset.outputHz}Hz display)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("GPS fixes: $gpsFixes")
                Text("Sensor: $sensorSamples")
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Speed: ${"%.1f".format(speedKmh)} km/h")
                Text("Distance: ${"%.2f".format(distanceMeters / 1000.0)} km")
            }
            lastGps?.let { gps ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Altitude: ${formatDashboardMeters(gps.altitudeMeters)}")
                    Text("Accuracy: ${gps.accuracyMeters?.toDouble()?.let(::formatDashboardMeters) ?: "--"}")
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Bearing: ${String.format(Locale.US, "%.0f deg", gps.bearingDegrees)}")
                    Text("Provider: ${gps.provider}")
                }
                Text(
                    "Lat/Lon: ${String.format(Locale.US, "%.7f", gps.latitude)}, ${String.format(Locale.US, "%.7f", gps.longitude)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onStart, enabled = !isRecording) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Start")
                }
                Button(onClick = onStop, enabled = isRecording) {
                    Icon(Icons.Default.Stop, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Stop")
                }
                TextButton(onClick = onOpenLive) {
                    Icon(Icons.Default.Map, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Live")
                }
            }
        }
    }
}

private fun formatDashboardMeters(value: Double): String = String.format(Locale.US, "%.1f m", value)

@Composable
private fun SettingsHeaderCard(
    selectedSection: SettingsSectionTab,
    message: String,
    onSectionSelected: (SettingsSectionTab) -> Unit,
    onSave: () -> Unit,
) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Settings", style = MaterialTheme.typography.titleLarge)
                Button(onClick = onSave) {
                    Icon(Icons.Default.Save, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Save")
                }
            }
            if (message.isNotBlank()) {
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TabRow(selectedTabIndex = SettingsSectionTab.entries.indexOf(selectedSection)) {
                SettingsSectionTab.entries.forEach { section ->
                    Tab(
                        modifier = Modifier.testTag("settings-section-${section.label.lowercase(Locale.US)}"),
                        selected = selectedSection == section,
                        onClick = { onSectionSelected(section) },
                        text = { Text(section.label) },
                    )
                }
            }
        }
    }
}

@Composable
private fun GeneralSettingsCard(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("General", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSettingsChange(settings.copy(darkMode = !settings.darkMode)) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = settings.darkMode,
                    onCheckedChange = { onSettingsChange(settings.copy(darkMode = it)) },
                )
                Text("Dark mode")
            }
            OutlinedTextField(
                value = settings.driverId,
                onValueChange = { onSettingsChange(settings.copy(driverId = it)) },
                label = { Text("Driver ID") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            ImuPresetSelector(
                selectedPreset = ImuEnhancementPresets.find(settings.imuPresetId),
                onPresetSelected = { preset ->
                    onSettingsChange(settings.copy(imuPresetId = preset.id))
                },
            )
        }
    }
}

@Composable
private fun LiveSettingsCard(
    settings: AppSettings,
    liveRegistrationBusy: Boolean,
    onSettingsChange: (AppSettings) -> Unit,
    onScanLiveQr: () -> Unit,
    onSelectLiveQrImage: () -> Unit,
    onRegisterLiveCode: (String, String) -> Unit,
) {
    var manualLiveBaseUrl by remember(settings.liveBaseUrl) {
        mutableStateOf(settings.liveBaseUrl.ifBlank { "https://openvta-live.kro.kr" })
    }
    var manualRegistrationCode by remember { mutableStateOf("") }
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("OpenVTA Live", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSettingsChange(settings.copy(liveEnabled = !settings.liveEnabled)) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = settings.liveEnabled,
                    onCheckedChange = { onSettingsChange(settings.copy(liveEnabled = it)) },
                )
                Text("OpenVTA Live upstream")
            }
            Text(
                "Pair this device before any telemetry or VTA upload is sent upstream.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    modifier = Modifier.testTag("live-scan-qr-button"),
                    onClick = onScanLiveQr,
                    enabled = !liveRegistrationBusy,
                ) {
                    Icon(Icons.Default.CloudUpload, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (liveRegistrationBusy) "Registering" else "Scan QR")
                }
                Button(
                    modifier = Modifier.testTag("live-qr-image-button"),
                    onClick = onSelectLiveQrImage,
                    enabled = !liveRegistrationBusy,
                ) {
                    Icon(Icons.Default.Folder, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("QR image")
                }
            }
            OutlinedTextField(
                value = manualLiveBaseUrl,
                onValueChange = { manualLiveBaseUrl = it },
                label = { Text("Live server URL") },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("live-base-url-field"),
                singleLine = true,
            )
            OutlinedTextField(
                value = manualRegistrationCode,
                onValueChange = { manualRegistrationCode = it },
                label = { Text("6 digit pairing code") },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("live-registration-code-field"),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Button(
                modifier = Modifier.testTag("live-register-code-button"),
                onClick = { onRegisterLiveCode(manualLiveBaseUrl, manualRegistrationCode) },
                enabled = !liveRegistrationBusy && manualLiveBaseUrl.isNotBlank() && manualRegistrationCode.isNotBlank(),
            ) {
                Icon(Icons.Default.CloudUpload, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(if (liveRegistrationBusy) "Registering" else "Register with code")
            }
            Text("Connection", style = MaterialTheme.typography.labelLarge)
            Text(
                if (settings.liveDeviceId.isBlank()) {
                    "Not connected. Generate a device registration code in OpenVTA Live user web."
                } else {
                    "Connected to ${settings.liveBaseUrl} as ${settings.liveDeviceId.take(8)}..."
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FtpSettingsCard(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("FTP export", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = settings.ftpHost,
                onValueChange = { onSettingsChange(settings.copy(ftpHost = it)) },
                label = { Text("FTP host") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = settings.ftpPort.toString(),
                onValueChange = { onSettingsChange(settings.copy(ftpPort = it.toIntOrNull()?.coerceIn(1, 65535) ?: 21)) },
                label = { Text("FTP port") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = settings.ftpUser,
                onValueChange = { onSettingsChange(settings.copy(ftpUser = it)) },
                label = { Text("FTP user") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = settings.ftpPassword,
                onValueChange = { onSettingsChange(settings.copy(ftpPassword = it)) },
                label = { Text("FTP password") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = settings.passiveMode,
                    onCheckedChange = { onSettingsChange(settings.copy(passiveMode = it)) },
                )
                Text("Passive FTP")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = settings.keepLocalFiles,
                    onCheckedChange = { onSettingsChange(settings.copy(keepLocalFiles = it)) },
                )
                Text("Keep local files after upload")
            }
            Text(
                "FTP sends credentials and log files in cleartext. Use only on trusted networks.",
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun ImuPresetSelector(
    selectedPreset: ImuEnhancementPreset,
    onPresetSelected: (ImuEnhancementPreset) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("IMU/GPS enhancement preset", style = MaterialTheme.typography.titleSmall)
        Text(
            "${selectedPreset.outputHz}Hz display path - ${selectedPreset.description}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ImuEnhancementPresets.all.forEach { preset ->
            if (preset.id == selectedPreset.id) {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onPresetSelected(preset) },
                ) {
                    Text(preset.displayName)
                }
            } else {
                TextButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onPresetSelected(preset) },
                ) {
                    Text("${preset.displayName}: ${preset.description}")
                }
            }
        }
        Text(
            "Raw GPS rows stay unchanged as \$ records. Enhanced export rows are saved as @ records with source and confidence.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SessionCard(
    session: RecordingSession,
    onView: () -> Unit,
    onZip: () -> Unit,
    onUpload: () -> Unit,
    onShareVta: () -> Unit,
    onShareZip: () -> Unit,
    isBusy: Boolean,
) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(session.id, style = MaterialTheme.typography.titleMedium)
            Text("Driver: ${session.driverId}  Upload: ${session.uploadState}")
            Text("VTA: ${session.vtaFile.name} (${session.vtaFile.length()} bytes)")
            Text("ZIP: ${session.zipFile.name} (${if (session.zipFile.isFile) session.zipFile.length() else 0} bytes)")
            if (isBusy) {
                Text("Working...", color = MaterialTheme.colorScheme.primary)
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onView, enabled = session.vtaFile.isFile && !isBusy) {
                        Icon(Icons.Default.Visibility, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("View")
                    }
                    TextButton(onClick = onZip, enabled = session.vtaFile.isFile && !isBusy) {
                        Icon(Icons.Default.Archive, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Zip")
                    }
                    TextButton(onClick = onUpload, enabled = session.vtaFile.isFile && !isBusy) {
                        Icon(Icons.Default.CloudUpload, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Upload")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onShareVta, enabled = session.vtaFile.isFile && !isBusy) {
                        Icon(Icons.Default.Share, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Share VTA")
                    }
                    TextButton(onClick = onShareZip, enabled = session.zipFile.isFile && !isBusy) {
                        Icon(Icons.Default.Share, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Share ZIP")
                    }
                }
            }
        }
    }
}

@Composable
private fun StoredVisualizationCard(
    visualization: SessionVisualization,
    onClose: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Session view", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onClose) { Text("Close") }
        }
        VisualizationCard(
            title = visualization.session.id,
            trace = visualization.trace,
        )
    }
}

private fun requiredPermissions(): List<String> = buildList {
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}

private fun AppSettings.uploadValidationError(): String? = when {
    ftpHost.isBlank() -> "FTP host is not configured"
    ftpUser.isBlank() -> "FTP user is not configured"
    else -> null
}

private fun shareFile(context: Context, file: File) {
    if (!file.isFile) return
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val intent = Intent(Intent.ACTION_SEND)
        .setType("application/octet-stream")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, "Share ${file.name}"))
}

private fun decodeQrTextFromImage(context: Context, uri: Uri): String {
    val bitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
        BitmapFactory.decodeStream(stream)
    } ?: throw IllegalArgumentException("Unable to read QR image")
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
    val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
    return QRCodeReader().decode(binaryBitmap).text
}
