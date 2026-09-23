namespace KhatwaLock.Core;

/// <summary>Pure decision helpers so the UI stays thin and the rules are unit-tested.</summary>
public static class LockDecision
{
    /// <summary>Should the lock screen be shown right now?</summary>
    public static bool ShouldLock(LockConfig config, LockStateStore state, DateOnly today)
    {
        if (!config.HasSecret) return false; // nothing to check against: do not brick the laptop
        return !state.IsUnlockedOn(today);
    }

    /// <summary>The emergency phrase must match exactly after trimming; whitespace runs are normalised.</summary>
    public static bool PhraseMatches(string expected, string typed)
    {
        static string Norm(string s) => string.Join(' ', (s ?? string.Empty).Trim().Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries));
        return Norm(expected) == Norm(typed);
    }
}
