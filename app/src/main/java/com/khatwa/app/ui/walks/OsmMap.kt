package com.khatwa.app.ui.walks

import android.content.Context
import android.graphics.Paint
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.khatwa.app.challenge.Http
import com.khatwa.core.geo.LatLon
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.TilesOverlay
import java.io.File

/**
 * OpenStreetMap tiles, as the tile usage policy asks: the app's own User-Agent, tiles cached on
 * the phone for at least 7 days, only the tiles on screen are fetched (no offline download), and
 * the "© OpenStreetMap contributors" notice drawn on the map.
 */
object OsmConfig {
    @Volatile private var done = false

    fun ensure(context: Context) {
        if (done) return
        synchronized(this) {
            if (done) return
            val cfg = Configuration.getInstance()
            cfg.load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
            cfg.userAgentValue = Http.USER_AGENT
            val base = File(context.cacheDir, "osmdroid")
            cfg.osmdroidBasePath = base
            cfg.osmdroidTileCache = File(base, "tiles")
            cfg.tileFileSystemCacheMaxBytes = 120L * 1024 * 1024
            cfg.tileFileSystemCacheTrimBytes = 90L * 1024 * 1024
            cfg.expirationExtendedDuration = 7L * 24 * 3600 * 1000
            done = true
        }
    }
}

private fun LatLon.geo() = GeoPoint(lat, lon)

/**
 * A map showing [routes] (past walks, faint), [current] (the walk being shown, strong), a
 * [target] marker and the user's position [me]. The view zooms to fit what it shows once, or
 * follows [me] when [follow] is set (a live walk).
 */
@Composable
fun OsmMap(
    modifier: Modifier,
    routes: List<List<LatLon>> = emptyList(),
    current: List<LatLon> = emptyList(),
    target: LatLon? = null,
    targetTitle: String? = null,
    me: LatLon? = null,
    follow: Boolean = false,
    center: LatLon? = null,
) {
    val context = LocalContext.current
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.4f
    val primary = MaterialTheme.colorScheme.primary.toArgb()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val map = remember {
        OsmConfig.ensure(context)
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            minZoomLevel = 4.0
            maxZoomLevel = 19.0
            overlays.add(CopyrightOverlay(context))
        }
    }
    // Zoom set once when following; otherwise re-fit whenever the drawn points change (data arrives late).
    val fitted = remember { booleanArrayOf(false) }
    val fittedCount = remember { intArrayOf(-1) }
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> map.onResume()
                Lifecycle.Event.ON_PAUSE -> map.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        map.onResume()
        onDispose {
            lifecycle.removeObserver(obs)
            map.onPause()
            map.onDetach()
        }
    }
    AndroidView({ map }, modifier.testTag("osm_map")) { mv ->
        mv.overlayManager.tilesOverlay.setColorFilter(if (dark) TilesOverlay.INVERT_COLORS else null)
        mv.overlays.removeAll { it is Polyline || it is Marker }
        fun line(points: List<LatLon>, alpha: Int, width: Float) = Polyline(mv).apply {
            setPoints(points.map { it.geo() })
            outlinePaint.color = (primary and 0x00ffffff) or (alpha shl 24)
            outlinePaint.strokeWidth = width * context.resources.displayMetrics.density
            outlinePaint.strokeCap = Paint.Cap.ROUND
            outlinePaint.strokeJoin = Paint.Join.ROUND
            isGeodesic = false
            infoWindow = null
        }
        routes.filter { it.size > 1 }.forEach { mv.overlays.add(line(it, 0x88, 4.5f)) }
        if (current.size > 1) mv.overlays.add(line(current, 0xff, 6f))
        target?.let { t ->
            mv.overlays.add(Marker(mv).apply {
                position = t.geo()
                title = targetTitle
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            })
        }
        me?.let { p ->
            mv.overlays.add(Marker(mv).apply {
                position = p.geo()
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = androidx.core.content.ContextCompat.getDrawable(context, com.khatwa.app.R.drawable.ic_map_me)
                infoWindow = null
            })
        }
        val all = routes.flatten() + current + listOfNotNull(target, me, center)
        fun place() {
            when {
                follow && me != null -> {
                    if (!fitted[0]) mv.controller.setZoom(17.0)
                    mv.controller.setCenter(me.geo())
                    fitted[0] = true
                }
                follow -> Unit
                fittedCount[0] != all.size && all.size >= 2 && all.distinct().size >= 2 -> {
                    val box = BoundingBox.fromGeoPoints(all.map { it.geo() })
                    mv.zoomToBoundingBox(box.increaseByScale(1.35f), false, 48)
                    if (mv.zoomLevelDouble > 17.5) mv.controller.setZoom(17.5)
                    fittedCount[0] = all.size
                }
                fittedCount[0] != all.size && all.isNotEmpty() -> {
                    mv.controller.setZoom(if (center != null && all.size == 1) 12.0 else 16.0)
                    mv.controller.setCenter(all.first().geo())
                    fittedCount[0] = all.size
                }
            }
        }
        if (mv.width == 0) mv.addOnFirstLayoutListener { _, _, _, _, _ -> place() } else place()
        mv.invalidate()
    }
}
