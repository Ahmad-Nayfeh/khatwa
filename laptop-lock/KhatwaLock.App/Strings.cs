namespace KhatwaLock.App;

/// <summary>All user-visible text, Arabic and English. Selected by config.language.</summary>
internal sealed class Strings
{
    public static Strings For(bool arabic) => arabic ? Ar : En;

    public bool Arabic { get; init; }
    public string AppTitle { get; init; } = "";
    public string LanguageToggle { get; init; } = "";

    // main window
    public string PairTitle { get; init; } = "";
    public string PairHint { get; init; } = "";
    public string PairPlaceholder { get; init; } = "";
    public string PairButton { get; init; } = "";
    public string PairBad { get; init; } = "";
    public string PairedTitle { get; init; } = "";
    public string LockHint { get; init; } = "";
    public string LockPlaceholder { get; init; } = "";
    public string LockButton { get; init; } = "";
    public string LockBad { get; init; } = "";
    public string LockOk { get; init; } = "";
    public string Unpair { get; init; } = "";
    public string UnpairConfirm { get; init; } = "";
    public string StartupOn { get; init; } = "";
    public string StartupOff { get; init; } = "";
    public string ShowLog { get; init; } = "";
    public string Footer { get; init; } = "";
    public string LockedNowTitle { get; init; } = "";
    public string LockedNowHint { get; init; } = "";
    public string OpenLockScreen { get; init; } = "";

    // lock screen
    public string LockScreenTitle { get; init; } = "";
    public string LockScreenSubtitle { get; init; } = "";
    public string UnlockButton { get; init; } = "";
    public string UnlockBad { get; init; } = "";
    public string UnlockBadMany { get; init; } = "";
    public string UnlockOk { get; init; } = "";
    public string Emergency { get; init; } = "";
    public string EmergencyWait { get; init; } = "";
    public string EmergencyType { get; init; } = "";
    public string Continue { get; init; } = "";
    public string ConfirmSurrender { get; init; } = "";
    public string BackToWalk { get; init; } = "";
    public string LockFooter { get; init; } = "";

    // log window
    public string LogTitle { get; init; } = "";
    public string LogEmpty { get; init; } = "";
    public string LogCount { get; init; } = "";
    public string LogDate { get; init; } = "";
    public string LogTime { get; init; } = "";
    public string LogNote { get; init; } = "";

    public static readonly Strings Ar = new()
    {
        Arabic = true,
        AppTitle = "خطوة · قفل اللابتوب",
        LanguageToggle = "English",
        PairTitle = "اقتران مع الجوال (مرة واحدة)",
        PairHint = "في تطبيق خطوة على الجوال: عند أول قفل اضغط «اقتران اللابتوب»، أو من الإعدادات ← قفل اللابتوب. أدخل كود الاقتران هنا.",
        PairPlaceholder = "XXXX-XXXX-XXXX-XXXX",
        PairButton = "اقتران",
        PairBad = "كود الاقتران غير صحيح. يتكون من 16 حرفاً ورقماً.",
        PairedTitle = "اللابتوب مقترن بجوالك",
        LockHint = "اقفل جوالك من تطبيق خطوة، ثم الصق «كود قفل اللابتوب» الذي يظهر في الرئيسية.",
        LockPlaceholder = "XXXX-XXXX",
        LockButton = "قفل اللابتوب",
        LockBad = "الكود لا يطابق جوالك. تأكد أنك نسخته من بطاقة القفل النشط في الرئيسية.",
        LockOk = "الكود صحيح. يُقفل اللابتوب الآن.",
        Unpair = "إلغاء الاقتران",
        UnpairConfirm = "إلغاء الاقتران؟ ستحتاج كود اقتران جديد من الجوال.",
        StartupOn = "يعمل عند تسجيل الدخول: نعم",
        StartupOff = "يعمل عند تسجيل الدخول: لا (اضغط للتفعيل)",
        ShowLog = "سجل الاستسلامات",
        Footer = "بلا شبكة ولا سيرفر. مفتوح المصدر.",
        LockedNowTitle = "اللابتوب مقفول الآن",
        LockedNowHint = "أكمل تحدي المشي على الجوال، ثم أدخل كود الفتح في شاشة القفل.",
        OpenLockScreen = "إظهار شاشة القفل",
        LockScreenTitle = "امشِ ليُفتح اللابتوب",
        LockScreenSubtitle = "عند اكتمال التحدي يعرض الجوال «كود فتح اللابتوب». الصقه هنا.",
        UnlockButton = "فتح",
        UnlockBad = "الكود غير صحيح.",
        UnlockBadMany = "الكود غير صحيح ({0}). كود الفتح يظهر في الرئيسية بعد انتهاء التحدي.",
        UnlockOk = "أحسنت. اللابتوب مفتوح.",
        Emergency = "طوارئ / إلغاء القفل",
        EmergencyWait = "انتظر {0} ثانية. سيُسجَّل هذا الإلغاء باسم «استسلام» بالتاريخ والوقت.\nخذ نفساً. ربما تفضّل المشي.",
        EmergencyType = "اكتب الجملة التالية حرفياً:\n«{0}»",
        Continue = "متابعة",
        ConfirmSurrender = "تأكيد الاستسلام (يُسجَّل)",
        BackToWalk = "رجوع، سأمشي",
        LockFooter = "خطوة · قفل اللابتوب حتى تمشي · لا شبكة، لا سيرفر",
        LogTitle = "خطوة · سجل الاستسلامات",
        LogEmpty = "لا توجد استسلامات مسجّلة.",
        LogCount = "عدد الاستسلامات: {0}",
        LogDate = "التاريخ",
        LogTime = "الوقت",
        LogNote = "ملاحظة",
    };

    public static readonly Strings En = new()
    {
        Arabic = false,
        AppTitle = "khatwa · Laptop lock",
        LanguageToggle = "العربية",
        PairTitle = "Pair with your phone (once)",
        PairHint = "In the khatwa app on your phone: tap \"Pair laptop\" on the first lock, or Settings → Laptop lock. Enter the pairing code here.",
        PairPlaceholder = "XXXX-XXXX-XXXX-XXXX",
        PairButton = "Pair",
        PairBad = "That pairing code is not valid. It has 16 letters and digits.",
        PairedTitle = "This laptop is paired with your phone",
        LockHint = "Lock your phone in the khatwa app, then paste the \"laptop lock code\" shown on its home screen.",
        LockPlaceholder = "XXXX-XXXX",
        LockButton = "Lock this laptop",
        LockBad = "That code does not match your phone. Copy it from the active-lock card on the home screen.",
        LockOk = "Code accepted. Locking now.",
        Unpair = "Unpair",
        UnpairConfirm = "Unpair? You will need a new pairing code from the phone.",
        StartupOn = "Runs at sign-in: yes",
        StartupOff = "Runs at sign-in: no (click to enable)",
        ShowLog = "Surrender log",
        Footer = "No network, no server. Open source.",
        LockedNowTitle = "This laptop is locked",
        LockedNowHint = "Finish the walking challenge on your phone, then enter the unlock code on the lock screen.",
        OpenLockScreen = "Show lock screen",
        LockScreenTitle = "Walk to unlock this laptop",
        LockScreenSubtitle = "When the challenge is complete, the phone shows the \"laptop unlock code\". Paste it here.",
        UnlockButton = "Unlock",
        UnlockBad = "Wrong code.",
        UnlockBadMany = "Wrong code ({0}). The unlock code appears on the phone's home screen when the challenge ends.",
        UnlockOk = "Well done. The laptop is unlocked.",
        Emergency = "Emergency / cancel lock",
        EmergencyWait = "Wait {0} seconds. This cancellation will be recorded as a surrender with date and time.\nTake a breath. You might prefer to walk.",
        EmergencyType = "Type the following sentence exactly:\n\"{0}\"",
        Continue = "Continue",
        ConfirmSurrender = "Confirm surrender (recorded)",
        BackToWalk = "Back, I'll walk",
        LockFooter = "khatwa · locked until you walk · no network, no server",
        LogTitle = "khatwa · Surrender log",
        LogEmpty = "No surrenders recorded.",
        LogCount = "Surrenders: {0}",
        LogDate = "Date",
        LogTime = "Time",
        LogNote = "Note",
    };
}
