using KhatwaLock.Core;
using Xunit;

namespace KhatwaLock.Tests;

public class StateAndConfigTests : IDisposable
{
    private readonly string _dir = Path.Combine(Path.GetTempPath(), "khatwa-tests-" + Guid.NewGuid().ToString("N"));

    public StateAndConfigTests() => Directory.CreateDirectory(_dir);
    public void Dispose() { try { Directory.Delete(_dir, true); } catch { } }

    [Fact]
    public void LockStatePersistsUntilUnlocked()
    {
        var state = new LockStateStore(Path.Combine(_dir, "state.json"));
        Assert.False(state.IsLocked);
        Assert.Null(state.ChallengeId);
        state.MarkLocked("k7mp");
        Assert.True(state.IsLocked);
        Assert.Equal("K7MP", state.ChallengeId);
        Assert.NotNull(state.LockedAt);
        // A fresh instance reads the same file: the lock survives restarts.
        Assert.True(new LockStateStore(Path.Combine(_dir, "state.json")).IsLocked);
        state.MarkUnlocked("code");
        Assert.False(state.IsLocked);
        Assert.Equal("K7MP", state.ChallengeId); // kept for the log; not locked any more
        state.Clear();
        Assert.False(state.IsLocked);
        Assert.Null(state.ChallengeId);
    }

    [Fact]
    public void ShouldLockNeedsASecretAndALockedState()
    {
        var state = new LockStateStore(Path.Combine(_dir, "state.json"));
        state.MarkLocked("K7MP");
        Assert.False(LockDecision.ShouldLock(new LockConfig(), state)); // no secret: never brick
        var cfg = new LockConfig { Secret = "ABCDEFGHJKLMNPQR" };
        Assert.True(LockDecision.ShouldLock(cfg, state));
        state.MarkUnlocked("code");
        Assert.False(LockDecision.ShouldLock(cfg, state));
    }

    [Fact]
    public void ConfigRoundTripsAndTolerantOfMissingOrBrokenFile()
    {
        var path = Path.Combine(_dir, "config.json");
        Assert.False(LockConfig.Load(path).HasSecret);
        var cfg = new LockConfig { Secret = " abcd-efgh-jklm-npqr ", Language = "en" };
        cfg.Save(path);
        var loaded = LockConfig.Load(path);
        Assert.Equal("ABCDEFGHJKLMNPQR", loaded.Secret);
        Assert.True(loaded.HasSecret);
        Assert.False(loaded.IsArabic);
        Assert.Equal(LockConfig.DefaultEmergencyPhraseEn, loaded.EmergencyPhrase);
        Assert.Equal(LockConfig.DefaultEmergencyPhraseAr, loaded.EmergencyPhraseAr);
        Assert.Equal(60, loaded.EmergencyWaitSeconds);
        File.WriteAllText(path, "{ not json");
        Assert.False(LockConfig.Load(path).HasSecret);
        Assert.True(LockConfig.Load(path).IsArabic);
    }

    [Fact]
    public void OldTwentyFourCharSecretIsNotAcceptedAsPairing()
    {
        var cfg = new LockConfig { Secret = "K7mP2qR9sT4vW6xZ3bN8cD5f" };
        Assert.False(cfg.HasSecret);
    }

    [Fact]
    public void SurrenderLogAppendsAndCounts()
    {
        var log = new SurrenderLog(Path.Combine(_dir, "surrenders.json"));
        Assert.Equal(0, log.Count);
        log.Append(new DateTime(2026, 9, 23, 20, 15, 0), "emergency");
        log.Append(new DateTime(2026, 9, 24, 21, 0, 0), "emergency");
        var all = log.Read();
        Assert.Equal(2, all.Count);
        Assert.Equal("2026-09-24", all[1].Date);
    }

    [Fact]
    public void PhraseMatchingIgnoresSurroundingAndDoubledSpaces()
    {
        Assert.True(LockDecision.PhraseMatches(LockConfig.DefaultEmergencyPhraseAr, "  " + LockConfig.DefaultEmergencyPhraseAr + "  "));
        Assert.True(LockDecision.PhraseMatches("a b c", "a  b   c"));
        Assert.False(LockDecision.PhraseMatches("a b c", "a b"));
    }
}
