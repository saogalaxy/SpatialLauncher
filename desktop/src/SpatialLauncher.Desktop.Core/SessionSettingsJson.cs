using System.Text.Json;

namespace SpatialLauncher.Desktop.Core;

/// <summary>JSON snapshot of live session knobs for Quest remote control.</summary>
public static class SessionSettingsJson
{
    private static readonly JsonSerializerOptions JsonOpts = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        WriteIndented = false
    };

    public static string ToJson(UserSettings s) => JsonSerializer.Serialize(new Dict(s), JsonOpts);

    public static bool TryApply(string json, UserSettings target, out string error)
    {
        error = "";
        try
        {
            var patch = JsonSerializer.Deserialize<Dict>(json, JsonOpts);
            if (patch == null)
            {
                error = "empty";
                return false;
            }
            if (patch.Live3d.HasValue) target.Live3D = patch.Live3d.Value;
            if (patch.FullSbs.HasValue) target.FullSbs = patch.FullSbs.Value;
            if (patch.DepthStrength.HasValue)
                target.Divergence = Math.Clamp(patch.DepthStrength.Value, 10, 200) / 100.0;
            if (patch.Convergence.HasValue)
                target.Convergence = Math.Clamp(patch.Convergence.Value, 0, 100) / 100.0;
            if (patch.StreamWidth.HasValue)
                target.StreamWidth = Math.Clamp(patch.StreamWidth.Value, 960, 3840);
            if (patch.JpegQuality.HasValue)
                target.JpegQuality = Math.Clamp(patch.JpegQuality.Value, 50, 98);
            if (patch.Sharpen.HasValue)
                target.SharpenPercent = Math.Clamp(patch.Sharpen.Value, 0, 80);
            if (patch.DepthHz.HasValue)
                target.DepthHz = Math.Clamp(patch.DepthHz.Value, 5, 60);
            if (patch.DepthSmooth.HasValue)
                target.DepthTemporalSmoothPercent = Math.Clamp(patch.DepthSmooth.Value, 0, 90);
            if (patch.EdgeClean.HasValue)
                target.EdgeCleanPercent = Math.Clamp(patch.EdgeClean.Value, 0, 100);
            if (!string.IsNullOrWhiteSpace(patch.Codec))
                target.StreamCodec = ParseCodec(patch.Codec);
            if (!string.IsNullOrWhiteSpace(patch.Audio))
            {
                target.AudioMode = patch.Audio.Contains("headset", StringComparison.OrdinalIgnoreCase)
                    ? Stream.AudioOutputMode.Headset
                    : Stream.AudioOutputMode.Pc;
            }
            if (!string.IsNullOrWhiteSpace(patch.DepthPreset))
            {
                target.DepthPreset = patch.DepthPreset.Contains("movie", StringComparison.OrdinalIgnoreCase)
                    ? DepthPreset.Movies
                    : DepthPreset.Gaming;
                target.ApplyDepthPresetDefaults();
            }
            return true;
        }
        catch (Exception ex)
        {
            error = ex.Message;
            return false;
        }
    }

    public static StreamCodec ParseCodec(string codec)
    {
        if (codec.Contains("av1", StringComparison.OrdinalIgnoreCase)
            || codec.Contains("av01", StringComparison.OrdinalIgnoreCase))
            return StreamCodec.Av1;
        if (codec.Contains("264", StringComparison.OrdinalIgnoreCase)
            || codec.Contains("mpeg", StringComparison.OrdinalIgnoreCase))
            return StreamCodec.H264;
        return StreamCodec.Mjpeg;
    }

    public static string CodecLabel(StreamCodec codec) => codec switch
    {
        StreamCodec.H264 => "h264",
        StreamCodec.Av1 => "av1",
        _ => "mjpeg"
    };

    public static string StreamPath(StreamCodec codec) => codec switch
    {
        StreamCodec.H264 => "sbs.h264",
        StreamCodec.Av1 => "sbs.av1",
        _ => "sbs.mjpg"
    };

    private sealed class Dict
    {
        public bool? Live3d { get; set; }
        public bool? FullSbs { get; set; }
        public int? DepthStrength { get; set; }
        public int? Convergence { get; set; }
        public int? StreamWidth { get; set; }
        public int? JpegQuality { get; set; }
        public int? Sharpen { get; set; }
        public int? DepthHz { get; set; }
        public int? DepthSmooth { get; set; }
        public int? EdgeClean { get; set; }
        public string? Codec { get; set; }
        public string? Audio { get; set; }
        public string? DepthPreset { get; set; }

        public Dict() { }

        public Dict(UserSettings s)
        {
            Live3d = s.Live3D;
            FullSbs = s.FullSbs;
            DepthStrength = (int)Math.Round(s.Divergence * 100);
            Convergence = (int)Math.Round(s.Convergence * 100);
            StreamWidth = s.StreamWidth;
            JpegQuality = s.JpegQuality;
            Sharpen = s.SharpenPercent;
            DepthHz = s.DepthHz;
            DepthSmooth = s.DepthTemporalSmoothPercent;
            EdgeClean = s.EdgeCleanPercent;
            Codec = CodecLabel(s.StreamCodec);
            Audio = s.AudioMode == Stream.AudioOutputMode.Headset ? "headset" : "pc";
            DepthPreset = s.DepthPreset == global::SpatialLauncher.Desktop.Core.DepthPreset.Movies
                ? "movies"
                : "gaming";
        }
    }
}
