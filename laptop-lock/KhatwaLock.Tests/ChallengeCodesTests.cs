using System.Text.Json;
using KhatwaLock.Core;
using Xunit;

namespace KhatwaLock.Tests;

public class ChallengeCodesTests
{
    private sealed record Vector(string secret, string challengeId, string lockCode, string unlockCode);
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
            Assert.Equal(v.lockCode, ChallengeCodes.LockCode(v.secret, v.challengeId));
            Assert.Equal(v.unlockCode, ChallengeCodes.UnlockCode(v.secret, v.challengeId));
            Assert.Equal(v.challengeId, ChallengeCodes.VerifyLockCode(v.secret, v.lockCode));
            Assert.True(ChallengeCodes.VerifyUnlockCode(v.secret, v.challengeId, v.unlockCode));
        }
    }

    [Fact]
    public void InputIsNormalisedAndWrongCodesAreRejected()
    {
        const string secret = "ABCDEFGHJKLMNPQR";
        Assert.Equal("K7MP", ChallengeCodes.VerifyLockCode(secret, " k7mp-y3he "));
        Assert.Equal("K7MP-Y3HE", ChallengeCodes.Format("K7MPY3HE"));
        Assert.Null(ChallengeCodes.VerifyLockCode(secret, "K7MP-Y3HF"));
        Assert.Null(ChallengeCodes.VerifyLockCode(secret, "K7MPY3H"));
        Assert.Null(ChallengeCodes.VerifyLockCode(secret, ""));
        Assert.Null(ChallengeCodes.VerifyLockCode(secret, null));
        Assert.Null(ChallengeCodes.VerifyLockCode("OTHERSECRET12345", "K7MPY3HE"));
        Assert.True(ChallengeCodes.VerifyUnlockCode(secret, "K7MP", "mtl5-n2ms"));
        Assert.False(ChallengeCodes.VerifyUnlockCode(secret, "K7MP", "MTL5N2MT"));
        Assert.False(ChallengeCodes.VerifyUnlockCode(secret, "2345", "MTL5N2MS"));
        Assert.False(ChallengeCodes.VerifyUnlockCode(secret, "K7MP", null));
    }

    [Fact]
    public void SecretsAreSixteenSafeCharacters()
    {
        Assert.True(ChallengeCodes.IsSecret("ABCD-EFGH-JKLM-NPQR"));
        Assert.True(ChallengeCodes.IsSecret("abcdefghjklmnpqr"));
        Assert.False(ChallengeCodes.IsSecret("ABCDEFGHJKLMNPQ"));
        Assert.False(ChallengeCodes.IsSecret("ABCDEFGHJKLMNPQ0"));
        Assert.False(ChallengeCodes.IsSecret(null));
    }
}
