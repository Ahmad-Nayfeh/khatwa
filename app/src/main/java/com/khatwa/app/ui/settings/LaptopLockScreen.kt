package com.khatwa.app.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.khatwa.app.AppContainer
import com.khatwa.app.ui.components.CardTone
import com.khatwa.app.ui.components.DangerButton
import com.khatwa.app.ui.components.KCard
import com.khatwa.app.ui.components.Muted
import com.khatwa.app.ui.components.PrimaryButton
import com.khatwa.app.ui.components.SecondaryButton
import com.khatwa.app.ui.components.SectionTitle
import com.khatwa.app.ui.components.VSpace
import com.khatwa.app.util.Fmt
import com.khatwa.core.laptop.LaptopCode
import kotlinx.coroutines.launch

@Composable
fun LaptopLockScreen(container: AppContainer, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val settings by container.settings.flow.collectAsStateWithLifecycle(initialValue = null)
    val today by container.tracker.today.collectAsStateWithLifecycle()
    var freshSecret by remember { mutableStateOf<String?>(null) }
    var confirmRegenerate by remember { mutableStateOf(false) }

    fun generate() {
        val s = LaptopCode.generateSecret()
        scope.launch { container.settings.setLaptopSecret(s) }
        freshSecret = s
    }

    SubScreen(title = "قفل اللابتوب", onBack = onBack) {
        KCard(tone = CardTone.Soft) {
            SectionTitle("كيف يعمل")
            Text("يولّد الجوال سراً عشوائياً تنسخه مرة واحدة إلى برنامج اللابتوب. كل يوم يُحسب كود من 6 أرقام من السر وتاريخ اليوم. الجوال يعرض الكود فقط بعد إكمال هدف اليوم، واللابتوب يطلبه عند تسجيل الدخول. لا شبكة ولا بلوتوث.")
        }
        VSpace()
        val secret = settings?.laptopSecret
        if (freshSecret != null) {
            KCard(tone = CardTone.Accent) {
                SectionTitle("السر الجديد (يظهر مرة واحدة)")
                Text(
                    freshSecret!!, style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 22.sp, letterSpacing = 2.sp),
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().testTag("laptop_secret"),
                )
                VSpace(8.dp)
                Muted("انسخه الآن إلى ملف إعدادات برنامج اللابتوب (config.json) كما يشرح README. لن يُعرض مرة أخرى؛ إن ضاع، أعد التوليد وحدّث اللابتوب.")
                VSpace(8.dp)
                PrimaryButton("نسخ إلى الحافظة", Modifier.fillMaxWidth()) {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("khatwa secret", freshSecret))
                }
                VSpace(6.dp)
                SecondaryButton("تم، أخفِ السر", Modifier.fillMaxWidth()) { freshSecret = null }
            }
            VSpace()
        }
        KCard {
            SectionTitle("الحالة")
            if (secret == null) {
                Muted("لم يُولَّد سر بعد.")
                VSpace(8.dp)
                PrimaryButton("توليد السر", Modifier.fillMaxWidth().testTag("laptop_generate")) { generate() }
            } else {
                Muted("السر مضبوط. طوله ${secret.length} حرفاً، آخر حرفين: …${secret.takeLast(2)}")
                VSpace(6.dp)
                val done = today.steps >= today.goal
                Text(
                    if (done) "كود اليوم: ${LaptopCode.code(secret, today.date)}" else "الكود يظهر بعد إكمال هدف اليوم (المتبقي ${Fmt.n((today.goal - today.steps).coerceAtLeast(0))}).",
                    style = MaterialTheme.typography.titleMedium,
                )
                VSpace(8.dp)
                DangerButton("إعادة توليد السر", Modifier.fillMaxWidth()) { confirmRegenerate = true }
            }
        }
    }

    if (confirmRegenerate) {
        AlertDialog(
            onDismissRequest = { confirmRegenerate = false },
            title = { Text("إعادة توليد السر؟") },
            text = { Text("السر القديم سيتوقف عن العمل. ستحتاج إلى نسخ السر الجديد إلى اللابتوب.") },
            confirmButton = { TextButton(onClick = { generate(); confirmRegenerate = false }) { Text("إعادة التوليد") } },
            dismissButton = { TextButton(onClick = { confirmRegenerate = false }) { Text("إلغاء") } },
        )
    }
}
