using KhatwaLock.Core;
using Xunit;

namespace KhatwaLock.Tests;

public class StateAndConfigTests : IDisposable
{
    private readonly string _dir = Path.Combine(Path.GetTempPath(), "khatwa-tests-" + Guid.NewGuid().ToString("N"));

    public StateAndConfigTests() => Directory.CreateDirectory(_dir);
    public void Dispose() { try { Directory.Delete(_dir, true); } catch { } }

    [Fact]
    public void UnlockLastsUntilTheEndOfTheDayOnly()
    {
        var state = new LockStateStore(Path.Combine(_dir, "state.json"));
        var today = new DateOnly(2026, 9, 23);
        Assert.False(state.IsUnlockedOn(today));
        state.MarkUnlocked(today, "code");
        Assert.True(state.IsUnlockedOn(today));
        Assert.False(state.IsUnlockedOn(today.AddDays(1)));
        state.Clear();
        Assert.False(state.IsUnlockedOn(today));
    }

    [Fact]
    public void ShouldLockFollowsSecretAndState()
    {
        var state = new LockStateStore(Path.Combine(_dir, "state.json"));
        var today = new DateOnly(2026, 9, 23);
        Assert.False(LockDecision.ShouldLock(new LockConfig(), state, today)); // no secret: never brick
        var cfg = new LockConfig { Secret = "abcdefghijklmnopqrstuvwx" };
        Assert.True(LockDecision.ShouldLock(cfg, state, today));
        state.MarkUnlocked(today, "code");
        Assert.False(LockDecision.ShouldLock(cfg, state, today));
        Assert.True(LockDecision.ShouldLock(cfg, state, today.AddDays(1)));
    }

    [Fact]
    public void ConfigRoundTripsAndTolerantOfMissingOrBrokenFile()
    {
        var path = Path.Combine(_dir, "config.json");
        Assert.False(LockConfig.Load(path).HasSecret);
        var cfg = new LockConfig { Secret = "  K7mP2qR9sT4vW6xZ3bN8cD5f  " };
        cfg.Save(path);
        var loaded = LockConfig.Load(path);
        Assert.Equal("K7mP2qR9sT4vW6xZ3bN8cD5f", loaded.Secret);
        Assert.Equal(LockConfig.DefaultEmergencyPhrase, loaded.EmergencyPhrase);
        Assert.Equal(60, loaded.EmergencyWaitSeconds);
        File.WriteAllText(path, "{ not json");
        Assert.False(LockConfig.Load(path).HasSecret);
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
        Assert.True(LockDecision.PhraseMatches(LockConfig.DefaultEmergencyPhrase, "  " + LockConfig.DefaultEmergencyPhrase + "  "));
        Assert.True(LockDecision.PhraseMatches("a b c", "a  b   c"));
        Assert.False(LockDecision.PhraseMatches("a b c", "a b"));
    }
}
