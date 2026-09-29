package com.khatwa.app.ui.walks

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.khatwa.app.AppContainer
import com.khatwa.app.data.WalkEntity
import com.khatwa.app.i18n.strings
import com.khatwa.app.permissions.PermissionChecks
import com.khatwa.app.ui.components.CardTone
import com.khatwa.app.ui.components.KCard
import com.khatwa.app.ui.components.Muted
import com.khatwa.app.ui.components.PrimaryButton
import com.khatwa.app.ui.components.SecondaryButton
import com.khatwa.app.ui.components.SectionTitle
import com.khatwa.app.ui.components.StatPill
import com.khatwa.app.ui.components.VSpace
import com.khatwa.app.util.Fmt
import com.khatwa.core.geo.Explored
import com.khatwa.core.geo.Geo
import com.khatwa.core.walk.Distances
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Runs [action] once location may be used: asks for the permission first when needed (while
 * the app is in use only; walks never track in the background).
 */
@Composable
fun rememberWithLocation(onDenied: () -> Unit = {}): (() -> Unit) -> Unit {
    val context = LocalContext.current
    var pending by remember { androidx.compose.runtime.mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val ok = result[Manifest.permission.ACCESS_FINE_LOCATION] == true || result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        val run = pending
        pending = null
        if (ok) run?.invoke() else onDenied()
    }
    return { action ->
        if (PermissionChecks.location(context)) action()
        else {
            pending = action
            launcher.launch(PermissionChecks.LOCATION_PERMISSIONS)
        }
    }
}

@Composable
private fun TopBar(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.testTag("walk_back")) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings.back)
        }
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
}

private fun kmh(w: WalkEntity): String {
    val h = (w.endMs - w.startMs) / 3_600_000.0
    return if (h <= 0) "—" else String.format(java.util.Locale.US, "%.1f", w.distanceM / 1000.0 / h)
}

/** One saved walk: its route on the map and its numbers. */
@Composable
fun WalkDetailScreen(container: AppContainer, id: Long, onBack: () -> Unit) {
    val s = strings
    val walk by remember(id) { container.db.walks().observe(id) }.collectAsStateWithLifecycle(initialValue = null)
    val all by remember { container.db.walks().observeAll() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp).testTag("walk_detail")) {
        TopBar(walk?.placeName ?: s.walkTitle, onBack)
        val w = walk ?: return@Column
        val route = remember(w.id) { Geo.decode(w.polyline) }
        val others = remember(all, w.id) { all.filter { it.id != w.id }.map { Geo.decode(it.polyline) } }
        OsmMap(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(18.dp)), routes = others, current = route)
        VSpace(10.dp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatPill(s.distance, s.kmUnit(Distances.km(w.distanceM)), Modifier.weight(1f).testTag("walk_distance"))
            StatPill(s.duration, Fmt.duration(w.endMs - w.startMs), Modifier.weight(1f))
        }
        VSpace(8.dp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatPill(s.sortSteps, Fmt.n(w.steps), Modifier.weight(1f))
            StatPill(s.pace, s.paceKmh(kmh(w)), Modifier.weight(1f))
        }
        if (w.completed) {
            VSpace(8.dp)
            Text(s.walkMetChallenge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("walk_completed"))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Muted(Fmt.dateTime(w.startMs))
            TextButton(onClick = { scope.launch { container.db.walks().delete(w.id); onBack() } }) { Text(s.deleteWalk) }
        }
    }
}

/** Every walk on one map: the streets you have walked, and how much of them is new ground. */
@Composable
fun MyMapScreen(container: AppContainer, onBack: () -> Unit) {
    val s = strings
    val walks by remember { container.db.walks().observeAll() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val routes = remember(walks) { walks.map { Geo.decode(it.polyline) } }
    val explored = remember(routes) { Explored.distinctKm(routes.flatMapTo(HashSet()) { Explored.cellsOf(it) }) }
    val city by container.cities.current.collectAsStateWithLifecycle(initialValue = null)
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp).testTag("my_map")) {
        TopBar(s.myMap, onBack)
        OsmMap(
            Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(18.dp)),
            routes = routes,
            center = if (routes.isEmpty()) city?.center else null,
        )
        VSpace(10.dp)
        if (walks.isEmpty()) Muted(s.noWalksYet)
        else {
            Text(s.walksSummary(Fmt.n(walks.size), Distances.km(walks.sumOf { it.distanceM })), style = MaterialTheme.typography.titleMedium)
            Text(s.exploredKm(String.format(java.util.Locale.US, "%.1f", explored)), color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("my_map_explored"))
        }
        VSpace(8.dp)
    }
}

/** The walk being recorded: the route so far, the destination, and the numbers, live. */
@Composable
fun LiveWalkScreen(container: AppContainer, onBack: () -> Unit, onFinished: (Long?) -> Unit) {
    val s = strings
    val active by container.walks.active.collectAsStateWithLifecycle()
    val today by container.tracker.today.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1_000) } }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp).testTag("live_walk")) {
        TopBar(s.liveWalk, onBack)
        val w = active
        if (w == null) {
            Muted(s.walkTooShort)
            return@Column
        }
        val me = w.points.lastOrNull()
        OsmMap(
            Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(18.dp)),
            current = w.points, target = w.target, targetTitle = w.placeName, me = me, follow = true,
        )
        VSpace(10.dp)
        Text(w.placeName?.let { s.walkingTo(it) } ?: s.walkRecording, style = MaterialTheme.typography.titleMedium)
        if (!w.hasFix) Muted(s.walkWaitingForGps)
        w.target?.let { t ->
            me?.let { p ->
                val d = Geo.distanceM(p, t)
                Muted(if (d <= 80) s.targetReached else s.toTarget(Fmt.n(d.toLong())), Modifier.testTag("live_to_target"))
            }
        }
        VSpace(8.dp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatPill(s.distance, s.kmUnit(Distances.km(w.distanceM)), Modifier.weight(1f).testTag("live_distance"))
            StatPill(s.duration, Fmt.duration(now - w.startMs), Modifier.weight(1f))
            StatPill(s.sortSteps, Fmt.n((today.steps - w.stepsAtStart).coerceAtLeast(0)), Modifier.weight(1f))
        }
        VSpace(10.dp)
        PrimaryButton(s.stopWalk, Modifier.fillMaxWidth().testTag("live_stop")) {
            scope.launch { onFinished(container.walks.stop()) }
        }
        VSpace(8.dp)
    }
}

/** Stats: "My walks" — totals, the map, a new walk, and the latest walks. */
@Composable
fun WalksCard(container: AppContainer, onOpenWalk: (Long) -> Unit, onOpenMap: () -> Unit, onOpenLive: () -> Unit) {
    val s = strings
    val walks by remember { container.db.walks().observeAll() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val active by container.walks.active.collectAsStateWithLifecycle()
    val withLocation = rememberWithLocation()
    KCard(modifier = Modifier.testTag("walks_card")) {
        SectionTitle(s.myWalks)
        if (walks.isEmpty()) Muted(s.noWalksYet)
        else Text(s.walksSummary(Fmt.n(walks.size), Distances.km(walks.sumOf { it.distanceM })), style = MaterialTheme.typography.bodyLarge)
        VSpace(8.dp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(s.myMap, Modifier.weight(1f).testTag("walks_my_map")) { onOpenMap() }
            if (active != null) PrimaryButton(s.liveWalk, Modifier.weight(1f)) { onOpenLive() }
            else PrimaryButton(s.newWalk, Modifier.weight(1f).testTag("walks_new")) {
                withLocation {
                    if (container.walks.start(null, null, null)) onOpenLive()
                }
            }
        }
        walks.take(5).forEach { w ->
            Row(
                Modifier.fillMaxWidth().clickable { onOpenWalk(w.id) }.padding(vertical = 10.dp).testTag("walk_row_${w.id}"),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text((if (w.completed) "✓ " else "") + (w.placeName ?: s.walkTitle), style = MaterialTheme.typography.bodyLarge)
                    Muted(Fmt.dateTime(w.startMs))
                }
                Text(s.kmUnit(Distances.km(w.distanceM)), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/** Shared by Home: while a walk is recorded, a banner with its distance, the map and "finish". */
@Composable
fun ActiveWalkBanner(container: AppContainer, onOpenLive: () -> Unit) {
    val s = strings
    val active by container.walks.active.collectAsStateWithLifecycle()
    val w = active ?: return
    val scope = rememberCoroutineScope()
    KCard(tone = CardTone.Accent, modifier = Modifier.testTag("active_walk")) {
        Text(w.placeName?.let { s.walkingTo(it) } ?: s.walkRecording, style = MaterialTheme.typography.titleMedium)
        Muted(if (w.hasFix) s.walkKm(Distances.km(w.distanceM)) else s.walkWaitingForGps)
        VSpace(8.dp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(s.liveWalk, Modifier.weight(1f).testTag("active_walk_map")) { onOpenLive() }
            PrimaryButton(s.stopWalk, Modifier.weight(1f).testTag("active_walk_stop")) { scope.launch { container.walks.stop() } }
        }
    }
    VSpace()
}
