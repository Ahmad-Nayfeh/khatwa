package com.khatwa.app.ui.city

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.khatwa.app.AppContainer
import com.khatwa.app.challenge.CityRepository.Detected
import com.khatwa.app.i18n.strings
import com.khatwa.app.ui.components.CardTone
import com.khatwa.app.ui.components.KCard
import com.khatwa.app.ui.components.Muted
import com.khatwa.app.ui.components.SecondaryButton
import com.khatwa.app.ui.components.VSpace
import com.khatwa.app.ui.settings.SubScreen
import com.khatwa.app.ui.walks.rememberWithLocation
import com.khatwa.core.city.Cities
import kotlinx.coroutines.launch

/**
 * Choose the city: a searchable drop-down of every Saudi city we cover, or "find my city" with
 * GPS. Outside those cities the user is told the challenges are not available there yet.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun CityPicker(container: AppContainer) {
    val s = strings
    val arabic = s.rtl
    val scope = rememberCoroutineScope()
    val current by container.cities.current.collectAsStateWithLifecycle(initialValue = null)
    var query by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Detected?>(null) }
    var detecting by remember { mutableStateOf(false) }
    val withLocation = rememberWithLocation(onDenied = { result = Detected.NoPermission })
    // Close the keyboard once a city is chosen.
    val focus = androidx.compose.ui.platform.LocalFocusManager.current

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = if (expanded) query else current?.let { "${it.name(arabic)} — ${it.region(arabic)}" }.orEmpty(),
            onValueChange = { query = it; expanded = true },
            label = { Text(s.cityField) },
            placeholder = { Text(s.citySearchHint) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            singleLine = true,
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryEditable).fillMaxWidth().testTag("city_field"),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            // The menu is its own window: expose test tags there too.
            modifier = Modifier.semantics { testTagsAsResourceId = true },
        ) {
            Cities.search(container.cities.all, query).forEach { c ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(c.name(arabic), style = MaterialTheme.typography.bodyLarge)
                            Text(c.region(arabic), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    onClick = {
                        scope.launch { container.settings.setCityId(c.id) }
                        result = null
                        query = ""
                        expanded = false
                        focus.clearFocus()
                    },
                    modifier = Modifier.testTag("city_${c.id}"),
                )
            }
        }
    }
    VSpace(8.dp)
    SecondaryButton(if (detecting) s.detectingCity else s.detectCity, Modifier.fillMaxWidth().testTag("city_detect"), enabled = !detecting) {
        focus.clearFocus()
        withLocation {
            detecting = true
            scope.launch {
                val r = container.cities.detect(container.walks.location)
                if (r is Detected.Found) container.settings.setCityId(r.city.id)
                result = r
                detecting = false
            }
        }
    }
    when (val r = result) {
        is Detected.Found -> { VSpace(8.dp); KCard(tone = CardTone.Accent, modifier = Modifier.testTag("city_found")) { Text(s.cityDetected(r.city.name(arabic))) } }
        Detected.Outside -> { VSpace(8.dp); KCard(tone = CardTone.Warning, modifier = Modifier.testTag("city_outside")) { Text(s.outsideArea) } }
        Detected.NoFix -> { VSpace(8.dp); KCard(tone = CardTone.Warning) { Text(s.locationNoFix) } }
        Detected.NoPermission -> { VSpace(8.dp); KCard(tone = CardTone.Warning) { Text(s.locationDenied) } }
        null -> Unit
    }
    VSpace(6.dp)
    Muted(s.citiesSaudiOnly)
}

/** Height in cm, for the step length. Saved as soon as it is a sensible number. */
@Composable
fun HeightField(container: AppContainer) {
    val s = strings
    val scope = rememberCoroutineScope()
    val settings by container.settings.flow.collectAsStateWithLifecycle(initialValue = null)
    var text by remember(settings?.heightCm) { mutableStateOf(settings?.heightCm?.toString().orEmpty()) }
    OutlinedTextField(
        value = text,
        onValueChange = { v ->
            text = v.filter { it.isDigit() }.take(3)
            text.toIntOrNull()?.takeIf { it in 100..230 }?.let { h -> scope.launch { container.settings.setHeightCm(h) } }
        },
        label = { Text(s.heightLabel) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().testTag("height_field"),
    )
    VSpace(4.dp)
    Muted(s.heightHint)
    settings?.let { Muted(s.strideLine(String.format(java.util.Locale.US, "%.0f", it.strideM * 100))) }
}

/** Settings → City and distance. */
@Composable
fun CityScreen(container: AppContainer, onBack: () -> Unit) {
    val s = strings
    SubScreen(s.cityAndDistance, onBack, tag = "city_screen") {
        Text(s.cityTitle, style = MaterialTheme.typography.titleMedium)
        VSpace(6.dp)
        CityPicker(container)
        VSpace()
        HeightField(container)
        VSpace()
        Muted(s.dataSources)
    }
}
