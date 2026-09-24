package com.khatwa.app.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.khatwa.core.goal.GoalConfig
import com.khatwa.core.lock.ScheduleConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalDate

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "khatwa_settings")

/** Typed snapshot of every user setting. */
data class Settings(
    val onboardingDone: Boolean = false,
    val goalStartDate: LocalDate? = null,
    val tempGoal: Int = 3000,
    val finalGoal: Int = 8000,
    val weeklyIncrement: Int = 500,
    val manualGoal: Int? = null,
    val schedule: ScheduleConfig = ScheduleConfig(),
    val blockSettings: Boolean = true,
    val allowlist: Set<String> = emptySet(),
    val allowlistInitialized: Boolean = false,
    val laptopSecret: String? = null,
    /** Counter of the last laptop challenge handed out with the current pairing (0 = none yet). */
    val laptopCounter: Long = 0,
    val weightReminderEnabled: Boolean = false,
    val weightReminderDay: Int = 6, // ISO day of week, Saturday
    val weightReminderMinute: Int = 9 * 60,
    val morningEnabled: Boolean = false,
    val morningMinute: Int = 7 * 60,
    /** "system" (follow the device), "light" or "dark". See [ThemeMode]. */
    val themeMode: String = ThemeMode.SYSTEM,
    /** App language: "ar" or "en". Also selects which quotes are shown. */
    val language: String = AppLanguage.AR,
    val lockStateJson: String? = null,
    /** Current / last laptop challenge (id + lifecycle) as JSON; see LockController. */
    val laptopChallengeJson: String? = null,
    val quoteOverrideDate: String? = null,
    val quoteOverrideIndex: Int = -1,
    /** Show the "quote of the day" card on Home. */
    val showQuote: Boolean = true,
    /** Bundled quote set version last seeded into the database (0 = never). */
    val quotesSeedVersion: Int = 0,
    val emergencyPhrase: String = DEFAULT_EMERGENCY_PHRASE,
    /** Date (ISO) on which a scheduled lock was surrendered; it is not re-armed that day. */
    val scheduleSkipDate: String? = null,
    /** Groups & competition (Firebase) opt-in. Off by default: nothing leaves the phone. */
    val groupsEnabled: Boolean = false,
    /** Nickname shown to group members. */
    val groupsNickname: String = "",
    /** Last Firebase anonymous uid seen (informational: shown in settings and kept in backups). */
    val groupsUid: String? = null,
    /** When this phone last saved its data to the signed-in account (0: never). */
    val cloudBackupAtMs: Long = 0,
) {
    /** The laptop pairing code in the current format (8 digits), or null (unpaired, or an old 16-character pairing). */
    val laptopPairing: String?
        get() = laptopSecret?.takeIf { com.khatwa.core.laptop.LaptopCode.isSecret(it) }?.let { com.khatwa.core.laptop.LaptopCode.normalize(it) }

    fun goalConfig(today: LocalDate): GoalConfig = GoalConfig(
        startDate = goalStartDate ?: today,
        tempGoal = tempGoal,
        finalGoal = finalGoal,
        weeklyIncrement = weeklyIncrement,
        manualGoal = manualGoal,
    )

    /**
     * The phrase to type for an emergency unlock. Unless the user stored a custom phrase, it is the
     * long sentence of the current app language (Arabic or English).
     */
    val effectiveEmergencyPhrase: String
        get() = if (emergencyPhrase == DEFAULT_EMERGENCY_PHRASE || emergencyPhrase in OLD_DEFAULT_PHRASES || emergencyPhrase.isBlank()) {
            com.khatwa.app.i18n.I18n.of(language).defaultEmergencyPhrase
        } else emergencyPhrase

    companion object {
        const val DEFAULT_EMERGENCY_PHRASE = "أختار الاستسلام اليوم بدلا من المشي، وأعلم أن هذا يسجل علي، وأعد نفسي أن أحاول من جديد غدا"
        /** Earlier, shorter defaults (e.g. from an old backup): replaced by the current one. */
        val OLD_DEFAULT_PHRASES = setOf(
            "أختار الاستسلام اليوم وأعلم أن هذا يسجل",
            "أختار الاستسلام اليوم وأعلم أن هذا يُسجَّل",
            "I choose to give up today and I know this is recorded",
        )
    }
}

/** App language stored in settings. */
object AppLanguage {
    const val AR = "ar"
    const val EN = "en"
    fun normalize(v: String?): String = if (v == EN) EN else AR
}

/** Theme choice stored in settings. */
object ThemeMode {
    const val SYSTEM = "system"
    const val LIGHT = "light"
    const val DARK = "dark"
    val ALL = listOf(SYSTEM, LIGHT, DARK)
    fun normalize(v: String?): String = if (v in ALL) v!! else SYSTEM
}

class SettingsRepository(private val context: Context) {
    private val store get() = context.settingsStore

    val flow: Flow<Settings> = store.data.map { it.toSettings() }

    suspend fun current(): Settings = flow.first()

    suspend fun edit(block: (MutablePreferences) -> Unit) {
        store.edit { block(it) }
    }

    suspend fun clear() {
        store.edit { it.clear() }
    }

    // ---- convenience setters used by the UI ----
    suspend fun setOnboardingDone(startDate: LocalDate) = edit {
        it[K.onboardingDone] = true
        if (it[K.goalStartDate] == null) it[K.goalStartDate] = startDate.toString()
    }

    suspend fun setGoals(temp: Int?, final: Int?, increment: Int?, manual: Int?, clearManual: Boolean = false) = edit {
        temp?.let { v -> it[K.tempGoal] = v }
        final?.let { v -> it[K.finalGoal] = v }
        increment?.let { v -> it[K.weeklyIncrement] = v }
        manual?.let { v -> it[K.manualGoal] = v }
        if (clearManual) it.remove(K.manualGoal)
    }

    suspend fun setSchedule(s: ScheduleConfig) = edit {
        it[K.scheduleEnabled] = s.enabled
        it[K.scheduleStart] = s.startMinute
        it[K.scheduleEnd] = s.endMinute
        it[K.scheduleDays] = s.days.map { d -> d.toString() }.toSet()
    }

    suspend fun setBlockSettings(v: Boolean) = edit { it[K.blockSettings] = v }

    suspend fun setAllowlist(pkgs: Set<String>) = edit {
        it[K.allowlist] = pkgs
        it[K.allowlistInitialized] = true
    }

    suspend fun setLaptopCounter(v: Long) = edit { it[K.laptopCounter] = v }

    suspend fun setLaptopSecret(secret: String?) = edit {
        // A new pairing restarts the challenge counter (the laptop starts from 0 too).
        it[K.laptopCounter] = 0L
        if (secret == null) it.remove(K.laptopSecret) else it[K.laptopSecret] = secret
    }

    suspend fun setWeightReminder(enabled: Boolean, day: Int, minute: Int) = edit {
        it[K.weightReminderEnabled] = enabled
        it[K.weightReminderDay] = day
        it[K.weightReminderMinute] = minute
    }

    suspend fun setMorning(enabled: Boolean, minute: Int) = edit {
        it[K.morningEnabled] = enabled
        it[K.morningMinute] = minute
    }

    suspend fun setThemeMode(v: String) = edit { it[K.themeMode] = ThemeMode.normalize(v) }

    suspend fun setLanguage(v: String) = edit { it[K.language] = AppLanguage.normalize(v) }

    suspend fun setGroupsEnabled(v: Boolean) = edit { it[K.groupsEnabled] = v }

    suspend fun setGroupsNickname(v: String) = edit { it[K.groupsNickname] = v.trim().take(24) }

    suspend fun setGroupsUid(v: String?) = edit { if (v == null) it.remove(K.groupsUid) else it[K.groupsUid] = v }
    suspend fun setCloudBackupAt(v: Long) = edit { it[K.cloudBackupAt] = v }

    suspend fun setShowQuote(v: Boolean) = edit { it[K.showQuote] = v }

    suspend fun setQuotesSeedVersion(v: Int) = edit { it[K.quotesSeedVersion] = v }

    suspend fun setLockStateJson(json: String?) = edit {
        if (json == null) it.remove(K.lockState) else it[K.lockState] = json
    }

    suspend fun setLaptopChallengeJson(json: String?) = edit {
        if (json == null) it.remove(K.laptopChallenge) else it[K.laptopChallenge] = json
    }

    suspend fun setScheduleSkipDate(date: String?) = edit {
        if (date == null) it.remove(K.scheduleSkipDate) else it[K.scheduleSkipDate] = date
    }

    suspend fun setEmergencyPhrase(phrase: String) = edit { it[K.emergencyPhrase] = phrase }

    suspend fun setQuoteOverride(date: String?, index: Int) = edit {
        if (date == null) { it.remove(K.quoteOverrideDate); it.remove(K.quoteOverrideIndex) }
        else { it[K.quoteOverrideDate] = date; it[K.quoteOverrideIndex] = index }
    }

    /** Full settings map for backup (strings only). */
    suspend fun exportMap(): Map<String, String> {
        val prefs = store.data.first()
        return prefs.asMap().entries.associate { (k, v) ->
            k.name to when (v) {
                is Set<*> -> v.joinToString(",")
                else -> v.toString()
            }
        }
    }

    /** Restore from a backup map produced by [exportMap]. Unknown keys are ignored. */
    suspend fun importMap(map: Map<String, String>) = edit { p ->
        p.clear()
        for ((name, value) in map) {
            when (name) {
                K.onboardingDone.name, K.scheduleEnabled.name, K.blockSettings.name, K.allowlistInitialized.name,
                K.weightReminderEnabled.name, K.morningEnabled.name, K.showQuote.name, K.groupsEnabled.name ->
                    p[booleanPreferencesKey(name)] = value.toBooleanStrictOrNull() ?: false
                K.themeMode.name -> p[K.themeMode] = ThemeMode.normalize(value)
                K.language.name -> p[K.language] = AppLanguage.normalize(value)
                K.tempGoal.name, K.finalGoal.name, K.weeklyIncrement.name, K.manualGoal.name, K.scheduleStart.name,
                K.scheduleEnd.name, K.weightReminderDay.name, K.weightReminderMinute.name, K.morningMinute.name,
                K.quoteOverrideIndex.name, K.quotesSeedVersion.name ->
                    value.toIntOrNull()?.let { p[intPreferencesKey(name)] = it }
                K.allowlist.name, K.scheduleDays.name ->
                    p[stringSetPreferencesKey(name)] = value.split(",").filter { it.isNotBlank() }.toSet()
                K.laptopCounter.name -> value.toLongOrNull()?.let { p[K.laptopCounter] = it }
                K.cloudBackupAt.name -> value.toLongOrNull()?.let { p[K.cloudBackupAt] = it }
                K.goalStartDate.name, K.laptopSecret.name, K.lockState.name, K.laptopChallenge.name, K.quoteOverrideDate.name,
                K.emergencyPhrase.name, K.scheduleSkipDate.name, K.groupsNickname.name, K.groupsUid.name ->
                    p[stringPreferencesKey(name)] = value
            }
        }
    }

    object K {
        val onboardingDone = booleanPreferencesKey("onboarding_done")
        val goalStartDate = stringPreferencesKey("goal_start_date")
        val tempGoal = intPreferencesKey("temp_goal")
        val finalGoal = intPreferencesKey("final_goal")
        val weeklyIncrement = intPreferencesKey("weekly_increment")
        val manualGoal = intPreferencesKey("manual_goal")
        val scheduleEnabled = booleanPreferencesKey("schedule_enabled")
        val scheduleStart = intPreferencesKey("schedule_start")
        val scheduleEnd = intPreferencesKey("schedule_end")
        val scheduleDays = stringSetPreferencesKey("schedule_days")
        val blockSettings = booleanPreferencesKey("block_settings")
        val allowlist = stringSetPreferencesKey("allowlist")
        val allowlistInitialized = booleanPreferencesKey("allowlist_initialized")
        val laptopSecret = stringPreferencesKey("laptop_secret")
        val laptopCounter = longPreferencesKey("laptop_counter")
        val weightReminderEnabled = booleanPreferencesKey("weight_reminder_enabled")
        val weightReminderDay = intPreferencesKey("weight_reminder_day")
        val weightReminderMinute = intPreferencesKey("weight_reminder_minute")
        val morningEnabled = booleanPreferencesKey("morning_enabled")
        val morningMinute = intPreferencesKey("morning_minute")
        val themeMode = stringPreferencesKey("theme_mode")
        val language = stringPreferencesKey("language")
        val lockState = stringPreferencesKey("lock_state")
        val laptopChallenge = stringPreferencesKey("laptop_challenge")
        val quoteOverrideDate = stringPreferencesKey("quote_override_date")
        val quoteOverrideIndex = intPreferencesKey("quote_override_index")
        val showQuote = booleanPreferencesKey("show_quote")
        val quotesSeedVersion = intPreferencesKey("quotes_seed_version")
        val emergencyPhrase = stringPreferencesKey("emergency_phrase")
        val scheduleSkipDate = stringPreferencesKey("schedule_skip_date")
        val groupsEnabled = booleanPreferencesKey("groups_enabled")
        val groupsNickname = stringPreferencesKey("groups_nickname")
        val groupsUid = stringPreferencesKey("groups_uid")
        val cloudBackupAt = longPreferencesKey("cloud_backup_at")
    }

    private fun Preferences.toSettings(): Settings {
        val defaults = Settings()
        return Settings(
            onboardingDone = this[K.onboardingDone] ?: false,
            goalStartDate = this[K.goalStartDate]?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            tempGoal = this[K.tempGoal] ?: defaults.tempGoal,
            finalGoal = this[K.finalGoal] ?: defaults.finalGoal,
            weeklyIncrement = this[K.weeklyIncrement] ?: defaults.weeklyIncrement,
            manualGoal = this[K.manualGoal],
            schedule = ScheduleConfig(
                enabled = this[K.scheduleEnabled] ?: false,
                startMinute = this[K.scheduleStart] ?: 20 * 60,
                endMinute = this[K.scheduleEnd] ?: 23 * 60,
                days = this[K.scheduleDays]?.mapNotNull { it.toIntOrNull() }?.toSet() ?: setOf(1, 2, 3, 4, 5, 6, 7),
            ),
            blockSettings = this[K.blockSettings] ?: true,
            allowlist = this[K.allowlist] ?: emptySet(),
            allowlistInitialized = this[K.allowlistInitialized] ?: false,
            laptopSecret = this[K.laptopSecret],
            laptopCounter = this[K.laptopCounter] ?: 0L,
            weightReminderEnabled = this[K.weightReminderEnabled] ?: false,
            weightReminderDay = this[K.weightReminderDay] ?: defaults.weightReminderDay,
            weightReminderMinute = this[K.weightReminderMinute] ?: defaults.weightReminderMinute,
            morningEnabled = this[K.morningEnabled] ?: false,
            morningMinute = this[K.morningMinute] ?: defaults.morningMinute,
            themeMode = ThemeMode.normalize(this[K.themeMode]),
            language = AppLanguage.normalize(this[K.language]),
            lockStateJson = this[K.lockState],
            laptopChallengeJson = this[K.laptopChallenge],
            quoteOverrideDate = this[K.quoteOverrideDate],
            quoteOverrideIndex = this[K.quoteOverrideIndex] ?: -1,
            showQuote = this[K.showQuote] ?: true,
            quotesSeedVersion = this[K.quotesSeedVersion] ?: 0,
            emergencyPhrase = this[K.emergencyPhrase] ?: Settings.DEFAULT_EMERGENCY_PHRASE,
            scheduleSkipDate = this[K.scheduleSkipDate],
            groupsEnabled = this[K.groupsEnabled] ?: false,
            groupsNickname = this[K.groupsNickname] ?: "",
            groupsUid = this[K.groupsUid],
            cloudBackupAtMs = this[K.cloudBackupAt] ?: 0L,
        )
    }
}
