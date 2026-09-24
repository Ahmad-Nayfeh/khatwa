using System.Security.Cryptography;
using System.Text;

namespace KhatwaLock.Core;

/// <summary>
/// Laptop lock codes, byte-for-byte compatible with the Android app (core/LaptopCode.kt).
///
/// The phone and the laptop share a 16-character pairing secret. Every phone lock ("challenge")
/// has a random 4-character id, from which two codes are derived:
///   lock code   = id + first 4 chars of MAC("lock:" + id)   (8 chars, shown as XXXX-XXXX)
///   unlock code = first 8 chars of MAC("unlock:" + id)       (8 chars, shown as XXXX-XXXX)
/// MAC = HMAC-SHA256(secret UTF-8, message UTF-8); each byte maps to Alphabet[b % 32].
/// Both implementations are checked against shared/hmac-vectors.json in CI.
/// </summary>
public static class ChallengeCodes
{
    public const string Alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    public const int SecretLength = 16;
    public const int IdLength = 4;
    public const int CodeLength = 8;

    public static string LockCode(string secret, string challengeId)
    {
        var id = Normalize(challengeId);
        if (id.Length != IdLength) throw new ArgumentException("challenge id must be 4 chars", nameof(challengeId));
        return id + MacChars(secret, "lock:" + id, CodeLength - IdLength);
    }

    public static string UnlockCode(string secret, string challengeId)
    {
        var id = Normalize(challengeId);
        if (id.Length != IdLength) throw new ArgumentException("challenge id must be 4 chars", nameof(challengeId));
        return MacChars(secret, "unlock:" + id, CodeLength);
    }

    /// <summary>The challenge id when <paramref name="input"/> is a valid lock code, else null.</summary>
    public static string? VerifyLockCode(string secret, string? input)
    {
        var code = Normalize(input ?? string.Empty);
        if (code.Length != CodeLength) return null;
        var id = code[..IdLength];
        return ConstantTimeEquals(code, LockCode(secret, id)) ? id : null;
    }

    public static bool VerifyUnlockCode(string secret, string challengeId, string? input)
    {
        var code = Normalize(input ?? string.Empty);
        if (code.Length != CodeLength) return false;
        return ConstantTimeEquals(code, UnlockCode(secret, challengeId));
    }

    /// <summary>"ABCDEFGH" → "ABCD-EFGH".</summary>
    public static string Format(string code)
    {
        var n = Normalize(code);
        var sb = new StringBuilder();
        for (var i = 0; i < n.Length; i++)
        {
            if (i > 0 && i % 4 == 0) sb.Append('-');
            sb.Append(n[i]);
        }
        return sb.ToString();
    }

    /// <summary>Upper-cases and drops anything that is not an ASCII letter or digit.</summary>
    public static string Normalize(string input)
    {
        var sb = new StringBuilder(input.Length);
        foreach (var ch in input.ToUpperInvariant())
            if (ch is >= 'A' and <= 'Z' or >= '0' and <= '9') sb.Append(ch);
        return sb.ToString();
    }

    public static bool IsSecret(string? input)
    {
        var n = Normalize(input ?? string.Empty);
        return n.Length == SecretLength && n.All(c => Alphabet.Contains(c));
    }

    private static string MacChars(string secret, string message, int count)
    {
        using var hmac = new HMACSHA256(Encoding.UTF8.GetBytes(secret));
        var h = hmac.ComputeHash(Encoding.UTF8.GetBytes(message));
        var sb = new StringBuilder(count);
        for (var i = 0; i < count; i++) sb.Append(Alphabet[h[i] % Alphabet.Length]);
        return sb.ToString();
    }

    private static bool ConstantTimeEquals(string a, string b) =>
        a.Length == b.Length &&
        CryptographicOperations.FixedTimeEquals(Encoding.ASCII.GetBytes(a), Encoding.ASCII.GetBytes(b));
}
