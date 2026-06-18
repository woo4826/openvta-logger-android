package com.temporal.vtalogger.domain

import java.io.File

data class AppSettings(
    val driverId: String = "CC00",
    val ftpHost: String = "",
    val ftpPort: Int = 21,
    val ftpUser: String = "",
    val ftpPassword: String = "",
    val passiveMode: Boolean = true,
    val keepLocalFiles: Boolean = true,
    val darkMode: Boolean = false,
    val imuPresetId: String = ImuEnhancementPresets.DEFAULT_ID,
)

enum class UploadState {
    NotQueued,
    Queued,
    Uploading,
    Uploaded,
    Failed,
}

data class RecordingSession(
    val id: String,
    val driverId: String,
    val startedAtMillis: Long,
    val vtaFile: File,
    val zipFile: File,
    val endedAtMillis: Long? = null,
    val uploadState: UploadState = UploadState.NotQueued,
    val lastError: String? = null,
    val imuPresetId: String = ImuEnhancementPresets.DEFAULT_ID,
)

data class GpsSample(
    val timeMillis: Long,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double,
    val speedMetersPerSecond: Float,
    val bearingDegrees: Float,
    val satelliteCount: Int,
    val accuracyMeters: Float?,
    val provider: String,
    val elapsedRealtimeNanos: Long,
)

data class SensorSnapshot(
    val orientation: FloatArray = floatArrayOf(0f, 0f, 0f),
    val magnetic: FloatArray = floatArrayOf(0f, 0f, 0f),
    val gyro: FloatArray = floatArrayOf(0f, 0f, 0f),
    val rotation: FloatArray = floatArrayOf(0f, 0f, 0f),
) {
    fun orientationX() = orientation.getOrElse(0) { 0f }
    fun orientationY() = orientation.getOrElse(1) { 0f }
    fun orientationZ() = orientation.getOrElse(2) { 0f }
    fun magneticX() = magnetic.getOrElse(0) { 0f }
    fun magneticY() = magnetic.getOrElse(1) { 0f }
    fun magneticZ() = magnetic.getOrElse(2) { 0f }
    fun gyroX() = gyro.getOrElse(0) { 0f }
    fun gyroY() = gyro.getOrElse(1) { 0f }
    fun gyroZ() = gyro.getOrElse(2) { 0f }
    fun rotationAzimuth() = rotation.getOrElse(0) { 0f }
    fun rotationPitch() = rotation.getOrElse(1) { 0f }
    fun rotationRoll() = rotation.getOrElse(2) { 0f }
}

data class SensorSample(
    val index: Long,
    val elapsedSeconds: Double,
    val eventCode: Int = 0,
    val snapshot: SensorSnapshot,
    val accel: FloatArray,
    val sensorTimestampNanos: Long,
    val sensorAccuracy: Int,
) {
    fun accelX() = accel.getOrElse(0) { 0f }
    fun accelY() = accel.getOrElse(1) { 0f }
    fun accelZ() = accel.getOrElse(2) { 0f }
}

data class RecordingStatus(
    val isRecording: Boolean = false,
    val isPaused: Boolean = false,
    val currentSession: RecordingSession? = null,
    val gpsFixCount: Long = 0,
    val sensorSampleCount: Long = 0,
    val speedKmh: Double = 0.0,
    val distanceMeters: Double = 0.0,
    val lastGps: GpsSample? = null,
    val lastMessage: String = "Idle",
    val liveTrace: VtaTrace = VtaTrace("Live recording", emptyList(), emptyList()),
)
