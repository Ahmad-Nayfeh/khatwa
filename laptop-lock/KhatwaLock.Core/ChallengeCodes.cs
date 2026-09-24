using System.Security.Cryptography;
using System.Text;

namespace KhatwaLock.Core;

/// <summary>
/// Laptop lock codes, byte-for-byte compatible with the Android app (core/LaptopCode.kt).
///
/// The phone and the laptop share an 8-digit pairing secret. Every phone lock ("challenge") takes
/// the next number of a counter that starts at 1 after pairing; two 6-digit codes derive from it:
///   lock code   = Code6(secret, "lock:"   + counter)
///   unlock code = Code6(secret, "unlock:" + counter)
/// Code6 = HMAC-SHA256(secret UTF-8, message UTF-8) with RFC 4226 dynamic truncation, mod 10^6.
/// The laptop remembers the last counter it accepted and tries the next <see cref="Window"/>.
/// Both implementations are checked against shared/hmac-vectors.json in CI.
/// </summary>
public static class ChallengeCodes
{
    public const int SecretLength = 8;
    public const int CodeLength = 6;
    public const int Window = 1000;

    public static string LockCode(string secret, long counter) => Code6(secret, "lock:" + counter);

    public static string UnlockCode(string secret, long counter) => Code6(secret, "unlock:" + counter);

    /// <summary>The counter when <paramref name="input"/> is the lock code of one of the next
    /// <see cref="Window"/> counters after <paramref name="last"/>, else null.</summary>
    public static long? VerifyLockCode(string secret, string? input, long last)
    {
        var code = Normalize(input ?? string.Empty);
        if (code.Length != CodeLength) return null;
        for (var n = last + 1; n <= last + Window; n++)
            if (ConstantTimeEquals(code, LockCode(secret, n))) return n;
        return null;
    }

    public static bool VerifyUnlockCode(string secret, long counter, string? input)
    {
        var code = Normalize(input ?? string.Empty);
        return code.Length == CodeLength && ConstantTimeEquals(code, UnlockCode(secret, counter));
    }

    /// <summary>Same rule as the phone: the first counter after <paramref name="last"/> whose lock
    /// code differs from the lock codes of the <see cref="Window"/> counters before it.</summary>
    public static long NextCounter(string secret, long last)
    {
        for (var n = last + 1; ; n++)
        {
            var code = LockCode(secret, n);
            var clash = false;
            for (var m = Math.Max(1, n - Window); m < n && !clash; m++) clash = LockCode(secret, m) == code;
            if (!clash) return n;
        }
    }

    /// <summary>"123456" → "123 456"; "12345678" → "1234 5678".</summary>
    public static string Format(string code)
    {
        var n = Normalize(code);
        var group = n.Length == SecretLength ? 4 : 3;
        var sb = new StringBuilder();
        for (var i = 0; i < n.Length; i++)
        {
            if (i > 0 && i % group == 0) sb.Append(' ');
            sb.Append(n[i]);
        }
        return sb.ToString();
    }

    /// <summary>Keeps digits only; Arabic-Indic (٠-٩) and Eastern Arabic-Indic (۰-۹) digits become ASCII.</summary>
    public static string Normalize(string input)
    {
        var sb = new StringBuilder(input.Length);
        foreach (var ch in input)
        {
            if (ch is >= '0' and <= '9') sb.Append(ch);
            else if (ch is >= '٠' and <= '٩') sb.Append((char)('0' + (ch - '٠')));
            else if (ch is >= '۰' and <= '۹') sb.Append((char)('0' + (ch - '۰')));
        }
        return sb.ToString();
    }

    public static bool IsSecret(string? input) =>
        input != null && Normalize(input).Length == SecretLength && input.All(c => char.IsDigit(c) || c is ' ' or '-');

    private static string Code6(string secret, string message)
    {
        using var hmac = new HMACSHA256(Encoding.UTF8.GetBytes(Normalize(secret)));
        var h = hmac.ComputeHash(Encoding.UTF8.GetBytes(message));
        var off = h[31] & 0x0f;
        var value = ((h[off] & 0x7f) << 24) | (h[off + 1] << 16) | (h[off + 2] << 8) | h[off + 3];
        return (value % 1_000_000).ToString("D6");
    }

    private static bool ConstantTimeEquals(string a, string b) =>
        a.Length == b.Length &&
        CryptographicOperations.FixedTimeEquals(Encoding.ASCII.GetBytes(a), Encoding.ASCII.GetBytes(b));
}
