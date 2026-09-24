using System.Text.Json;
using System.Text.Json.Serialization;

namespace KhatwaLock.Core;

/// <summary>config.json: the pairing secret, the UI language and the emergency settings.</summary>
public sealed class LockConfig
{
    public const string DefaultEmergencyPhraseAr = "أختار الاستسلام اليوم وأعلم أن هذا يسجل";
    /// <summary>The 0.3.0 default had diacritics that are hard to type; replaced on load.</summary>
    private const string OldDefaultEmergencyPhraseAr = "أختار الاستسلام اليوم وأعلم أن هذا يُسجَّل";
    public const string DefaultEmergencyPhraseEn = "I choose to give up today and I know this is recorded";

    /// <summary>8-digit pairing code from the phone (Settings ← laptop lock, or the lock card).</summary>
    [JsonPropertyName("secret")] public string Secret { get; set; } = string.Empty;

    /// <summary>"ar" or "en".</summary>
    [JsonPropertyName("language")] public string Language { get; set; } = "ar";

    [JsonPropertyName("emergencyPhraseAr")] public string EmergencyPhraseAr { get; set; } = DefaultEmergencyPhraseAr;
    [JsonPropertyName("emergencyPhraseEn")] public string EmergencyPhraseEn { get; set; } = DefaultEmergencyPhraseEn;
    [JsonPropertyName("emergencyWaitSeconds")] public int EmergencyWaitSeconds { get; set; } = 60;

    [JsonIgnore] public bool HasSecret => ChallengeCodes.IsSecret(Secret);
    [JsonIgnore] public string NormalizedSecret => ChallengeCodes.Normalize(Secret);
    [JsonIgnore] public bool IsArabic => !string.Equals(Language, "en", StringComparison.OrdinalIgnoreCase);
    [JsonIgnore] public string EmergencyPhrase => IsArabic ? EmergencyPhraseAr : EmergencyPhraseEn;

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
            // An old-format pairing (16 letters/digits) is not valid any more: pair again.
            cfg.Secret = ChallengeCodes.IsSecret(cfg.Secret) ? ChallengeCodes.Normalize(cfg.Secret!) : string.Empty;
            if (string.IsNullOrWhiteSpace(cfg.EmergencyPhraseAr) || cfg.EmergencyPhraseAr == OldDefaultEmergencyPhraseAr) cfg.EmergencyPhraseAr = DefaultEmergencyPhraseAr;
            if (string.IsNullOrWhiteSpace(cfg.EmergencyPhraseEn)) cfg.EmergencyPhraseEn = DefaultEmergencyPhraseEn;
            if (cfg.EmergencyWaitSeconds < 0) cfg.EmergencyWaitSeconds = 60;
            if (string.IsNullOrWhiteSpace(cfg.Language)) cfg.Language = "ar";
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
