package com.khatwa.app.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast

/** Clipboard helper: copies text and shows a short confirmation on older Android versions. */
object Clip {
    fun copy(context: Context, label: String, text: String, toast: String? = null) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        // Android 13+ shows its own "copied" confirmation.
        if (toast != null && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
        }
    }
}
