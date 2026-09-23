package com.khatwa.app.ui.settings

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.khatwa.app.AppContainer
import com.khatwa.app.lock.AllowlistDefaults
import com.khatwa.app.ui.components.Muted
import com.khatwa.app.ui.components.VSpace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class InstalledApp(val pkg: String, val label: String, val icon: Bitmap?)

@Composable
fun AllowlistScreen(container: AppContainer, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by container.settings.flow.collectAsStateWithLifecycle(initialValue = null)
    var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    val always = remember { AllowlistDefaults.alwaysAllowed(context) }
    val settingsPkgs = remember { AllowlistDefaults.settingsPackages() }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { loadLaunchableApps(context) }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "رجوع") }
            Text("قائمة المسموح", style = MaterialTheme.typography.headlineMedium)
        }
        Muted("التطبيقات المفعّلة هنا تبقى متاحة أثناء القفل. المكالمات وواجهة النظام والمشغّل وهذا التطبيق مسموحة دائماً.")
        VSpace(8.dp)
        settings?.let { s ->
            SwitchRow("حظر تطبيق الإعدادات أثناء القفل", s.blockSettings) { on -> scope.launch { container.settings.setBlockSettings(on) } }
        }
        OutlinedTextField(
            value = query, onValueChange = { query = it }, label = { Text("بحث") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        )
        val allowed = settings?.allowlist ?: emptySet()
        val filtered = apps.filter { query.isBlank() || it.label.contains(query, ignoreCase = true) || it.pkg.contains(query, ignoreCase = true) }
        LazyColumn(Modifier.fillMaxSize()) {
            items(filtered, key = { it.pkg }) { app ->
                val isAlways = app.pkg in always
                val isSettings = app.pkg in settingsPkgs
                val checked = isAlways || (isSettings && settings?.blockSettings == false) || app.pkg in allowed
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    app.icon?.let { Image(it.asImageBitmap(), contentDescription = null, modifier = Modifier.size(36.dp)) }
                        ?: Spacer(Modifier.size(36.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(app.label, style = MaterialTheme.typography.bodyLarge)
                        Muted(
                            when {
                                isAlways -> "مسموح دائماً"
                                isSettings -> "يتبع خيار «حظر الإعدادات»"
                                else -> app.pkg
                            }
                        )
                    }
                    Switch(
                        checked = checked,
                        enabled = !isAlways && !isSettings,
                        onCheckedChange = { on ->
                            scope.launch {
                                val next = if (on) allowed + app.pkg else allowed - app.pkg
                                container.settings.setAllowlist(next)
                            }
                        },
                    )
                }
            }
        }
    }
}

fun loadLaunchableApps(context: android.content.Context): List<InstalledApp> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val infos = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
    val seen = HashSet<String>()
    return infos.mapNotNull { ri ->
        val pkg = ri.activityInfo.packageName
        if (pkg == context.packageName || !seen.add(pkg)) return@mapNotNull null
        val label = ri.loadLabel(pm).toString()
        val icon = runCatching { ri.loadIcon(pm).toBitmap(96) }.getOrNull()
        InstalledApp(pkg, label, icon)
    }.sortedBy { it.label.lowercase() }
}

private fun Drawable.toBitmap(size: Int): Bitmap {
    if (this is BitmapDrawable && bitmap != null) return Bitmap.createScaledBitmap(bitmap, size, size, true)
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    setBounds(0, 0, size, size)
    draw(canvas)
    return bmp
}
