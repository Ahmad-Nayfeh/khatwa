package com.khatwa.app.backup

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.khatwa.app.AppContainer
import com.khatwa.app.KhatwaApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Private cloud copy of the phone's data for signed-in accounts: the same file as the manual
 * backup, gzipped, in users/{uid}/backup/latest (only its owner can read it; not even the admin).
 * Saved once a day and after signing in; restored after a reinstall or on a new phone.
 */
class CloudBackup(private val c: AppContainer) {

    private val _restoreOffer = MutableStateFlow<Long?>(null)
    /** Set when a signed-in phone that already has data finds a backup: the UI asks what to do. */
    val restoreOffer: StateFlow<Long?> = _restoreOffer

    private val _restored = MutableStateFlow<String?>(null)
    /** Summary of the last restore, shown once. */
    val restored: StateFlow<String?> = _restored

    /** Saves the phone's data now. False when nobody is signed in or saving failed. */
    suspend fun upload(): Boolean {
        if (!c.groups.configured || c.groups.account == null) return false
        return try {
            val data = encode()
            c.groups.saveBackup(data, data.length)
            c.settings.setCloudBackupAt(System.currentTimeMillis())
            Log.i(TAG, "saved (${data.length} chars)")
            true
        } catch (e: Exception) {
            Log.w(TAG, "save failed: ${e.message}")
            false
        }
    }

    /** When the account's backup was saved (null: none yet). */
    suspend fun savedAt(): Long? = c.groups.backupSavedAt()

    /**
     * Replaces the phone's data with the account's backup. Null when there is none. [duringSetup]:
     * first setup screen; setup continues and the step counter starts only after the permissions.
     */
    suspend fun restore(duringSetup: Boolean = false): String? {
        val (data, _) = c.groups.loadBackup() ?: return null
        val summary = Backup(c).import(decode(data), duringSetup)
        val s = c.settings.current()
        com.khatwa.app.groups.GroupsSync.schedule(c.app, s.onboardingDone && s.groupsEnabled && c.groups.configured)
        _restoreOffer.value = null
        _restored.value = summary
        Log.i(TAG, "restored")
        return summary
    }

    /**
     * After signing in: an account without a backup gets one now. With a backup: restored at once
     * during first-time setup ([fresh]), otherwise the phone asks (its own data would be replaced).
     */
    suspend fun afterSignIn(fresh: Boolean) {
        schedule(c.app, true)
        val saved = runCatching { savedAt() }.getOrNull()
        when {
            saved == null -> upload()
            fresh -> runCatching { restore(duringSetup = true) }.onFailure { Log.w(TAG, "restore failed: ${it.message}") }
            else -> _restoreOffer.value = saved
        }
    }

    /** "Keep this phone's data": the account's backup is replaced by this phone's data. */
    suspend fun keepPhoneData() {
        _restoreOffer.value = null
        upload()
    }

    fun clearRestored() { _restored.value = null }

    fun onSignedOut() {
        _restoreOffer.value = null
        schedule(c.app, false)
    }

    /** gzip + base64; if too big for one document, older step snapshots are left out. */
    private suspend fun encode(): String {
        val day = 24L * 60 * 60 * 1000
        for (since in listOf(0L, System.currentTimeMillis() - 90 * day, System.currentTimeMillis() - 30 * day, Long.MAX_VALUE)) {
            val out = ByteArrayOutputStream()
            GZIPOutputStream(out).use { it.write(Backup(c).export(pretty = false, snapshotsSinceMs = since).toByteArray(Charsets.UTF_8)) }
            val text = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            if (text.length <= MAX_CHARS) return text
        }
        throw IllegalStateException("backup too large")
    }

    private fun decode(data: String): String =
        GZIPInputStream(ByteArrayInputStream(Base64.decode(data, Base64.NO_WRAP))).use { it.readBytes().toString(Charsets.UTF_8) }

    companion object {
        private const val TAG = "CloudBackup"
        private const val WORK = "khatwa-cloud-backup"
        /** One Firestore document holds up to 1 MiB; the rules allow 1 000 000 characters. */
        const val MAX_CHARS = 900_000

        fun schedule(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) { wm.cancelUniqueWork(WORK); return }
            val req = PeriodicWorkRequestBuilder<CloudBackupWorker>(24, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}

class CloudBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = KhatwaApp.container(applicationContext)
        if (!c.settings.current().onboardingDone || c.groups.account == null) return Result.success()
        c.tracker.load()
        return if (c.cloud.upload()) Result.success() else Result.retry()
    }
}
