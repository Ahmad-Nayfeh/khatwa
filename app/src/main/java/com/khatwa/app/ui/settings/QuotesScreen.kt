package com.khatwa.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.khatwa.app.AppContainer
import com.khatwa.app.data.QuoteEntity
import com.khatwa.app.i18n.strings
import com.khatwa.app.ui.components.Muted
import com.khatwa.app.ui.components.SecondaryButton
import com.khatwa.app.ui.components.VSpace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun QuotesScreen(container: AppContainer, onBack: () -> Unit) {
    val s = strings
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val settings by container.settings.flow.collectAsStateWithLifecycle(initialValue = null)
    val lang = settings?.language ?: com.khatwa.app.settings.AppLanguage.AR
    val quotes by remember(lang) { container.features.quotes.observeByLang(lang) }.collectAsStateWithLifecycle(initialValue = emptyList())
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<QuoteEntity?>(null) }
    var adding by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val json = container.features.quotes.exportJson()
            withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) } }
            message = s.exportedQuotes(quotes.size)
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }
            message = runCatching { s.importedQuotes(container.features.quotes.importJson(text ?: "")) }
                .getOrElse { s.quotesImportFailed }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = s.back) }
            Text(s.quotes, style = MaterialTheme.typography.headlineMedium)
        }
        Muted(s.quotesHint(quotes.size))
        VSpace(6.dp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(s.add, Modifier.weight(1f)) { adding = true }
            SecondaryButton(s.export, Modifier.weight(1f)) { exportLauncher.launch("khatwa-quotes.json") }
            SecondaryButton(s.import_, Modifier.weight(1f)) { importLauncher.launch(arrayOf("application/json", "text/*", "*/*")) }
        }
        TextButton(onClick = { scope.launch { message = s.restoredQuotes(container.features.quotes.restoreBundled()) } }) { Text(s.restoreBundled) }
        message?.let { Muted(it) }
        OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text(s.search) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        VSpace(6.dp)
        LazyColumn(Modifier.fillMaxSize()) {
            items(quotes.filter { query.isBlank() || it.text.contains(query) }, key = { it.id }) { q ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(q.text, style = MaterialTheme.typography.bodyLarge)
                        q.source?.let { Muted("— $it") }
                    }
                    TextButton(onClick = { editing = q }) { Text(s.edit) }
                }
            }
        }
    }

    if (adding || editing != null) {
        var text by remember(editing) { mutableStateOf(editing?.text ?: "") }
        AlertDialog(
            onDismissRequest = { adding = false; editing = null },
            title = { Text(if (adding) s.newQuote else s.editQuote) },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, minLines = 2, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(enabled = text.isNotBlank(), onClick = {
                    scope.launch {
                        if (adding) container.features.quotes.add(text, lang) else container.features.quotes.update(editing!!, text)
                        adding = false; editing = null
                    }
                }) { Text(s.save) }
            },
            dismissButton = {
                Row {
                    if (editing != null) TextButton(onClick = { scope.launch { container.features.quotes.delete(editing!!.id); editing = null } }) { Text(s.delete) }
                    TextButton(onClick = { adding = false; editing = null }) { Text(s.cancel) }
                }
            },
        )
    }
}
