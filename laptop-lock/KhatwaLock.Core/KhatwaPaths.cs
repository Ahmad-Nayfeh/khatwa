namespace KhatwaLock.Core;

/// <summary>All files live in %LOCALAPPDATA%\khatwa (per user, no admin needed to write).</summary>
public static class KhatwaPaths
{
    public static string BaseDir { get; set; } =
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "khatwa");

    public static string ConfigPath => Path.Combine(BaseDir, "config.json");
    public static string StatePath => Path.Combine(BaseDir, "state.json");
    public static string SurrendersPath => Path.Combine(BaseDir, "surrenders.json");
    public static string LogPath => Path.Combine(BaseDir, "khatwa-lock.log");
}
