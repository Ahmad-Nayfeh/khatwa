package com.khatwa.app.ui.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.khatwa.app.AppContainer
import com.khatwa.app.ui.components.CardTone
import com.khatwa.app.ui.components.KCard
import com.khatwa.app.ui.components.Muted
import com.khatwa.app.ui.components.PrimaryButton
import com.khatwa.app.ui.components.SectionTitle
import com.khatwa.app.ui.components.VSpace
import com.khatwa.app.ui.lock.PairingStatus
import com.khatwa.core.laptop.LaptopCode
import kotlinx.coroutines.launch

/** Settings ← laptop lock: only the one-time pairing lives here; the codes are on the Home tab. */
@Composable
fun LaptopLockScreen(container: AppContainer, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val settings by container.settings.flow.collectAsStateWithLifecycle(initialValue = null)
    var confirmRegenerate by remember { mutableStateOf(false) }
    val secret = settings?.laptopSecret

    SubScreen(title = "قفل اللابتوب", onBack = onBack) {
        KCard(tone = CardTone.Soft) {
            SectionTitle("كيف يعمل")
            Text("1. اقرن اللابتوب مرة واحدة بكود الاقتران أدناه (يطلبه برنامج اللابتوب عند أول فتح).\n2. عند قفل الجوال من الرئيسية يظهر «كود قفل اللابتوب»: الصقه في برنامج اللابتوب فيُقفل.\n3. عند اكتمال التحدي يظهر «كود فتح اللابتوب»: الصقه فيُفتح.\nكل ذلك بلا شبكة ولا بلوتوث.")
        }
        VSpace()
        KCard {
            SectionTitle("الاقتران")
            PairingStatus(container, secret) { confirmRegenerate = true }
            if (secret == null) {
                VSpace(8.dp)
                PrimaryButton("توليد كود الاقتران", Modifier.testTag("laptop_generate")) {
                    scope.launch { container.settings.setLaptopSecret(LaptopCode.generateSecret()) }
                }
            }
            VSpace(8.dp)
            Muted("كود الاقتران هو السر المشترك بين الجهازين. لا تشاركه مع أحد.")
        }
    }

    if (confirmRegenerate) {
        AlertDialog(
            onDismissRequest = { confirmRegenerate = false },
            title = { Text("إعادة توليد كود الاقتران؟") },
            text = { Text("اللابتوب المقترن حالياً سيتوقف عن قبول الأكواد حتى تعيد اقترانه بالكود الجديد.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { container.settings.setLaptopSecret(LaptopCode.generateSecret()) }
                    confirmRegenerate = false
                }) { Text("إعادة التوليد") }
            },
            dismissButton = { TextButton(onClick = { confirmRegenerate = false }) { Text("إلغاء") } },
        )
    }
}
