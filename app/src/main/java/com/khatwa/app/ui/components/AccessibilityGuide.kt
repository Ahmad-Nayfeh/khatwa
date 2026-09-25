package com.khatwa.app.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.khatwa.app.i18n.strings
import com.khatwa.app.permissions.PermissionChecks

/**
 * How to turn on the lock's accessibility service. On Android 13+ an app installed from outside
 * the store gets a "Restricted setting" block, and the "Allow restricted settings" item in the
 * app-info menu (⋮) only appears after the user has tried once from Accessibility. So:
 *  1. Accessibility → Installed apps → Khatwa → "Restricted setting" message → OK
 *  2. App info → ⋮ → Allow restricted settings → confirm
 *  3. Accessibility → Installed apps → Khatwa → turn it on
 * Each step has a button to the right screen; the step after the last one opened is highlighted.
 * Below Android 13 only the last step exists.
 */
@Composable
fun AccessibilityGuide(enabled: Boolean) {
    val s = strings
    val context = LocalContext.current
    if (enabled) {
        Text(s.enabledCheck, color = MaterialTheme.colorScheme.primary)
        return
    }
    if (Build.VERSION.SDK_INT < 33) {
        PrimaryButton(s.openAccessibilitySettings, Modifier.fillMaxWidth()) { context.startActivity(PermissionChecks.accessibilityIntent()) }
        return
    }
    // The last step whose button was pressed (0: none yet). Remembered across the trips to Settings.
    var opened by rememberSaveable { mutableIntStateOf(0) }
    Text(s.a11yGuideIntro, modifier = Modifier.testTag("a11y_guide"))
    VSpace(10.dp)
    val steps = listOf(
        Triple(s.a11yStep1, s.a11yStep1Button) { context.startActivity(PermissionChecks.accessibilityIntent()) },
        Triple(s.a11yStep2, s.a11yStep2Button) { context.startActivity(PermissionChecks.appInfoIntent(context)) },
        Triple(s.a11yStep3, s.a11yStep3Button) { context.startActivity(PermissionChecks.accessibilityIntent()) },
    )
    steps.forEachIndexed { i, (text, button, open) ->
        val number = i + 1
        val current = number == opened + 1 || (opened >= 3 && number == 3)
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(30.dp).background(
                    if (number <= opened) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant, CircleShape,
                ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (number <= opened && number < 3) "✓" else number.toString(),
                    color = if (number <= opened) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(text, style = MaterialTheme.typography.bodyLarge)
                VSpace(6.dp)
                val act = { opened = maxOf(opened, number); open() }
                if (current) PrimaryButton(button, Modifier.fillMaxWidth().testTag("a11y_step_$number"), onClick = act)
                else SecondaryButton(button, Modifier.fillMaxWidth().testTag("a11y_step_$number"), onClick = act)
            }
        }
    }
    VSpace(4.dp)
    Muted(s.a11yNamesNote)
}
