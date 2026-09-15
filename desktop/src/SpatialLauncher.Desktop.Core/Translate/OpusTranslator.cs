using System.Text.RegularExpressions;
using SpatialLauncher.Desktop.Core.Depth;

namespace SpatialLauncher.Desktop.Core.Translate;

/// <summary>
/// OPUS-MT ja/zh/ko→en when Marian ONNX pairs exist under LocalAppData.
/// Until decoder is fully wired, passes text through but reports pack readiness.
/// </summary>
public sealed class OpusTranslator
{
    public bool IsReady { get; private set; }
    public string Status { get; private set; } = "OPUS not packed";

    public void EnsureReady()
    {
        bool ja = PairReady(ModelPaths.OpusJaEnDir);
        bool zh = PairReady(ModelPaths.OpusZhEnDir);
        bool ko = PairReady(ModelPaths.OpusKoEnDir);
        IsReady = ja || zh || ko;
        if (!IsReady)
        {
            Status = "OPUS packs missing (jaen/zhen/koen)";
            return;
        }
        var parts = new List<string>();
        if (ja) parts.Add("ja");
        if (zh) parts.Add("zh");
        if (ko) parts.Add("ko");
        Status = "OPUS-MT ready · " + string.Join("/", parts);
    }

    private static bool PairReady(string dir)
    {
        string enc = Path.Combine(dir, "encoder_model.onnx");
        string dec = Path.Combine(dir, "decoder_model.onnx");
        return File.Exists(enc) && File.Exists(dec);
    }

    public Task<string> ToEnglishAsync(string text, bool useOpus)
    {
        if (string.IsNullOrWhiteSpace(text)) return Task.FromResult("");
        string line = text.Trim();
        if (!useOpus || LooksPrimarilyEnglish(line))
            return Task.FromResult(line);

        EnsureReady();
        if (!IsReady)
            return Task.FromResult(line);

        // Marian ONNX decode hook — packs detected; until native decode ships, return source
        // so the pipeline stays live. Installer places Quest-compatible encoder/decoder ONNX.
        return Task.FromResult(line);
    }

    public static bool LooksPrimarilyEnglish(string text)
    {
        int letters = 0, latin = 0;
        foreach (var ch in text)
        {
            if (!char.IsLetter(ch)) continue;
            letters++;
            if (ch <= 0x024F) latin++;
        }
        if (letters == 0) return true;
        if (ContainsCjk(text) || ContainsHangul(text)) return false;
        return latin * 10 >= letters * 7;
    }

    public static bool ContainsCjk(string text) =>
        text.Any(c => c is >= '\u3040' and <= '\u30FF' or >= '\u4E00' and <= '\u9FFF');

    public static bool ContainsHangul(string text) =>
        text.Any(c => c is >= '\uAC00' and <= '\uD7AF');
}

public sealed class DialogueDeduper
{
    private string _last = "";

    public bool TryAccept(string raw, out string normalized)
    {
        normalized = Normalize(raw);
        if (string.IsNullOrEmpty(normalized)) return false;
        if (normalized == _last) return false;
        if (!string.IsNullOrEmpty(_last) && !_last.Equals(normalized, StringComparison.Ordinal)
            && Similar(_last, normalized))
            return false;
        _last = normalized;
        return true;
    }

    public static string Normalize(string raw)
    {
        if (string.IsNullOrWhiteSpace(raw)) return "";
        var lowered = raw.ToLowerInvariant();
        lowered = Regex.Replace(lowered, @"[^a-z0-9\u3040-\u30ff\u4e00-\u9fff\uac00-\ud7af]+", " ");
        return Regex.Replace(lowered, @"\s+", " ").Trim();
    }

    private static bool Similar(string a, string b)
    {
        if (a.Contains(b) || b.Contains(a)) return true;
        int dist = Levenshtein(a, b);
        int maxLen = Math.Max(a.Length, b.Length);
        return maxLen > 0 && dist <= Math.Max(2, maxLen / 5);
    }

    private static int Levenshtein(string a, string b)
    {
        int n = a.Length, m = b.Length;
        var d = new int[n + 1, m + 1];
        for (int i = 0; i <= n; i++) d[i, 0] = i;
        for (int j = 0; j <= m; j++) d[0, j] = j;
        for (int i = 1; i <= n; i++)
        for (int j = 1; j <= m; j++)
        {
            int cost = a[i - 1] == b[j - 1] ? 0 : 1;
            d[i, j] = Math.Min(Math.Min(d[i - 1, j] + 1, d[i, j - 1] + 1), d[i - 1, j - 1] + cost);
        }
        return d[n, m];
    }
}
