package com.khatwa.app.ui.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.khatwa.app.AppContainer
import com.khatwa.app.i18n.strings
import com.khatwa.app.lock.LaptopChallenge
import com.khatwa.app.ui.components.CardTone
import com.khatwa.app.ui.components.KCard
import com.khatwa.app.ui.components.Muted
import com.khatwa.app.ui.components.PrimaryButton
import com.khatwa.app.ui.components.SecondaryButton
import com.khatwa.app.ui.components.SectionTitle
import com.khatwa.app.ui.components.VSpace
import com.khatwa.core.laptop.LaptopCode
import kotlinx.coroutines.launch

/**
 * A code shown large for typing on the laptop ("123 456", or "1234 5678" for pairing). No copy
 * button: the code is read off the phone and typed on the laptop. Always left-to-right digits.
 */
@Composable
fun CodeBox(label: String, code: String, tag: String) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        VSpace(4.dp)
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Text(
                LaptopCode.format(code),
                style = MaterialTheme.typography.displayLarge.copy(fontFamily = FontFamily.Monospace, fontSize = 44.sp, letterSpacing = 4.sp),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().testTag(tag),
            )
        }
    }
}

/**
 * Inside the active-lock card: the laptop lock code for this challenge, or a pairing prompt.
 * After the lock ends: a separate card with the unlock code (see [LaptopUnlockCard]).
 */
@Composable
fun LaptopLockCodeRow(container: AppContainer, secret: String?, challenge: LaptopChallenge?, onPair: () -> Unit) {
    if (challenge == null || challenge.finished) return
    val s = strings
    VSpace(10.dp)
    if (secret == null) {
        Muted(s.pairLaptopHint)
        VSpace(6.dp)
        SecondaryButton(s.pairLaptop, Modifier.fillMaxWidth().testTag("laptop_pair")) { onPair() }
    } else {
        val counter = challenge.counter ?: return
        CodeBox(s.laptopLockCodeLabel, LaptopCode.lockCode(secret, counter), "home_lock_code")
    }
}

/** Shown on Home after the challenge ended: the unlock code for the laptop, until dismissed. */
@Composable
fun LaptopUnlockCard(container: AppContainer, secret: String?, challenge: LaptopChallenge?) {
    if (challenge == null || !challenge.finished || secret == null) return
    val counter = challenge.counter ?: return
    val s = strings
    val scope = rememberCoroutineScope()
    KCard(tone = CardTone.Accent) {
        SectionTitle(if (challenge.finishReason == "surrender") s.challengeEndedSurrender else s.challengeDone)
        VSpace(6.dp)
        CodeBox(s.laptopUnlockCodeLabel, LaptopCode.unlockCode(secret, counter), "home_unlock_code")
        VSpace(6.dp)
        TextButton(onClick = { scope.launch { container.lock.dismissLaptopChallenge() } }, modifier = Modifier.testTag("home_unlock_dismiss")) {
            Text(s.hideCode)
        }
    }
    VSpace()
}

/** One-time pairing: shows the pairing code the laptop program asks for on its first run. */
@Composable
fun PairingDialog(container: AppContainer, secret: String?, onDismiss: () -> Unit) {
    val strs = strings
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf(secret) }
    if (current == null) {
        val s = LaptopCode.generateSecret()
        current = s
        scope.launch { container.settings.setLaptopSecret(s); container.lock.onLaptopPaired() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strs.pairLaptop) },
        text = {
            Column {
                Muted(strs.pairingDialogText)
                VSpace(10.dp)
                CodeBox(strs.pairingCode, current!!, "laptop_secret")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(strs.done) } },
    )
}

/** Small pairing/status card used in Settings ← laptop lock. */
@Composable
fun PairingStatus(container: AppContainer, secret: String?, onRegenerate: () -> Unit) {
    val s = strings
    if (secret == null) {
        Muted(s.notPairedYet)
    } else {
        CodeBox(s.currentPairingCode, secret, "laptop_secret")
        VSpace(8.dp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onRegenerate) { Text(s.regeneratePairing) }
        }
    }
}
