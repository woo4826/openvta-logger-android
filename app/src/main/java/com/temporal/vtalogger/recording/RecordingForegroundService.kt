package com.temporal.vtalogger.recording

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.temporal.vtalogger.MainActivity
import com.temporal.vtalogger.R
import com.temporal.vtalogger.VtaLoggerApp
import com.temporal.vtalogger.domain.DistanceCalculator
import com.temporal.vtalogger.domain.GpsSample
import com.temporal.vtalogger.domain.RecordingSession
import com.temporal.vtalogger.domain.SensorSample
import com.temporal.vtalogger.domain.SensorSnapshot
import kotlin.math.roundToInt

class RecordingForegroundService : Service(), SensorEventListener, LocationListener {
    private lateinit var app: VtaLoggerApp
    private lateinit var locationManager: LocationManager
    private lateinit var sensorManager: SensorManager

    @Volatile
    private var session: RecordingSession? = null
    @Volatile
    private var paused = false
    private var startedAtMillis = 0L
    private var sensorIndex = 0L
    private var gpsFixCount = 0L
    private var distanceMeters = 0.0
    private var lastLocation: Location? = null
    private var satelliteCount = 0
    private var sensorAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
    private var latestOrientation = floatArrayOf(0f, 0f, 0f)
    private var latestMagnetic = floatArrayOf(0f, 0f, 0f)
    private var wakeLock: PowerManager.WakeLock? = null
    private var locationThread: HandlerThread? = null
    private var locationHandler: Handler? = null
    private var sensorThread: HandlerThread? = null
    private var sensorHandler: Handler? = null
    private var lastSensorStatusUpdateMillis = 0L

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            satelliteCount = status.satelliteCount
        }
    }

    override fun onCreate() {
        super.onCreate()
        app = application as VtaLoggerApp
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        ensureNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_START) {
            ACTION_START -> startRecording()
            ACTION_PAUSE -> setPaused(true)
            ACTION_RESUME -> setPaused(false)
            ACTION_STOP -> stopRecording()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopSensorsAndLocation()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun startRecording() {
        if (session != null) return
        if (!hasLocationPermission()) {
            app.container.updateStatus { it.copy(lastMessage = "Location permission is required") }
            stopSelf()
            return
        }

        val settings = app.container.settingsRepository.load()
        startedAtMillis = System.currentTimeMillis()
        sensorIndex = 0L
        gpsFixCount = 0L
        distanceMeters = 0.0
        lastLocation = null
        lastSensorStatusUpdateMillis = 0L
        paused = false
        session = app.container.recordingRepository.createSession(settings.driverId, startedAtMillis)
        app.container.liveTraceStore.clear()

        acquireWakeLock()
        startForeground(NOTIFICATION_ID, buildNotification("Recording"))
        startSensorsAndLocation()
        app.container.updateStatus {
            it.copy(
                isRecording = true,
                isPaused = false,
                currentSession = session,
                gpsFixCount = 0,
                sensorSampleCount = 0,
                distanceMeters = 0.0,
                lastMessage = "Recording started",
                liveTrace = app.container.liveTraceStore.snapshot(),
            )
        }
    }

    private fun stopRecording() {
        val activeSession = session
        if (activeSession != null) {
            val closed = app.container.recordingRepository.closeSession(activeSession)
            app.container.updateStatus {
                it.copy(
                    isRecording = false,
                    isPaused = false,
                    currentSession = closed,
                    lastMessage = "Recording stopped: ${closed.vtaFile.name}",
                )
            }
        } else {
            app.container.updateStatus { it.copy(isRecording = false, isPaused = false, lastMessage = "Idle") }
        }
        session = null
        releaseWakeLock()
        stopSensorsAndLocation()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun setPaused(value: Boolean) {
        paused = value
        app.container.updateStatus { it.copy(isPaused = value, lastMessage = if (value) "Recording paused" else "Recording resumed") }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(if (value) "Paused" else "Recording"))
    }

    private fun startSensorsAndLocation() {
        locationThread = HandlerThread("VtaLocationThread").also {
            it.start()
            locationHandler = Handler(it.looper)
        }
        sensorThread = HandlerThread("VtaSensorThread").also {
            it.start()
            sensorHandler = Handler(it.looper)
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                0L,
                0f,
                this,
                locationHandler?.looper ?: Looper.getMainLooper(),
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                locationManager.registerGnssStatusCallback(gnssCallback, locationHandler)
            }
        }

        registerSensor(Sensor.TYPE_ACCELEROMETER)
        registerSensor(Sensor.TYPE_MAGNETIC_FIELD)
        @Suppress("DEPRECATION")
        registerSensor(Sensor.TYPE_ORIENTATION)
    }

    private fun registerSensor(type: Int) {
        sensorManager.getDefaultSensor(type)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, sensorHandler)
        }
    }

    private fun stopSensorsAndLocation() {
        runCatching { locationManager.removeUpdates(this) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { locationManager.unregisterGnssStatusCallback(gnssCallback) }
        }
        runCatching { sensorManager.unregisterListener(this) }
        locationHandler = null
        locationThread?.quitSafely()
        locationThread = null
        sensorHandler = null
        sensorThread?.quitSafely()
        sensorThread = null
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:recording",
        ).apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }

    override fun onLocationChanged(location: Location) {
        val activeSession = session ?: return
        gpsFixCount += 1
        lastLocation?.let {
            distanceMeters += DistanceCalculator.metersBetween(
                it.latitude,
                it.longitude,
                location.latitude,
                location.longitude,
            )
        }
        lastLocation = location

        val sample = GpsSample(
            timeMillis = location.time,
            latitude = location.latitude,
            longitude = location.longitude,
            altitudeMeters = location.altitude,
            speedMetersPerSecond = location.speed,
            bearingDegrees = location.bearing,
            satelliteCount = satelliteCount,
            accuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
            provider = location.provider ?: LocationManager.GPS_PROVIDER,
            elapsedRealtimeNanos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                location.elapsedRealtimeNanos
            } else {
                0L
            },
        )

        if (!paused) {
            app.container.recordingRepository.appendGps(activeSession, sample)
        }
        val liveTrace = app.container.liveTraceStore.appendGps(sample)
        app.container.updateStatus {
            it.copy(
                gpsFixCount = gpsFixCount,
                speedKmh = sample.speedMetersPerSecond * 3.6,
                distanceMeters = distanceMeters,
                lastGps = sample,
                lastMessage = "GPS ${sample.latitude.format(5)}, ${sample.longitude.format(5)}",
                liveTrace = liveTrace,
            )
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_MAGNETIC_FIELD -> latestMagnetic = event.values.copyOf(3)
            @Suppress("DEPRECATION")
            Sensor.TYPE_ORIENTATION -> latestOrientation = event.values.copyOf(3)
            Sensor.TYPE_ACCELEROMETER -> recordAccelerometer(event)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        sensorAccuracy = accuracy
    }

    @Deprecated("Deprecated by Android framework")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    override fun onProviderEnabled(provider: String) = Unit

    override fun onProviderDisabled(provider: String) {
        app.container.updateStatus { it.copy(lastMessage = "GPS provider disabled") }
    }

    private fun recordAccelerometer(event: SensorEvent) {
        val activeSession = session ?: return
        sensorIndex += 1
        val sample = SensorSample(
            index = sensorIndex,
            elapsedSeconds = (System.currentTimeMillis() - startedAtMillis) / 1000.0,
            snapshot = SensorSnapshot(
                orientation = latestOrientation.copyOf(3),
                magnetic = latestMagnetic.copyOf(3),
            ),
            accel = event.values.copyOf(3),
            sensorTimestampNanos = event.timestamp,
            sensorAccuracy = sensorAccuracy,
        )
        if (!paused) {
            app.container.recordingRepository.appendSensor(activeSession, sample)
        }
        app.container.liveTraceStore.appendSensor(sample)
        val now = SystemClock.elapsedRealtime()
        if (now - lastSensorStatusUpdateMillis >= SENSOR_STATUS_UPDATE_INTERVAL_MS) {
            lastSensorStatusUpdateMillis = now
            app.container.updateStatus {
                it.copy(sensorSampleCount = sensorIndex)
            }
        }
    }

    private fun buildNotification(content: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = serviceIntent(this, ACTION_STOP)
        val pauseIntent = serviceIntent(this, if (paused) ACTION_RESUME else ACTION_PAUSE)
        return NotificationCompat.Builder(this, RECORDING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("VTA Logger")
            .setContentText(content)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .addAction(0, if (paused) "Resume" else "Pause", pauseIntent)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    private fun ensureNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(
                    RECORDING_CHANNEL_ID,
                    getString(R.string.recording_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    UPLOAD_CHANNEL_ID,
                    getString(R.string.upload_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    private fun hasLocationPermission(): Boolean =
        ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun Double.format(decimals: Int): String = "%.${decimals}f".format(this)

    companion object {
        const val ACTION_START = "com.temporal.vtalogger.action.START_RECORDING"
        const val ACTION_STOP = "com.temporal.vtalogger.action.STOP_RECORDING"
        const val ACTION_PAUSE = "com.temporal.vtalogger.action.PAUSE_RECORDING"
        const val ACTION_RESUME = "com.temporal.vtalogger.action.RESUME_RECORDING"
        const val RECORDING_CHANNEL_ID = "recording"
        const val UPLOAD_CHANNEL_ID = "uploads"
        private const val NOTIFICATION_ID = 42
        private const val SENSOR_STATUS_UPDATE_INTERVAL_MS = 500L
        private const val WAKE_LOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L

        fun startIntent(context: Context): Intent = Intent(context, RecordingForegroundService::class.java).setAction(ACTION_START)

        fun stopIntent(context: Context): Intent = Intent(context, RecordingForegroundService::class.java).setAction(ACTION_STOP)

        private fun serviceIntent(context: Context, action: String): PendingIntent {
            val intent = Intent(context, RecordingForegroundService::class.java).setAction(action)
            return PendingIntent.getService(
                context,
                action.hashCode(),
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    }
}
