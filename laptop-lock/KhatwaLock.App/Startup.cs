using Microsoft.Win32;
using KhatwaLock.Core;

namespace KhatwaLock.App;

/// <summary>
/// "Runs at sign-in" without administrator rights: the exe is copied to %LOCALAPPDATA%\khatwa and
/// registered under HKCU\...\Run in watchdog mode, so an active lock reappears after a reboot.
/// (Task Scheduler with highest privileges remains available through install.ps1 for people who
/// want a harder lock.)
/// </summary>
internal static class Startup
{
    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string ValueName = "KhatwaLock";

    public static string InstalledExe => Path.Combine(KhatwaPaths.BaseDir, "KhatwaLock.exe");

    public static bool IsEnabled()
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(RunKey, writable: false);
            return key?.GetValue(ValueName) is string s && s.Length > 0;
        }
        catch { return false; }
    }

    /// <summary>Copies the running exe next to the config (if needed) and registers the Run entry.</summary>
    public static bool Enable()
    {
        try
        {
            Directory.CreateDirectory(KhatwaPaths.BaseDir);
            var self = Program.ExePath;
            if (!string.Equals(Path.GetFullPath(self), Path.GetFullPath(InstalledExe), StringComparison.OrdinalIgnoreCase))
            {
                File.Copy(self, InstalledExe, overwrite: true);
            }
            using var key = Registry.CurrentUser.CreateSubKey(RunKey, writable: true);
            key?.SetValue(ValueName, $"\"{InstalledExe}\" --watchdog");
            Log.Write("startup entry enabled");
            return true;
        }
        catch (Exception e)
        {
            Log.Write("startup enable failed: " + e.Message);
            return false;
        }
    }

    public static void Disable()
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(RunKey, writable: true);
            key?.DeleteValue(ValueName, throwOnMissingValue: false);
            Log.Write("startup entry removed");
        }
        catch (Exception e)
        {
            Log.Write("startup disable failed: " + e.Message);
        }
    }

    /// <summary>Starts the watchdog now (idempotent: a second watchdog exits immediately).</summary>
    public static void StartWatchdogNow()
    {
        try
        {
            var exe = File.Exists(InstalledExe) ? InstalledExe : Program.ExePath;
            System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(exe, "--watchdog") { UseShellExecute = false });
        }
        catch (Exception e)
        {
            Log.Write("watchdog start failed: " + e.Message);
        }
    }
}
