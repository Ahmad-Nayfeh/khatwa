using System.Text.Json;
using System.Text.Json.Serialization;

namespace KhatwaLock.Core;

/// <summary>
/// state.json: whether the laptop is currently locked and by which phone challenge.
/// The lock survives reboots and logins until the matching unlock code (or the emergency
/// phrase) is entered.
/// </summary>
public sealed class LockStateStore
{
    private sealed class StateFile
    {
        [JsonPropertyName("locked")] public bool Locked { get; set; }
        [JsonPropertyName("challengeId")] public string? ChallengeId { get; set; }
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

    public bool IsLocked => Read() is { Locked: true, ChallengeId: { Length: > 0 } };

    public string? ChallengeId => Read().ChallengeId;

    public DateTime? LockedAt => DateTime.TryParse(Read().LockedAt, out var d) ? d : null;

    public void MarkLocked(string challengeId) =>
        Write(new StateFile { Locked = true, ChallengeId = ChallengeCodes.Normalize(challengeId), LockedAt = DateTime.Now.ToString("s") });

    public void MarkUnlocked(string how)
    {
        var s = Read();
        s.Locked = false;
        s.UnlockedAt = DateTime.Now.ToString("s");
        s.How = how;
        Write(s);
    }

    public void Clear()
    {
        if (File.Exists(_path)) File.Delete(_path);
    }
}
