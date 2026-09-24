package com.khatwa.app.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import com.khatwa.app.settings.AppLanguage

/**
 * Every user-visible string of the phone app, in Arabic ([Ar]) and English ([En]).
 *
 * Composables read [strings] (backed by [LocalStrings], provided by the theme from the language
 * setting). Non-composable code (notifications, alarms, services) reads [I18n.current], which the
 * container keeps in sync with the same setting.
 */
interface Strings {
    val code: String
    val rtl: Boolean

    // Common
    val appName: String
    val back: String
    val save: String
    val cancel: String
    val delete: String
    val edit: String
    val done: String
    val open: String
    val grant: String
    val search: String
    val export: String
    val import_: String
    val steps: String
    val step: String
    val emergencyTitle: String
    fun daysCount(n: String): String
    fun stepsCount(n: String): String
    val openAppSettings: String
    val openAccessibilitySettings: String
    val grantOverlay: String
    val enabledCheck: String
    val grantedCheck: String

    // Time / dates
    val am: String
    val pm: String
    fun minutesShort(min: Long): String
    fun hoursMinutesShort(h: Long, m: Long): String
    val dayNames: Map<Int, String>
    val dayNamesShort: Map<Int, String>
    val monthNames: List<String>
    val listSeparator: String
    val today: String

    // Tabs
    val tabHome: String
    val tabStats: String
    val tabSettings: String

    // Notifications & alarms
    val channelServiceDescription: String
    val channelEventsDescription: String
    val goalDoneToday: String
    fun remainingSteps(n: String): String
    fun stepsOfGoal(steps: String, goal: String): String
    val weightReminderTitle: String
    val weightReminderText: String
    fun morningTitle(yesterday: String): String
    fun lockWarnTitle(minutes: Int): String
    fun lockWarnText(remaining: String, time: String): String
    val scheduledLockStartedTitle: String
    val scheduledLockStartedText: String
    val unlockedTitle: String
    val unlockedText: String
    fun restoreSummary(days: Int, sessions: Int, weights: Int, quotes: Int): String
    val defaultEmergencyPhrase: String

    // Home
    val vsLastWeek: String
    val countingStoppedNoPermission: String
    val noSensor: String
    fun ofGoal(goal: String): String
    val quoteOfTheDay: String
    val anotherQuote: String
    val thisWeek: String
    val sessionsThisWeek: String
    val weekCompareHint: String
    val currentStreak: String
    val longestStreak: String
    val effectiveGoal: String
    val goal: String
    val week: String
    val manualFixed: String
    val measurementWeek: String
    val finalGoal: String
    val counterRunning: String
    val sensorReadingArrived: String
    val sensorWaiting: String
    val restartCounter: String

    // Lock section / overlay / emergency
    val lockNotWorking: String
    val accessibilityOff: String
    val overlayOff: String
    val fullStepsHint: String
    val lockActive: String
    fun manualLockShort(target: String): String
    val scheduledLockShort: String
    val lockMeUntilIWalk: String
    val lockMeHint: String
    val customCount: String
    val stepCount: String
    val lock: String
    val walkToUnlock: String
    val stepsRemaining: String
    fun manualLockLong(target: String): String
    fun scheduledLockLong(goal: String, time: String): String
    val openAllowedApps: String
    fun emergencyWarning(remaining: String): String
    fun waitSeconds(n: Int): String
    val breathe: String
    val typePhrase: String
    val continue_: String
    val sureSurrender: String
    val confirmSurrender: String
    val backIWillWalk: String

    // Laptop codes
    val copy: String
    val copied: String
    val pairLaptopHint: String
    val pairLaptop: String
    val laptopLockCodeLabel: String
    val challengeEndedSurrender: String
    val challengeDone: String
    val laptopUnlockCodeLabel: String
    val hideCode: String
    val pairingDialogText: String
    val pairingCode: String
    val notPairedYet: String
    val currentPairingCode: String
    val regeneratePairing: String
    val laptopLock: String
    val howItWorks: String
    val laptopHowItWorksText: String
    val pairing: String
    val generatePairingCode: String
    val pairingSecretHint: String
    val regeneratePairingQuestion: String
    val regeneratePairingWarning: String
    val regenerate: String

    // Onboarding
    val previous: String
    val start: String
    val next: String
    val welcomeTitle: String
    val welcomeText1: String
    val welcomeText2: String
    val importantAssumption: String
    val assumptionText: String
    val screensExplain: String
    val stepPermissionTitle: String
    val stepPermissionText: String
    val noSensorLong: String
    val permissionGrantedCheck: String
    val grantPermission: String
    val permissionFallbackHint: String
    val quietNotificationTitle: String
    val quietNotificationText: String
    val notificationsOnCheck: String
    val allowNotifications: String
    val openNotificationSettings: String
    val progressiveGoalTitle: String
    val progressiveGoalText: String
    val tempGoalLabel: String
    val weeklyIncrement: String
    val phoneLockOptional: String
    val phoneLockNeedsTwo: String
    val accessibilityServiceNumbered: String
    val accessibilityServiceText: String
    val restrictedSettingsHint: String
    val overlayNumbered: String
    val overlayText: String
    val battery: String
    val batteryText: String
    val batteryExemptCheck: String
    val batteryExempt: String
    val samsungToo: String
    val samsungBatteryText: String
    val ready: String
    val readyText: String
    val readyHint: String

    // Settings root
    val settings: String
    val goalSubtitle: String
    val scheduledLock: String
    val scheduledLockSubtitle: String
    val allowlist: String
    val allowlistSubtitle: String
    val laptopLockSubtitle: String
    val weightLog: String
    val weightLogSubtitle: String
    val quotes: String
    val quotesSubtitle: String
    val backup: String
    val backupSubtitle: String
    val permissionsStatus: String
    val permissionsSubtitle: String
    val notificationsAndAppearance: String
    val showQuoteOnHome: String
    val morningNotification: String
    val morningNotificationTime: String
    val appearance: String
    val themeSystem: String
    val themeLight: String
    val themeDark: String
    val language: String
    val languageArabic: String
    val languageEnglish: String
    val footer: String

    // Allowlist
    val allowlistHint: String
    val blockSettingsDuringLock: String
    val alwaysAllowed: String
    val followsBlockSettings: String

    // Backup
    val backupSaved: String
    val exportHint: String
    val exportFull: String
    val importHint: String
    val chooseFileAndImport: String
    val clearAll: String
    val clearAllHint: String
    val replaceAllQuestion: String
    val replaceAllText: String
    fun importFailed(reason: String): String
    val invalidFile: String
    val clearAllQuestion: String
    val clearAllText: String
    val clear: String

    // Goal
    val statusNow: String
    val currentEffectiveGoal: String
    val mode: String
    val manualMode: String
    val reachedFinalGoal: String
    val progressive: String
    val startedOn: String
    val progression: String
    val progressionText: String
    val tempGoalMeasurement: String
    val manualEdit: String
    val manualEditHint: String
    val manualGoal: String
    val fixGoalStopProgression: String
    val updateManualGoal: String
    val backToProgression: String

    // Permissions
    val stepSensor: String
    val stepSensorText: String
    val physicalActivity: String
    val physicalActivityText: String
    val notifications: String
    val notificationsText: String
    val accessibilityService: String
    val accessibilityServiceShort: String
    val overlay: String
    val overlayShort: String
    val batteryExemption: String
    val batteryExemptionText: String
    val counterRuns: String
    val counterRunsText: String
    val run: String
    val restrictedSettings: String
    val restrictedSettingsText: String
    val restrictedSettingsSteps: String
    val samsung: String
    val samsungText: String

    // Quotes
    fun exportedQuotes(n: Int): String
    fun importedQuotes(n: Int): String
    val quotesImportFailed: String
    fun quotesHint(n: Int): String
    val add: String
    fun restoredQuotes(n: Int): String
    val restoreBundled: String
    val newQuote: String
    val editQuote: String

    // Scheduled lock
    val enableScheduledLock: String
    val scheduledLockHint: String
    val times: String
    val startTime: String
    val endTime: String
    fun endAfterStartHint(warn: Int): String
    val weekDays: String
    val summary: String
    fun scheduleSummary(start: String, end: String, days: String): String
    val scheduledLockOff: String

    // Weight
    val newEntry: String
    val weightHint: String
    val kg: String
    val trend: String
    val weeklyReminder: String
    val enableReminder: String
    val time: String
    val log: String
    val noEntries: String
    fun kgValue(v: String): String

    // Stats
    val statistics: String
    val swipeHint: String
    val dailyAverage: String
    val bestDay: String
    val goalDays: String
    val last30Days: String
    fun bestDayLine(date: String): String
    val walkingSessions: String
    val sessionDefinition: String
    val thisMonth: String
    val longestSessionThisMonth: String
    val averageSessionLength: String
    val last12Months: String
    val last12MonthsHint: String
    val hourDistribution: String
    val hourDistributionHint: String
    val surrendersThisMonth: String
    val emergencyCancelCount: String
    val surrenderHint: String
    val goalReachedCheck: String
    fun goalLabel(goal: String): String
    val ofGoalPct: String
    val sessions: String
    val longestSession: String
}

object I18n {
    /** Language used by non-composable code; kept in sync with the language setting. */
    @Volatile var current: Strings = Ar

    fun of(lang: String?): Strings = if (AppLanguage.normalize(lang) == AppLanguage.EN) En else Ar
}

val LocalStrings = staticCompositionLocalOf<Strings> { Ar }

val strings: Strings
    @Composable @ReadOnlyComposable get() = LocalStrings.current
