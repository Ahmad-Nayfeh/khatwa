using System.Text.Json;
using KhatwaLock.Core;
using Xunit;

namespace KhatwaLock.Tests;

public class ChallengeCodesTests
{
    private sealed record Vector(string secret, long counter, string lockCode, string unlockCode);
    private sealed record Skip(string secret, long last, long next);
    private sealed record Pairing(List<string> valid, List<string> invalid);
    private sealed record Doc(string description, int window, List<Vector> vectors, Skip nextCounterSkip, Pairing pairing);

    private static Doc Shared()
    {
        var path = Path.Combine(AppContext.BaseDirectory, "hmac-vectors.json");
        var doc = JsonSerializer.Deserialize<Doc>(File.ReadAllText(path), new JsonSerializerOptions { PropertyNameCaseInsensitive = true });
        Assert.NotNull(doc);
        Assert.True(doc!.vectors.Count >= 5);
        return doc;
    }

    [Fact]
    public void SharedVectorsMatchTheCSharpImplementation()
    {
        var doc = Shared();
        Assert.Equal(ChallengeCodes.Window, doc.window);
        foreach (var v in doc.vectors)
        {
            Assert.Equal(v.lockCode, ChallengeCodes.LockCode(v.secret, v.counter));
            Assert.Equal(v.unlockCode, ChallengeCodes.UnlockCode(v.secret, v.counter));
            Assert.True(ChallengeCodes.VerifyUnlockCode(v.secret, v.counter, v.unlockCode));
            Assert.Equal(v.counter, ChallengeCodes.VerifyLockCode(v.secret, v.lockCode, Math.Max(0, v.counter - 1)));
        }
    }

    [Fact]
    public void NextCounterSkipsAClashLikeThePhone()
    {
        var s = Shared().nextCounterSkip;
        Assert.Equal(s.next, ChallengeCodes.NextCounter(s.secret, s.last));
        Assert.True(s.next > s.last + 1);
        Assert.Equal(1, ChallengeCodes.NextCounter("12345678", 0));
    }

    [Fact]
    public void EveryPhoneChallengeIsRecognisedByAFreshLaptop()
    {
        const string secret = "24681357";
        long last = 0;
        for (var i = 0; i < 300; i++)
        {
            var n = ChallengeCodes.NextCounter(secret, last);
            Assert.Equal(n, ChallengeCodes.VerifyLockCode(secret, ChallengeCodes.LockCode(secret, n), 0));
            last = n;
        }
    }

    [Fact]
    public void InputIsNormalisedAndWrongCodesAreRejected()
    {
        const string secret = "12345678";
        Assert.Equal(1, ChallengeCodes.VerifyLockCode(secret, " 179 578 ", 0));
        Assert.Equal(1, ChallengeCodes.VerifyLockCode(secret, "١٧٩٥٧٨", 0));
        Assert.Equal(1, ChallengeCodes.VerifyLockCode(secret, "۱۷۹۵۷۸", 0));
        Assert.Null(ChallengeCodes.VerifyLockCode(secret, "179578", 1)); // already used
        Assert.Null(ChallengeCodes.VerifyLockCode(secret, "17957", 0));
        Assert.Null(ChallengeCodes.VerifyLockCode(secret, "", 0));
        Assert.Null(ChallengeCodes.VerifyLockCode(secret, null, 0));
        Assert.True(ChallengeCodes.VerifyUnlockCode(secret, 1, "880 387"));
        Assert.False(ChallengeCodes.VerifyUnlockCode(secret, 2, "880387"));
        Assert.False(ChallengeCodes.VerifyUnlockCode(secret, 1, "880388"));
        Assert.False(ChallengeCodes.VerifyUnlockCode("87654321", 1, "880387"));
        Assert.False(ChallengeCodes.VerifyUnlockCode(secret, 1, null));
        Assert.Equal("179 578", ChallengeCodes.Format("179578"));
        Assert.Equal("1234 5678", ChallengeCodes.Format("12345678"));
    }

    [Fact]
    public void PairingCodesWithWrongCheckDigitsAreRejected()
    {
        var p = Shared().pairing;
        foreach (var c in p.valid) Assert.True(ChallengeCodes.IsValidPairingCode(c), c);
        foreach (var c in p.invalid) Assert.False(ChallengeCodes.IsValidPairingCode(c), c);
        Assert.True(ChallengeCodes.IsValidPairingCode("1234 5676"));
        Assert.True(ChallengeCodes.IsValidPairingCode("١٢٣٤٥٦٧٦"));
        Assert.False(ChallengeCodes.IsValidPairingCode("1234567"));
        Assert.False(ChallengeCodes.IsValidPairingCode(""));
        Assert.False(ChallengeCodes.IsValidPairingCode(null));
        foreach (var good in p.valid)
            for (var i = 0; i < 8; i++)
            {
                for (var d = '0'; d <= '9'; d++)
                    if (d != good[i]) Assert.False(ChallengeCodes.IsValidPairingCode(good[..i] + d + good[(i + 1)..]));
                if (i < 7 && good[i] != good[i + 1])
                    Assert.False(ChallengeCodes.IsValidPairingCode(good[..i] + good[i + 1] + good[i] + good[(i + 2)..]));
            }
    }

    [Fact]
    public void SecretsAreEightDigits()
    {
        Assert.True(ChallengeCodes.IsSecret("12345678"));
        Assert.True(ChallengeCodes.IsSecret("1234 5678"));
        Assert.True(ChallengeCodes.IsSecret("١٢٣٤٥٦٧٨"));
        Assert.False(ChallengeCodes.IsSecret("1234567"));
        Assert.False(ChallengeCodes.IsSecret("ABCDEFGHJKLMNPQR"));
        Assert.False(ChallengeCodes.IsSecret("K7MP2QR9ST4VW6XZ"));
        Assert.False(ChallengeCodes.IsSecret(null));
    }
}
