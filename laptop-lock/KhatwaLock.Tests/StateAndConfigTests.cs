using KhatwaLock.Core;
using Xunit;

namespace KhatwaLock.Tests;

public class StateAndConfigTests : IDisposable
{
    private readonly string _dir = Path.Combine(Path.GetTempPath(), "khatwa-tests-" + Guid.NewGuid().ToString("N"));

    public StateAndConfigTests() => Directory.CreateDirectory(_dir);
    public void Dispose() { try { Directory.Delete(_dir, true); } catch { } }

    [Fact]
    public void LockStatePersistsUntilUnlockedAndRemembersTheLastCounter()
    {
        var state = new LockStateStore(Path.Combine(_dir, "state.json"));
        Assert.False(state.IsLocked);
        Assert.Equal(0, state.Counter);
        Assert.Equal(0, state.LastCounter);
        state.MarkLocked(7);
        Assert.True(state.IsLocked);
        Assert.Equal(7, state.Counter);
        Assert.Equal(7, state.LastCounter);
        Assert.NotNull(state.LockedAt);
        // A fresh instance reads the same file: the lock survives restarts.
        Assert.True(new LockStateStore(Path.Combine(_dir, "state.json")).IsLocked);
        state.MarkUnlocked("code");
        Assert.False(state.IsLocked);
        Assert.Equal(7, state.LastCounter); // lock codes are looked for after it
        state.MarkLocked(9);
        Assert.Equal(9, state.LastCounter);
        state.Clear();
        Assert.False(state.IsLocked);
        Assert.Equal(0, state.LastCounter);
    }

    [Fact]
    public void AnOldFormatStateFileReadsAsUnlocked()
    {
        var path = Path.Combine(_dir, "state.json");
        File.WriteAllText(path, "{ \"locked\": true, \"challengeId\": \"K7MP\" }");
        Assert.False(new LockStateStore(path).IsLocked); // no usable code would exist for it
    }

    [Fact]
    public void ShouldLockNeedsASecretAndALockedState()
    {
        var state = new LockStateStore(Path.Combine(_dir, "state.json"));
        state.MarkLocked(1);
        Assert.False(LockDecision.ShouldLock(new LockConfig(), state)); // no secret: never brick
        var cfg = new LockConfig { Secret = "12345678" };
        Assert.True(LockDecision.ShouldLock(cfg, state));
        state.MarkUnlocked("code");
        Assert.False(LockDecision.ShouldLock(cfg, state));
    }

    [Fact]
    public void ConfigRoundTripsAndTolerantOfMissingOrBrokenFile()
    {
        var path = Path.Combine(_dir, "config.json");
        Assert.False(LockConfig.Load(path).HasSecret);
        var cfg = new LockConfig { Secret = " 1234 5678 ", Language = "en" };
        cfg.Save(path);
        var loaded = LockConfig.Load(path);
        Assert.Equal("12345678", loaded.Secret);
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
    public void OldFormatPairingsAreNotAccepted()
    {
        var path = Path.Combine(_dir, "config.json");
        // 16-character code of the previous version, with 8 digits in it: must not become a pairing.
        File.WriteAllText(path, "{ \"secret\": \"K7M2P3Q4R5S6T7U8\" }");
        Assert.False(LockConfig.Load(path).HasSecret);
        Assert.False(new LockConfig { Secret = "K7mP2qR9sT4vW6xZ3bN8cD5f" }.HasSecret);
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
    public void ArabicPhraseMatchesHoweverItIsTyped()
    {
        const string withTashkeel = "أختار الاستسلام اليوم وأعلم أن هذا يُسجَّل";
        Assert.True(LockDecision.PhraseMatches(withTashkeel, "أختار الاستسلام اليوم وأعلم أن هذا يسجل"));
        Assert.True(LockDecision.PhraseMatches(withTashkeel, "اختار الاستسلام اليوم واعلم ان هذا يسجل"));
        Assert.True(LockDecision.PhraseMatches(withTashkeel, "\u200Fاختار الاستسلام اليوم، واعلم ان هذا يـسـجـل."));
        Assert.False(LockDecision.PhraseMatches(withTashkeel, "اختار الاستسلام اليوم"));
        Assert.False(LockDecision.PhraseMatches(withTashkeel, "اختار الاستسلام اليوم واعلم ان هذا لا يسجل"));
        Assert.False(LockDecision.PhraseMatches("", ""));
    }

    [Fact]
    public void TheLongDefaultPhraseIsAcceptedHoweverItIsTyped()
    {
        // No hamza, no commas, and "على" for "علي": all accepted.
        Assert.True(LockDecision.PhraseMatches(LockConfig.DefaultEmergencyPhraseAr,
            "اختار الاستسلام اليوم بدلا من المشي واعلم ان هذا يسجل على واعد نفسي ان احاول من جديد غدا"));
        Assert.False(LockDecision.PhraseMatches(LockConfig.DefaultEmergencyPhraseAr, "اختار الاستسلام اليوم بدلا من المشي"));
        Assert.True(LockDecision.PhraseMatches(LockConfig.DefaultEmergencyPhraseEn,
            "i choose to give up today instead of walking i know this is recorded and i promise myself to try again tomorrow"));
        Assert.True(LockConfig.DefaultEmergencyPhraseAr.Split(' ').Length >= 15);
    }

    [Fact]
    public void TheOldDiacriticDefaultIsReplacedOnLoad()
    {
        var path = Path.Combine(_dir, "config.json");
        File.WriteAllText(path, "{ \"secret\": \"12345678\", \"emergencyPhraseAr\": \"أختار الاستسلام اليوم وأعلم أن هذا يُسجَّل\" }");
        Assert.Equal(LockConfig.DefaultEmergencyPhraseAr, LockConfig.Load(path).EmergencyPhraseAr);
    }

    [Fact]
    public void TheShortDefaultsAreReplacedButACustomPhraseIsKept()
    {
        var path = Path.Combine(_dir, "config.json");
        File.WriteAllText(path, "{ \"emergencyPhraseAr\": \"أختار الاستسلام اليوم وأعلم أن هذا يسجل\", \"emergencyPhraseEn\": \"I choose to give up today and I know this is recorded\" }");
        var cfg = LockConfig.Load(path);
        Assert.Equal(LockConfig.DefaultEmergencyPhraseAr, cfg.EmergencyPhraseAr);
        Assert.Equal(LockConfig.DefaultEmergencyPhraseEn, cfg.EmergencyPhraseEn);
        File.WriteAllText(path, "{ \"emergencyPhraseAr\": \"جملتي الخاصة الطويلة\" }");
        Assert.Equal("جملتي الخاصة الطويلة", LockConfig.Load(path).EmergencyPhraseAr);
    }

    [Fact]
    public void PhraseMatchingIgnoresSurroundingAndDoubledSpaces()
    {
        Assert.True(LockDecision.PhraseMatches(LockConfig.DefaultEmergencyPhraseAr, "  " + LockConfig.DefaultEmergencyPhraseAr + "  "));
        Assert.True(LockDecision.PhraseMatches("a b c", "a  b   c"));
        Assert.False(LockDecision.PhraseMatches("a b c", "a b"));
    }
}
