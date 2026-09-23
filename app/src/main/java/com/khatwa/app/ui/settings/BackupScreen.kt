package com.khatwa.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.khatwa.app.AppContainer
import com.khatwa.app.backup.Backup
import com.khatwa.app.ui.components.CardTone
import com.khatwa.app.ui.components.DangerButton
import com.khatwa.app.ui.components.KCard
import com.khatwa.app.ui.components.Muted
import com.khatwa.app.ui.components.PrimaryButton
import com.khatwa.app.ui.components.SecondaryButton
import com.khatwa.app.ui.components.SectionTitle
import com.khatwa.app.ui.components.VSpace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

@Composable
fun BackupScreen(container: AppContainer, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val backup = remember { Backup(container) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmImportUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = backup.export()
            withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } }
            message = "تم حفظ النسخة الاحتياطية."
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> confirmImportUri = uri }

    SubScreen(title = "النسخة الاحتياطية", onBack = onBack) {
        KCard {
            SectionTitle("تصدير")
            Muted("ملف JSON واحد يشمل الخطوات والجلسات واللقطات والوزن والاستسلامات والحكم وكل الإعدادات (بما فيها سر اللابتوب). احفظه في مكان آمن.")
            VSpace(8.dp)
            PrimaryButton("تصدير نسخة كاملة", Modifier.fillMaxWidth()) { exportLauncher.launch("khatwa-backup-${LocalDate.now()}.json") }
        }
        VSpace()
        KCard {
            SectionTitle("استيراد")
            Muted("يستبدل كل البيانات الحالية بمحتوى الملف.")
            VSpace(8.dp)
            SecondaryButton("اختيار ملف واستيراده", Modifier.fillMaxWidth()) { importLauncher.launch(arrayOf("application/json", "text/*", "*/*")) }
        }
        VSpace()
        KCard(tone = CardTone.Warning) {
            SectionTitle("مسح كل البيانات")
            Text("يحذف كل شيء ويعيد التطبيق إلى شاشة الترحيب. لا يمكن التراجع.")
            VSpace(8.dp)
            DangerButton("مسح كل البيانات", Modifier.fillMaxWidth()) { confirmClear = true }
        }
        message?.let { VSpace(); Muted(it) }
    }

    confirmImportUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { confirmImportUri = null },
            title = { Text("استبدال كل البيانات؟") },
            text = { Text("سيُستبدل كل ما في التطبيق بمحتوى الملف المختار.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmImportUri = null
                    scope.launch {
                        val text = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }
                        message = runCatching { backup.import(text ?: "") }.getOrElse { "تعذّر الاستيراد: ${it.message ?: "الملف غير صالح"}" }
                    }
                }) { Text("استيراد") }
            },
            dismissButton = { TextButton(onClick = { confirmImportUri = null }) { Text("إلغاء") } },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("مسح كل البيانات؟") },
            text = { Text("سيُحذف كل شيء نهائياً.") },
            confirmButton = { TextButton(onClick = { confirmClear = false; scope.launch { backup.clearAll() } }) { Text("مسح") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("إلغاء") } },
        )
    }
}
