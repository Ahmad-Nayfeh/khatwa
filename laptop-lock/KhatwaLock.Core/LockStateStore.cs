using System.Text.Json;
using System.Text.Json.Serialization;

namespace KhatwaLock.Core;

/// <summary>
/// state.json: whether the laptop is locked, by which phone challenge (counter), and the last
/// challenge counter it accepted (lock codes are looked for after it). The lock survives reboots
/// and logins until the matching unlock code (or the emergency phrase) is entered.
/// </summary>
public sealed class LockStateStore
{
    private sealed class StateFile
    {
        [JsonPropertyName("locked")] public bool Locked { get; set; }
        [JsonPropertyName("counter")] public long Counter { get; set; }
        [JsonPropertyName("lastCounter")] public long LastCounter { get; set; }
        [JsonPropertyName("lockedAt")] public string? LockedAt { get; set; }
        [JsonPropertyName("unlockedAt")] public string? UnlockedAt { get; set; }
        [JsonPropertyName("how")] public string? How { get; set; }
    }

    private readonly string _path;

    public LockStateStore(string path) => _path = path;

    private StateFile Read()
    {
        if (!File.Exists(_path)) return new StateFile();
        try { return JsonSerializer.Deserialize<StateFile>(File.ReadAllText(_path)) ?? new StateFile(); }
        catch (JsonException) { return new StateFile(); }
    }

    private void Write(StateFile s)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(_path)!);
        File.WriteAllText(_path, JsonSerializer.Serialize(s, new JsonSerializerOptions { WriteIndented = true }));
    }

    /// <summary>Locked by a known challenge. (A state file from the old code format has no counter
    /// and reads as unlocked, so an update never leaves the laptop locked without a usable code.)</summary>
    public bool IsLocked => Read() is { Locked: true, Counter: > 0 };

    /// <summary>The challenge counter of the current (or last) lock; 0 when none.</summary>
    public long Counter => Read().Counter;

    /// <summary>The last challenge counter this laptop accepted; lock codes are looked for after it.</summary>
    public long LastCounter => Read().LastCounter;

    public DateTime? LockedAt => DateTime.TryParse(Read().LockedAt, out var d) ? d : null;

    public void MarkLocked(long counter)
    {
        var s = Read();
        s.Locked = true;
        s.Counter = counter;
        s.LastCounter = Math.Max(s.LastCounter, counter);
        s.LockedAt = DateTime.Now.ToString("s");
        s.UnlockedAt = null;
        s.How = null;
        Write(s);
    }

    public void MarkUnlocked(string how)
    {
        var s = Read();
        s.Locked = false;
        s.UnlockedAt = DateTime.Now.ToString("s");
        s.How = how;
        Write(s);
    }

    /// <summary>Forget everything (used when pairing again: the phone restarts its counter).</summary>
    public void Clear()
    {
        if (File.Exists(_path)) File.Delete(_path);
    }
}
