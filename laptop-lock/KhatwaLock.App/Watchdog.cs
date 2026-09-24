using KhatwaLock.Core;

namespace KhatwaLock.App;

/// <summary>
/// Runs at sign-in (HKCU Run entry). Every few seconds: if the laptop is locked and no lock
/// screen is showing (closed via Task Manager, crashed, or just signed in), show it.
/// Reasonable resistance only: Safe Mode or deleting the folder still win.
/// </summary>
internal static class Watchdog
{
    private const string WatchdogMutexName = @"Local\KhatwaLock.Watchdog";

    public static int Run()
    {
        using var mutex = new Mutex(true, WatchdogMutexName, out var createdNew);
        if (!createdNew) return 0; // one watchdog is enough
        Log.Write("watchdog started");
        ApplicationConfiguration.Initialize();
        while (true)
        {
            try
            {
                var config = LockConfig.Load(KhatwaPaths.ConfigPath);
                var state = new LockStateStore(KhatwaPaths.StatePath);
                if (LockDecision.ShouldLock(config, state) && !Program.IsLockScreenRunning())
                {
                    Log.Write("watchdog: laptop locked and no lock screen; showing it");
                    Program.ShowLockScreen(config, state, new SurrenderLog(KhatwaPaths.SurrendersPath));
                }
            }
            catch (Exception e)
            {
                Log.Write("watchdog error: " + e.Message);
            }
            Thread.Sleep(3000);
        }
    }
}
