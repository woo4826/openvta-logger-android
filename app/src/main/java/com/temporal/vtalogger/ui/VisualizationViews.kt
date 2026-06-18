package com.temporal.vtalogger.ui

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.temporal.vtalogger.domain.GpsDisplayTimeFormatter
import com.temporal.vtalogger.domain.GpsTracePoint
import com.temporal.vtalogger.domain.VtaTrace
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil

@Composable
fun VisualizationCard(
    title: String,
    trace: VtaTrace,
    modifier: Modifier = Modifier,
    followLatest: Boolean = false,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            SummaryRow(trace)
            val displayPoints = trace.displayGpsPoints
            if (displayPoints.isEmpty()) {
                Text("Waiting for GPS route", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("Route map", style = MaterialTheme.typography.titleSmall)
            MapLibreRouteMap(trace = trace, followLatest = followLatest)
            Text(
                "Map data © OpenStreetMap contributors",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LatestGpsDetails(trace.latestGps)
            RecentGpsFixes(displayPoints.takeLast(5))
            MetricLineChart(
                title = "Speed km/h",
                values = displayPoints.map { it.speedKmh },
                lineColor = MaterialTheme.colorScheme.primary,
                emptyText = "No GPS speed data",
            )
            MetricLineChart(
                title = "Altitude m",
                values = displayPoints.map { it.altitudeMeters },
                lineColor = MaterialTheme.colorScheme.tertiary,
                emptyText = "No altitude data",
            )
            MetricLineChart(
                title = "Accuracy m",
                values = displayPoints.mapNotNull { it.accuracyMeters },
                lineColor = MaterialTheme.colorScheme.error,
                emptyText = "No accuracy data",
            )
        }
    }
}

@Composable
private fun SummaryRow(trace: VtaTrace) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            SummaryMetric(label = "Raw GPS", value = trace.rawGpsCount.toString())
            SummaryMetric(label = "Trace", value = trace.pointCount.toString())
            SummaryMetric(label = "Max", value = "%.0f km/h".format(trace.maxSpeedKmh))
            SummaryMetric(label = "Sensor", value = trace.sensorCount.toString())
        }
        Text(
            "Preset: ${trace.enhancementPreset.displayName} (${trace.enhancementPreset.outputHz}Hz, +${trace.enhancedPointCount} derived)",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SummaryMetric(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun LatestGpsDetails(point: GpsTracePoint?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Latest GPS", style = MaterialTheme.typography.titleSmall)
        if (point == null) {
            Text("No GPS fix yet", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        DetailRow(
            "Time" to GpsDisplayTimeFormatter.format(point),
            "Provider" to (point.provider ?: "legacy"),
        )
        DetailRow(
            "Latitude" to formatCoordinate(point.latitude),
            "Longitude" to formatCoordinate(point.longitude),
        )
        DetailRow(
            "Altitude" to formatMeters(point.altitudeMeters),
            "Accuracy" to (point.accuracyMeters?.let(::formatMeters) ?: "--"),
        )
        DetailRow(
            "Speed" to formatKmh(point.speedKmh),
            "Bearing" to formatDegrees(point.bearingDegrees),
        )
        DetailRow(
            "Satellites" to point.satelliteCount.toString(),
            "Elapsed RT" to (point.elapsedRealtimeNanos?.let(::formatElapsedRealtime) ?: "--"),
        )
        DetailRow(
            "Source" to point.source.label,
            "Confidence" to String.format(Locale.US, "%.0f%%", point.confidence * 100.0),
        )
    }
}

@Composable
private fun DetailRow(
    first: Pair<String, String>,
    second: Pair<String, String>,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        DetailMetric(first.first, first.second, Modifier.weight(1f))
        DetailMetric(second.first, second.second, Modifier.weight(1f))
    }
}

@Composable
private fun DetailMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun RecentGpsFixes(points: List<GpsTracePoint>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Recent GPS fixes", style = MaterialTheme.typography.titleSmall)
        if (points.isEmpty()) {
            Text("No GPS fix rows", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        points.asReversed().forEachIndexed { index, point ->
            if (index > 0) {
                HorizontalDivider()
            }
            Text(
                "${GpsDisplayTimeFormatter.format(point)}  ${formatCoordinate(point.latitude)}, ${formatCoordinate(point.longitude)}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "alt ${formatMeters(point.altitudeMeters)} / speed ${formatKmh(point.speedKmh)} / acc ${point.accuracyMeters?.let(::formatMeters) ?: "--"} / ${point.source.label}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun MetricLineChart(
    title: String,
    values: List<Double>,
    lineColor: Color,
    emptyText: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                if (values.isEmpty()) "--" else "max %.1f".format(values.maxOrNull() ?: 0.0),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        if (values.isEmpty()) {
            Text(emptyText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp),
            ) {
                val minValue = values.minOrNull() ?: 0.0
                val maxValue = values.maxOrNull() ?: minValue
                val range = (maxValue - minValue).takeIf { abs(it) > 0.0001 } ?: 1.0
                val xStep = if (values.size == 1) 0f else size.width / (values.size - 1)
                val points = values.mapIndexed { index, value ->
                    val x = if (values.size == 1) size.width / 2f else xStep * index
                    val y = size.height - (((value - minValue) / range).toFloat() * size.height)
                    Offset(x, y.coerceIn(0f, size.height))
                }
                drawLine(
                    color = Color(0xFFE0E0E0),
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = 1.dp.toPx(),
                )
                if (points.size == 1) {
                    drawCircle(lineColor, radius = 4.dp.toPx(), center = points.first())
                } else {
                    val path = Path().apply {
                        moveTo(points.first().x, points.first().y)
                        points.drop(1).forEach { lineTo(it.x, it.y) }
                    }
                    drawPath(
                        path = path,
                        color = lineColor,
                        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
            }
        }
    }
}

@Composable
fun MapLibreRouteMap(
    trace: VtaTrace,
    modifier: Modifier = Modifier,
    followLatest: Boolean = false,
) {
    val routePoints = trace.displayGpsPoints
    val rawPoints = trace.gpsPoints
    val enhancedPoints = trace.enhancedGpsPoints
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val useDarkMapStyle = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val mapView = remember(useDarkMapStyle) { createMapLibreMapView(context, useDarkMapStyle) }
    val routeState = remember(mapView) { MapLibreRouteState() }
    val routeKey = remember(routePoints, rawPoints, enhancedPoints, followLatest) {
        val latest = routePoints.lastOrNull()
        val updateBucket = latest?.elapsedRealtimeNanos?.div(MAP_UPDATE_INTERVAL_NANOS) ?: routePoints.size.toLong()
        val first = routePoints.firstOrNull()
        "${routePoints.size}:${rawPoints.size}:${enhancedPoints.size}:${updateBucket}:${first?.latitude}:${first?.longitude}:${latest?.latitude}:${latest?.longitude}:$followLatest"
    }

    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> Unit
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        mapView.onStart()
        mapView.onResume()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    LaunchedEffect(routeKey) {
        updateMapLibreRoute(mapView, routeState, routePoints, rawPoints, enhancedPoints, followLatest)
    }

    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            .height(300.dp)
            .clipToBounds(),
        factory = { mapView },
    )
}

private fun createMapLibreMapView(context: Context, useDarkMapStyle: Boolean): MapView {
    MapLibre.getInstance(context.applicationContext)
    return MapView(context).apply {
        onCreate(null)
        getMapAsync { map ->
            map.uiSettings.isLogoEnabled = false
            map.uiSettings.isAttributionEnabled = false
            map.uiSettings.isCompassEnabled = false
            val styleJson = if (useDarkMapStyle) OSM_RASTER_DARK_STYLE_JSON else OSM_RASTER_STYLE_JSON
            map.setStyle(Style.Builder().fromJson(styleJson)) { style ->
                ensureRouteLayers(style)
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(DEFAULT_MAP_CENTER, DEFAULT_MAP_ZOOM))
            }
        }
    }
}

private class MapLibreRouteState {
    var lastCameraKey: String? = null
}

private fun updateMapLibreRoute(
    mapView: MapView,
    state: MapLibreRouteState,
    routePoints: List<GpsTracePoint>,
    rawPoints: List<GpsTracePoint>,
    enhancedPoints: List<GpsTracePoint>,
    followLatest: Boolean,
) {
    mapView.getMapAsync { map ->
        map.getStyle { style ->
            ensureRouteLayers(style)
            val visibleRoutePoints = downsampleRoute(routePoints)
            val visibleRawPoints = downsampleRoute(rawPoints)
            val visibleEnhancedPoints = downsampleRoute(enhancedPoints)
            updateRouteSources(style, visibleRoutePoints, visibleRawPoints, visibleEnhancedPoints)
            updateRouteCamera(map, state, visibleRoutePoints, followLatest)
        }
    }
}

private fun ensureRouteLayers(style: Style) {
    if (style.getSource(ROUTE_SOURCE_ID) == null) {
        style.addSource(GeoJsonSource(ROUTE_SOURCE_ID, emptyFeatureCollection()))
    }
    if (style.getSource(RAW_POINTS_SOURCE_ID) == null) {
        style.addSource(GeoJsonSource(RAW_POINTS_SOURCE_ID, emptyFeatureCollection()))
    }
    if (style.getSource(ENHANCED_POINTS_SOURCE_ID) == null) {
        style.addSource(GeoJsonSource(ENHANCED_POINTS_SOURCE_ID, emptyFeatureCollection()))
    }
    if (style.getSource(START_SOURCE_ID) == null) {
        style.addSource(GeoJsonSource(START_SOURCE_ID, emptyFeatureCollection()))
    }
    if (style.getSource(LATEST_SOURCE_ID) == null) {
        style.addSource(GeoJsonSource(LATEST_SOURCE_ID, emptyFeatureCollection()))
    }
    if (style.getLayer(ROUTE_LAYER_ID) == null) {
        style.addLayer(
            LineLayer(ROUTE_LAYER_ID, ROUTE_SOURCE_ID).withProperties(
                lineColor("#5B50A4"),
                lineWidth(5f),
            ),
        )
    }
    if (style.getLayer(ENHANCED_POINTS_LAYER_ID) == null) {
        style.addLayer(
            CircleLayer(ENHANCED_POINTS_LAYER_ID, ENHANCED_POINTS_SOURCE_ID).withProperties(
                circleColor("#F9A825"),
                circleRadius(3f),
                circleStrokeColor("#4A3500"),
                circleStrokeWidth(0.8f),
            ),
        )
    }
    if (style.getLayer(RAW_POINTS_LAYER_ID) == null) {
        style.addLayer(
            CircleLayer(RAW_POINTS_LAYER_ID, RAW_POINTS_SOURCE_ID).withProperties(
                circleColor("#1565C0"),
                circleRadius(4.5f),
                circleStrokeColor("#FFFFFF"),
                circleStrokeWidth(1.2f),
            ),
        )
    }
    if (style.getLayer(START_LAYER_ID) == null) {
        style.addLayer(
            CircleLayer(START_LAYER_ID, START_SOURCE_ID).withProperties(
                circleColor("#2E7D32"),
                circleRadius(6f),
                circleStrokeColor("#FFFFFF"),
                circleStrokeWidth(2f),
            ),
        )
    }
    if (style.getLayer(LATEST_LAYER_ID) == null) {
        style.addLayer(
            CircleLayer(LATEST_LAYER_ID, LATEST_SOURCE_ID).withProperties(
                circleColor("#D7353F"),
                circleRadius(7f),
                circleStrokeColor("#FFFFFF"),
                circleStrokeWidth(2f),
            ),
        )
    }
}

private fun updateRouteSources(
    style: Style,
    routePoints: List<GpsTracePoint>,
    rawPoints: List<GpsTracePoint>,
    enhancedPoints: List<GpsTracePoint>,
) {
    style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE_ID)?.setGeoJson(routeFeatureCollection(routePoints))
    style.getSourceAs<GeoJsonSource>(RAW_POINTS_SOURCE_ID)?.setGeoJson(pointsFeatureCollection(rawPoints))
    style.getSourceAs<GeoJsonSource>(ENHANCED_POINTS_SOURCE_ID)?.setGeoJson(pointsFeatureCollection(enhancedPoints))
    style.getSourceAs<GeoJsonSource>(START_SOURCE_ID)?.setGeoJson(pointFeatureCollection(routePoints.firstOrNull()))
    style.getSourceAs<GeoJsonSource>(LATEST_SOURCE_ID)?.setGeoJson(pointFeatureCollection(routePoints.lastOrNull()))
}

private fun updateRouteCamera(
    map: MapLibreMap,
    state: MapLibreRouteState,
    points: List<GpsTracePoint>,
    followLatest: Boolean,
) {
    val latest = points.lastOrNull() ?: return
    if (followLatest) {
        val cameraKey = "live:${latest.latitude}:${latest.longitude}"
        if (state.lastCameraKey != cameraKey) {
            state.lastCameraKey = cameraKey
            map.animateCamera(
                CameraUpdateFactory.newLatLngZoom(LatLng(latest.latitude, latest.longitude), LIVE_MAP_ZOOM),
                MAP_CAMERA_ANIMATION_MS,
            )
        }
        return
    }

    val cameraKey = "fit:${points.size}:${points.first().latitude}:${points.first().longitude}:${latest.latitude}:${latest.longitude}"
    if (state.lastCameraKey == cameraKey) return
    state.lastCameraKey = cameraKey
    if (points.size == 1) {
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(latest.latitude, latest.longitude), STORED_MAP_SINGLE_POINT_ZOOM))
    } else {
        val bounds = LatLngBounds.Builder().also { builder ->
            points.forEach { builder.include(LatLng(it.latitude, it.longitude)) }
        }.build()
        map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, MAP_BOUNDS_PADDING_PX))
    }
}

private fun downsampleRoute(points: List<GpsTracePoint>): List<GpsTracePoint> {
    if (points.size <= MAX_MAP_POINTS) return points
    val tail = points.takeLast(MAX_RETAINED_ROUTE_POINTS)
    if (tail.size <= MAX_MAP_POINTS) return tail
    val step = ceil(tail.size.toDouble() / MAX_MAP_POINTS).toInt().coerceAtLeast(1)
    val sampled = tail.filterIndexed { index, _ -> index % step == 0 }.toMutableList()
    val latest = tail.last()
    if (sampled.lastOrNull() != latest) sampled.add(latest)
    return sampled
}

private fun routeFeatureCollection(points: List<GpsTracePoint>): FeatureCollection {
    if (points.size < 2) return emptyFeatureCollection()
    val coordinates = points.map { Point.fromLngLat(it.longitude, it.latitude) }
    return FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(coordinates)))
}

private fun pointFeatureCollection(point: GpsTracePoint?): FeatureCollection {
    return point?.let {
        FeatureCollection.fromFeature(Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude)))
    } ?: emptyFeatureCollection()
}

private fun pointsFeatureCollection(points: List<GpsTracePoint>): FeatureCollection {
    if (points.isEmpty()) return emptyFeatureCollection()
    return FeatureCollection.fromFeatures(
        points.map { point ->
            Feature.fromGeometry(Point.fromLngLat(point.longitude, point.latitude))
        },
    )
}

private fun emptyFeatureCollection(): FeatureCollection = FeatureCollection.fromFeatures(emptyList())

private fun formatCoordinate(value: Double): String = String.format(Locale.US, "%.7f", value)

private fun formatMeters(value: Double): String = String.format(Locale.US, "%.1f m", value)

private fun formatKmh(value: Double): String = String.format(Locale.US, "%.1f km/h", value)

private fun formatDegrees(value: Double): String = String.format(Locale.US, "%.0f deg", value)

private fun formatElapsedRealtime(value: Long): String = String.format(Locale.US, "%.3f s", value / 1_000_000_000.0)

private const val ROUTE_SOURCE_ID = "vta-route-source"
private const val RAW_POINTS_SOURCE_ID = "vta-raw-points-source"
private const val ENHANCED_POINTS_SOURCE_ID = "vta-enhanced-points-source"
private const val START_SOURCE_ID = "vta-start-source"
private const val LATEST_SOURCE_ID = "vta-latest-source"
private const val ROUTE_LAYER_ID = "vta-route-layer"
private const val RAW_POINTS_LAYER_ID = "vta-raw-points-layer"
private const val ENHANCED_POINTS_LAYER_ID = "vta-enhanced-points-layer"
private const val START_LAYER_ID = "vta-start-layer"
private const val LATEST_LAYER_ID = "vta-latest-layer"
private const val LIVE_MAP_ZOOM = 16.0
private const val STORED_MAP_SINGLE_POINT_ZOOM = 15.0
private const val DEFAULT_MAP_ZOOM = 2.0
private const val MAP_CAMERA_ANIMATION_MS = 120
private const val MAP_BOUNDS_PADDING_PX = 48
private const val MAP_UPDATE_INTERVAL_NANOS = 1_000_000_000L
private const val MAX_MAP_POINTS = 500
private const val MAX_RETAINED_ROUTE_POINTS = 2_000
private val DEFAULT_MAP_CENTER = LatLng(0.0, 0.0)
private val OSM_RASTER_STYLE_JSON = """
{
  "version": 8,
  "sources": {
    "osm-raster": {
      "type": "raster",
      "tiles": ["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],
      "tileSize": 256,
      "attribution": "© OpenStreetMap contributors"
    }
  },
  "layers": [
    {
      "id": "osm-raster-layer",
      "type": "raster",
      "source": "osm-raster"
    }
  ]
}
""".trimIndent()
private val OSM_RASTER_DARK_STYLE_JSON = """
{
  "version": 8,
  "sources": {
    "osm-raster": {
      "type": "raster",
      "tiles": ["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],
      "tileSize": 256,
      "attribution": "© OpenStreetMap contributors"
    }
  },
  "layers": [
    {
      "id": "osm-raster-layer",
      "type": "raster",
      "source": "osm-raster",
      "paint": {
        "raster-brightness-min": 0.05,
        "raster-brightness-max": 0.55,
        "raster-saturation": -0.85,
        "raster-contrast": 0.25
      }
    }
  ]
}
""".trimIndent()
