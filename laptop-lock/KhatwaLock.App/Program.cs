using System.Diagnostics;
using KhatwaLock.Core;

namespace KhatwaLock.App;

/// <summary>
/// One exe, several modes:
///   (no args)     the main window (pair once; paste the lock code) — or the lock screen when locked
///   --watchdog    keeps the lock alive: shows the lock screen whenever the laptop is locked
///   --log         shows the surrender log window
///   --selftest    checks the codes against built-in vectors and exits 0/1 (used by CI)
///   --codes S ID  prints lock and unlock codes for secret S and challenge id ID (debugging)
///   --ui-smoke N  shows the lock form for N seconds and exits (CI smoke)
/// </summary>
internal static class Program
{
    private const string LockMutexName = @"Local\KhatwaLock.LockScreen";

    [STAThread]
    private static int Main(string[] args)
    {
        try
        {
            Directory.CreateDirectory(KhatwaPaths.BaseDir);
        }
        catch { /* best effort */ }

        if (args.Length > 0)
        {
            switch (args[0])
            {
                case "--selftest": return SelfTest();
                case "--codes": return PrintCodes(args);
                case "--watchdog": return Watchdog.Run();
                case "--log": return ShowLog();
                case "--ui-smoke": return UiSmoke(args);
                case "--version":
                    Console.WriteLine(typeof(Program).Assembly.GetName().Version);
                    return 0;
            }
        }
        return Gui();
    }

    private static int Gui()
    {
        var config = LockConfig.Load(KhatwaPaths.ConfigPath);
        var state = new LockStateStore(KhatwaPaths.StatePath);
        var surrenders = new SurrenderLog(KhatwaPaths.SurrendersPath);
        ApplicationConfiguration.Initialize();
        if (LockDecision.ShouldLock(config, state))
        {
            // Locked: go straight to the lock screen (a watchdog may already be showing it).
            if (!ShowLockScreen(config, state, surrenders)) return 0;
            config = LockConfig.Load(KhatwaPaths.ConfigPath);
        }
        Application.Run(new MainForm(config, state, surrenders));
        return 0;
    }

    /// <summary>Shows the lock screen modally; false when another instance already shows it.</summary>
    public static bool ShowLockScreen(LockConfig config, LockStateStore state, SurrenderLog surrenders)
    {
        using var mutex = new Mutex(true, LockMutexName, out var createdNew);
        if (!createdNew)
        {
            Log.Write("lock screen already running");
            return false;
        }
        Log.Write("showing lock screen");
        using var form = new LockForm(config, state, surrenders);
        form.ShowDialog();
        return true;
    }

    private static int ShowLog()
    {
        var config = LockConfig.Load(KhatwaPaths.ConfigPath);
        ApplicationConfiguration.Initialize();
        Application.Run(new LogForm(new SurrenderLog(KhatwaPaths.SurrendersPath), Strings.For(config.IsArabic)));
        return 0;
    }

    private static int UiSmoke(string[] args)
    {
        var seconds = args.Length > 1 && int.TryParse(args[1], out var s) ? s : 3;
        var config = new LockConfig { Secret = "12345678" };
        var tmp = Path.Combine(Path.GetTempPath(), "khatwa-ui-smoke");
        Directory.CreateDirectory(tmp);
        var state = new LockStateStore(Path.Combine(tmp, "state.json"));
        state.Clear();
        state.MarkLocked(1);
        ApplicationConfiguration.Initialize();
        var form = new LockForm(config, state, new SurrenderLog(Path.Combine(tmp, "surrenders.json")), smokeTest: true);
        var timer = new System.Windows.Forms.Timer { Interval = seconds * 1000 };
        timer.Tick += (_, _) => { timer.Stop(); form.ForceClose(); };
        form.Shown += (_, _) => timer.Start();
        Application.Run(form);
        Console.WriteLine($"ui-smoke: form shown for {seconds}s, wrong-code rejected={form.SmokeWrongRejected}, right-code accepted={form.SmokeRightAccepted}");
        return form.SmokeWrongRejected && form.SmokeRightAccepted ? 0 : 1;
    }

    private static int SelfTest()
    {
        // Some of the shared vectors (shared/hmac-vectors.json), kept in sync by the unit tests.
        var ok = ChallengeCodes.LockCode("12345678", 1) == "179578"
                 && ChallengeCodes.UnlockCode("12345678", 1) == "880387"
                 && ChallengeCodes.LockCode("31415926", 1000) == "060899"
                 && ChallengeCodes.UnlockCode("31415926", 1000) == "026260"
                 && ChallengeCodes.VerifyLockCode("12345678", "179 578", 0) == 1
                 && ChallengeCodes.VerifyLockCode("12345678", "١٧٩٥٧٨", 0) == 1
                 && ChallengeCodes.VerifyLockCode("12345678", "179579", 0) == null
                 && ChallengeCodes.NextCounter("12345678", 769) == 771;
        Console.WriteLine(ok ? "selftest OK" : "selftest FAILED");
        return ok ? 0 : 1;
    }

    private static int PrintCodes(string[] args)
    {
        if (args.Length < 3 || !long.TryParse(args[2], out var counter)) { Console.WriteLine("usage: --codes <secret> <counter>"); return 2; }
        Console.WriteLine("lock:   " + ChallengeCodes.Format(ChallengeCodes.LockCode(args[1], counter)));
        Console.WriteLine("unlock: " + ChallengeCodes.Format(ChallengeCodes.UnlockCode(args[1], counter)));
        return 0;
    }

    /// <summary>True when a lock-screen instance currently holds the mutex.</summary>
    internal static bool IsLockScreenRunning()
    {
        try
        {
            using var m = new Mutex(false, LockMutexName, out var createdNew);
            if (createdNew) return false; // nobody had it; we created (and will dispose) it
            return true;
        }
        catch { return false; }
    }

    internal static string ExePath => Environment.ProcessPath ?? Process.GetCurrentProcess().MainModule?.FileName ?? "KhatwaLock.exe";
}

internal static class Log
{
    private static readonly object Gate = new();

    public static void Write(string message)
    {
        try
        {
            lock (Gate)
            {
                Directory.CreateDirectory(KhatwaPaths.BaseDir);
                File.AppendAllText(KhatwaPaths.LogPath, $"{DateTime.Now:s} {message}{Environment.NewLine}");
            }
        }
        catch { /* logging must never break the lock */ }
    }
}
