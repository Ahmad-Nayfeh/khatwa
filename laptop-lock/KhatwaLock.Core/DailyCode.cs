using System.Security.Cryptography;
using System.Text;

namespace KhatwaLock.Core;

/// <summary>
/// Daily unlock code, byte-for-byte compatible with the Android app (core/LaptopCode.kt):
/// HMAC-SHA256(key = secret UTF-8, message = "yyyy-MM-dd" UTF-8) → first 4 bytes big-endian,
/// top bit cleared → mod 1_000_000 → 6 digits, zero-padded.
/// Both implementations are checked against shared/hmac-vectors.json in CI.
/// </summary>
public static class DailyCode
{
    public static string Compute(string secret, DateOnly date) => Compute(secret, date.ToString("yyyy-MM-dd"));

    public static string Compute(string secret, string isoDate)
    {
        using var hmac = new HMACSHA256(Encoding.UTF8.GetBytes(secret));
        var h = hmac.ComputeHash(Encoding.UTF8.GetBytes(isoDate));
        long n = ((long)(h[0] & 0x7f) << 24) | ((long)h[1] << 16) | ((long)h[2] << 8) | h[3];
        return (n % 1_000_000).ToString("D6");
    }

    /// <summary>Accepts today's code and yesterday's (the two devices may disagree on the date).</summary>
    public static bool IsAcceptable(string secret, string input, DateOnly today)
    {
        var trimmed = (input ?? string.Empty).Trim();
        if (trimmed.Length != 6 || !trimmed.All(char.IsAsciiDigit)) return false;
        return ConstantTimeEquals(trimmed, Compute(secret, today))
            || ConstantTimeEquals(trimmed, Compute(secret, today.AddDays(-1)));
    }

    private static bool ConstantTimeEquals(string a, string b) =>
        CryptographicOperations.FixedTimeEquals(Encoding.ASCII.GetBytes(a), Encoding.ASCII.GetBytes(b));
}
