using System.Text.Json;
using System.Text.Json.Serialization;

namespace KhatwaLock.Core;

/// <summary>config.json: the shared secret plus a few knobs. Kept deliberately small.</summary>
public sealed class LockConfig
{
    public const string DefaultEmergencyPhrase = "أختار الاستسلام اليوم وأعلم أن هذا يُسجَّل";

    [JsonPropertyName("secret")] public string Secret { get; set; } = string.Empty;
    [JsonPropertyName("emergencyPhrase")] public string EmergencyPhrase { get; set; } = DefaultEmergencyPhrase;
    [JsonPropertyName("emergencyWaitSeconds")] public int EmergencyWaitSeconds { get; set; } = 60;
    /// <summary>Reserved for a future evening end time ("HH:mm"); unused today.</summary>
    [JsonPropertyName("eveningEnd")] public string? EveningEnd { get; set; }

    public bool HasSecret => !string.IsNullOrWhiteSpace(Secret) && Secret.Trim().Length >= 8;

    private static readonly JsonSerializerOptions Options = new()
    {
        WriteIndented = true,
        Encoder = System.Text.Encodings.Web.JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
    };

    public static LockConfig Load(string path)
    {
        if (!File.Exists(path)) return new LockConfig();
        try
        {
            var cfg = JsonSerializer.Deserialize<LockConfig>(File.ReadAllText(path), Options) ?? new LockConfig();
            cfg.Secret = cfg.Secret.Trim();
            if (string.IsNullOrWhiteSpace(cfg.EmergencyPhrase)) cfg.EmergencyPhrase = DefaultEmergencyPhrase;
            if (cfg.EmergencyWaitSeconds < 0) cfg.EmergencyWaitSeconds = 60;
            return cfg;
        }
        catch (JsonException)
        {
            return new LockConfig();
        }
    }

    public void Save(string path)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        File.WriteAllText(path, JsonSerializer.Serialize(this, Options));
    }
}
