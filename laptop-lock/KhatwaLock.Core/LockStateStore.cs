using System.Text.Json;
using System.Text.Json.Serialization;

namespace KhatwaLock.Core;

/// <summary>state.json: "unlocked until the end of the day" as the date on which the code was accepted.</summary>
public sealed class LockStateStore
{
    private sealed class StateFile
    {
        [JsonPropertyName("unlockedDate")] public string? UnlockedDate { get; set; }
        [JsonPropertyName("unlockedAt")] public string? UnlockedAt { get; set; }
        [JsonPropertyName("how")] public string? How { get; set; }
    }

    private readonly string _path;

    public LockStateStore(string path) => _path = path;

    public DateOnly? UnlockedDate
    {
        get
        {
            if (!File.Exists(_path)) return null;
            try
            {
                var s = JsonSerializer.Deserialize<StateFile>(File.ReadAllText(_path));
                return s?.UnlockedDate is { } d && DateOnly.TryParseExact(d, "yyyy-MM-dd", out var date) ? date : null;
            }
            catch (JsonException) { return null; }
        }
    }

    public bool IsUnlockedOn(DateOnly today) => UnlockedDate == today;

    public void MarkUnlocked(DateOnly today, string how)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(_path)!);
        var s = new StateFile { UnlockedDate = today.ToString("yyyy-MM-dd"), UnlockedAt = DateTime.Now.ToString("s"), How = how };
        File.WriteAllText(_path, JsonSerializer.Serialize(s, new JsonSerializerOptions { WriteIndented = true }));
    }

    public void Clear()
    {
        if (File.Exists(_path)) File.Delete(_path);
    }
}
