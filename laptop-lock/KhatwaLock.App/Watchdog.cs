using System.Diagnostics;
using KhatwaLock.Core;

namespace KhatwaLock.App;

/// <summary>
/// Runs at logon next to the lock screen. Every few seconds: if today is still locked and no
/// lock screen is running (closed via Task Manager, crashed, or the date rolled over), start one.
/// Reasonable resistance only: Safe Mode, Task Scheduler, or deleting the folder still win.
/// </summary>
internal static class Watchdog
{
    private const string WatchdogMutexName = @"Local\KhatwaLock.Watchdog";

    public static int Run()
    {
        using var mutex = new Mutex(true, WatchdogMutexName, out var createdNew);
        if (!createdNew) return 0; // one watchdog is enough
        Log.Write("watchdog started");
        while (true)
        {
            try
            {
                var config = LockConfig.Load(KhatwaPaths.ConfigPath);
                var state = new LockStateStore(KhatwaPaths.StatePath);
                var today = DateOnly.FromDateTime(DateTime.Now);
                if (LockDecision.ShouldLock(config, state, today) && !Program.IsLockScreenRunning())
                {
                    Log.Write("watchdog: lock screen missing while locked; starting it");
                    Process.Start(new ProcessStartInfo(Program.ExePath) { UseShellExecute = false });
                    Thread.Sleep(5000);
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
