using System.Diagnostics;
using KhatwaLock.Core;

namespace KhatwaLock.App;

/// <summary>
/// One exe, several modes:
///   (no args)     lock mode: shows the full-screen lock unless today is already unlocked
///   --watchdog    keeps the lock alive: restarts it if it is closed while still locked
///   --log         shows the surrender log window
///   --selftest    checks the HMAC against built-in vectors and exits 0/1 (used by CI)
///   --code S D    prints the code for secret S and ISO date D (debugging)
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
                case "--code": return PrintCode(args);
                case "--watchdog": return Watchdog.Run();
                case "--log": return ShowLog();
                case "--ui-smoke": return UiSmoke(args);
                case "--version":
                    Console.WriteLine(typeof(Program).Assembly.GetName().Version);
                    return 0;
            }
        }
        return LockMode();
    }

    private static int LockMode()
    {
        var config = LockConfig.Load(KhatwaPaths.ConfigPath);
        var state = new LockStateStore(KhatwaPaths.StatePath);
        var today = DateOnly.FromDateTime(DateTime.Now);
        if (!LockDecision.ShouldLock(config, state, today))
        {
            Log.Write(config.HasSecret ? "already unlocked today; exiting" : "no secret configured; exiting");
            return 0;
        }
        using var mutex = new Mutex(true, LockMutexName, out var createdNew);
        if (!createdNew)
        {
            Log.Write("lock screen already running; exiting");
            return 0;
        }
        Log.Write("showing lock screen");
        ApplicationConfiguration.Initialize();
        Application.Run(new LockForm(config, state, new SurrenderLog(KhatwaPaths.SurrendersPath)));
        return 0;
    }

    private static int ShowLog()
    {
        ApplicationConfiguration.Initialize();
        Application.Run(new LogForm(new SurrenderLog(KhatwaPaths.SurrendersPath)));
        return 0;
    }

    private static int UiSmoke(string[] args)
    {
        var seconds = args.Length > 1 && int.TryParse(args[1], out var s) ? s : 3;
        var config = new LockConfig { Secret = "abcdefghijklmnopqrstuvwx" };
        var tmp = Path.Combine(Path.GetTempPath(), "khatwa-ui-smoke");
        Directory.CreateDirectory(tmp);
        var state = new LockStateStore(Path.Combine(tmp, "state.json"));
        state.Clear();
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
        var ok = DailyCode.Compute("abcdefghijklmnopqrstuvwx", "2026-01-01") == "358895"
                 && DailyCode.Compute("K7mP2qR9sT4vW6xZ3bN8cD5f", "2026-09-23") == "178291"
                 && DailyCode.Compute("secret", "2000-02-29") == "290136";
        Console.WriteLine(ok ? "selftest OK" : "selftest FAILED");
        return ok ? 0 : 1;
    }

    private static int PrintCode(string[] args)
    {
        if (args.Length < 3) { Console.WriteLine("usage: --code <secret> <yyyy-MM-dd>"); return 2; }
        Console.WriteLine(DailyCode.Compute(args[1], args[2]));
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
        catch { /* logging must never crash the lock */ }
    }
}
