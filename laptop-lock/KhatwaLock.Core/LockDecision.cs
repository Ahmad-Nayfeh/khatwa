namespace KhatwaLock.Core;

/// <summary>Pure decision helpers so the UI stays thin and the rules are unit-tested.</summary>
public static class LockDecision
{
    /// <summary>Should the lock screen be shown right now? Never without a pairing secret.</summary>
    public static bool ShouldLock(LockConfig config, LockStateStore state)
    {
        if (!config.HasSecret) return false; // nothing to check against: do not brick the laptop
        return state.IsLocked;
    }

    /// <summary>
    /// The typed emergency phrase matches when the words are the same, however the Arabic is typed
    /// (same rule as the phone, core/lock/Phrase.kt): diacritics and tatweel are ignored; أ إ آ ٱ
    /// count as ا, ى as ي, ة as ه; invisible direction marks and punctuation are ignored; whitespace
    /// runs count as one space; English is case-insensitive.
    /// </summary>
    public static bool PhraseMatches(string expected, string typed)
    {
        var e = NormalizePhrase(expected);
        return e.Length > 0 && e == NormalizePhrase(typed);
    }

    public static string NormalizePhrase(string? input)
    {
        var sb = new System.Text.StringBuilder();
        foreach (var c in (input ?? string.Empty).Normalize(System.Text.NormalizationForm.FormKC))
        {
            if (c is >= '\u064B' and <= '\u065F' || c == '\u0670' || c is >= '\u0610' and <= '\u061A' || c is >= '\u06D6' and <= '\u06ED') continue;
            if (c == '\u0640') continue; // tatweel
            if (char.GetUnicodeCategory(c) == System.Globalization.UnicodeCategory.Format) continue;
            if (c is 'أ' or 'إ' or 'آ' or 'ٱ') sb.Append('ا');
            else if (c == 'ى') sb.Append('ي');
            else if (c == 'ة') sb.Append('ه');
            else if (char.IsLetterOrDigit(c)) sb.Append(char.ToLowerInvariant(c));
            else if (char.IsWhiteSpace(c)) sb.Append(' ');
        }
        return string.Join(' ', sb.ToString().Split(' ', StringSplitOptions.RemoveEmptyEntries));
    }
}
