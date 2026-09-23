using System.Text.Json;
using KhatwaLock.Core;
using Xunit;

namespace KhatwaLock.Tests;

public class DailyCodeTests
{
    private sealed record Vector(string secret, string date, string code, string hmacHex);
    private sealed record Doc(string description, List<Vector> vectors);

    private static List<Vector> SharedVectors()
    {
        var path = Path.Combine(AppContext.BaseDirectory, "hmac-vectors.json");
        var doc = JsonSerializer.Deserialize<Doc>(File.ReadAllText(path), new JsonSerializerOptions { PropertyNameCaseInsensitive = true });
        Assert.NotNull(doc);
        Assert.True(doc!.vectors.Count >= 5);
        return doc.vectors;
    }

    [Fact]
    public void SharedVectorsMatchTheCSharpImplementation()
    {
        foreach (var v in SharedVectors())
        {
            Assert.Equal(v.code, DailyCode.Compute(v.secret, v.date));
            Assert.Equal(v.code, DailyCode.Compute(v.secret, DateOnly.ParseExact(v.date, "yyyy-MM-dd")));
        }
    }

    [Fact]
    public void CodesAreSixDigitsAndChangeDaily()
    {
        var a = DailyCode.Compute("K7mP2qR9sT4vW6xZ3bN8cD5f", new DateOnly(2026, 9, 23));
        var b = DailyCode.Compute("K7mP2qR9sT4vW6xZ3bN8cD5f", new DateOnly(2026, 9, 24));
        Assert.Matches("^[0-9]{6}$", a);
        Assert.NotEqual(a, b);
    }

    [Fact]
    public void AcceptsTodayAndYesterdayOnly()
    {
        const string secret = "abcdefghijklmnopqrstuvwx";
        var today = new DateOnly(2026, 1, 2);
        Assert.True(DailyCode.IsAcceptable(secret, DailyCode.Compute(secret, today), today));
        Assert.True(DailyCode.IsAcceptable(secret, " " + DailyCode.Compute(secret, today.AddDays(-1)) + " ", today));
        Assert.False(DailyCode.IsAcceptable(secret, DailyCode.Compute(secret, today.AddDays(-2)), today));
        Assert.False(DailyCode.IsAcceptable(secret, DailyCode.Compute(secret, today.AddDays(1)), today));
        Assert.False(DailyCode.IsAcceptable(secret, "000000", today) && DailyCode.Compute(secret, today) != "000000" && DailyCode.Compute(secret, today.AddDays(-1)) != "000000");
        Assert.False(DailyCode.IsAcceptable(secret, "12345", today));
        Assert.False(DailyCode.IsAcceptable(secret, "abcdef", today));
        Assert.False(DailyCode.IsAcceptable(secret, "", today));
    }

    [Fact]
    public void KnownVectorFromTheSharedFileIsAcceptedAsYesterday()
    {
        // 2026-01-01 with secret abcdefghijklmnopqrstuvwx is 358895 (shared vector); accepted on 2026-01-02.
        Assert.True(DailyCode.IsAcceptable("abcdefghijklmnopqrstuvwx", "358895", new DateOnly(2026, 1, 2)));
        Assert.False(DailyCode.IsAcceptable("abcdefghijklmnopqrstuvwx", "358895", new DateOnly(2026, 1, 3)));
    }
}
