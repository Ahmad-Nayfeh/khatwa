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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.khatwa.app.AppContainer
import com.khatwa.app.lock.LaptopChallenge
import com.khatwa.app.ui.components.CardTone
import com.khatwa.app.ui.components.KCard
import com.khatwa.app.ui.components.Muted
import com.khatwa.app.ui.components.PrimaryButton
import com.khatwa.app.ui.components.SecondaryButton
import com.khatwa.app.ui.components.SectionTitle
import com.khatwa.app.ui.components.VSpace
import com.khatwa.app.util.Clip
import com.khatwa.core.laptop.LaptopCode
import kotlinx.coroutines.launch

/** A code shown large, monospace, with a copy button. */
@Composable
fun CodeBox(label: String, code: String, tag: String, copyLabel: String = "نسخ") {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        VSpace(4.dp)
        Text(
            LaptopCode.format(code),
            style = MaterialTheme.typography.displayLarge.copy(fontFamily = FontFamily.Monospace, fontSize = 34.sp, letterSpacing = 3.sp),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().testTag(tag),
        )
        VSpace(6.dp)
        SecondaryButton(copyLabel, Modifier.fillMaxWidth().testTag("${tag}_copy")) {
            Clip.copy(context, "khatwa code", LaptopCode.format(code), "تم النسخ")
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
    VSpace(10.dp)
    if (secret == null) {
        Muted("لقفل اللابتوب مع هذا التحدي، اقرن اللابتوب مرة واحدة.")
        VSpace(6.dp)
        SecondaryButton("اقتران اللابتوب", Modifier.fillMaxWidth().testTag("laptop_pair")) { onPair() }
    } else {
        CodeBox("كود قفل اللابتوب (الصقه في برنامج اللابتوب)", LaptopCode.lockCode(secret, challenge.id), "home_lock_code")
    }
}

/** Shown on Home after the challenge ended: the unlock code for the laptop, until dismissed. */
@Composable
fun LaptopUnlockCard(container: AppContainer, secret: String?, challenge: LaptopChallenge?) {
    if (challenge == null || !challenge.finished || secret == null) return
    val scope = rememberCoroutineScope()
    KCard(tone = CardTone.Accent) {
        SectionTitle(if (challenge.finishReason == "surrender") "انتهى التحدي (استسلام)" else "أكملت التحدي")
        VSpace(6.dp)
        CodeBox("كود فتح اللابتوب", LaptopCode.unlockCode(secret, challenge.id), "home_unlock_code")
        VSpace(6.dp)
        TextButton(onClick = { scope.launch { container.lock.dismissLaptopChallenge() } }, modifier = Modifier.testTag("home_unlock_dismiss")) {
            Text("تم، أخفِ الكود")
        }
    }
    VSpace()
}

/** One-time pairing: shows the pairing code the laptop program asks for on its first run. */
@Composable
fun PairingDialog(container: AppContainer, secret: String?, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf(secret) }
    if (current == null) {
        val s = LaptopCode.generateSecret()
        current = s
        scope.launch { container.settings.setLaptopSecret(s) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("اقتران اللابتوب") },
        text = {
            Column {
                Muted("افتح برنامج خطوة على اللابتوب لأول مرة وأدخل هذا الكود. مرة واحدة فقط، ثم تُقفل وتُفتح بالأكواد من الرئيسية.")
                VSpace(10.dp)
                CodeBox("كود الاقتران", current!!, "laptop_secret")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("تم") } },
    )
}

/** Small pairing/status card used in Settings ← laptop lock. */
@Composable
fun PairingStatus(container: AppContainer, secret: String?, onRegenerate: () -> Unit) {
    if (secret == null) {
        Muted("لم يُقرن اللابتوب بعد. الاقتران يبدأ من الرئيسية عند أول قفل، أو من هنا.")
    } else {
        CodeBox("كود الاقتران الحالي", secret, "laptop_secret")
        VSpace(8.dp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onRegenerate) { Text("إعادة توليد (يلغي الاقتران القديم)") }
        }
    }
}
