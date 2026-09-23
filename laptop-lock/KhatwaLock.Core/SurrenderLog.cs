using System.Text.Json;
using System.Text.Json.Serialization;

namespace KhatwaLock.Core;

public sealed class SurrenderEntry
{
    [JsonPropertyName("at")] public string At { get; set; } = string.Empty;
    [JsonPropertyName("date")] public string Date { get; set; } = string.Empty;
    [JsonPropertyName("note")] public string Note { get; set; } = string.Empty;
}

/// <summary>surrenders.json: every emergency unlock, newest last.</summary>
public sealed class SurrenderLog
{
    private readonly string _path;
    private static readonly JsonSerializerOptions Options = new()
    {
        WriteIndented = true,
        Encoder = System.Text.Encodings.Web.JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
    };

    public SurrenderLog(string path) => _path = path;

    public List<SurrenderEntry> Read()
    {
        if (!File.Exists(_path)) return new List<SurrenderEntry>();
        try { return JsonSerializer.Deserialize<List<SurrenderEntry>>(File.ReadAllText(_path), Options) ?? new(); }
        catch (JsonException) { return new List<SurrenderEntry>(); }
    }

    public void Append(DateTime now, string note)
    {
        var list = Read();
        list.Add(new SurrenderEntry { At = now.ToString("s"), Date = DateOnly.FromDateTime(now).ToString("yyyy-MM-dd"), Note = note });
        Directory.CreateDirectory(Path.GetDirectoryName(_path)!);
        File.WriteAllText(_path, JsonSerializer.Serialize(list, Options));
    }

    public int Count => Read().Count;
}
