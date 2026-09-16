namespace SpatialLauncher.Desktop.Core;

public enum DepthPreset
{
    /// <summary>DA-V2 ViT-S — lighter GPU share with games.</summary>
    Gaming = 0,
    /// <summary>DA3 Base (fallback DA3 Small / DA-V2 Base) + forward-fill SBS.</summary>
    Movies = 1
}

/// <summary>Quest Link transport — user-switchable.</summary>
public enum StreamCodec
{
    Mjpeg = 0,
    H264 = 1,
    Av1 = 2
}

public sealed class UserSettings
{
    public AssistMode AssistMode { get; set; } = AssistMode.Read;
    public bool UseOpusTranslate { get; set; } = true;
    public bool Live3D { get; set; } = true;
    public bool FullSbs { get; set; }
    public DepthPreset DepthPreset { get; set; } = DepthPreset.Gaming;
    public StreamCodec StreamCodec { get; set; } = StreamCodec.Mjpeg;
    public Stream.AudioOutputMode AudioMode { get; set; } = Stream.AudioOutputMode.Pc;
    /// <summary>
    /// Windows MMDevice ID for Spatial Launcher Audio (virtual sink). Empty = auto-pick.
    /// </summary>
    public string PreferredAudioSinkId { get; set; } = "";
    /// <summary>Friendly name shown for Spatial Launcher Audio (cached for UI).</summary>
    public string PreferredAudioSinkName { get; set; } = "";
    public double Divergence { get; set; } = 0.21;
    public double Convergence { get; set; } = 0.5;
    /// <summary>Target depth inferences per second (Gaming default ~20, Movies ~30).</summary>
    public int DepthHz { get; set; } = 20;
    /// <summary>0..100 temporal blend of new depth into previous map. High values smear moving people.</summary>
    public int DepthTemporalSmoothPercent { get; set; } = 25;
    /// <summary>0..100 cleans halos at depth edges (people vs background). Higher = less edge smear.</summary>
    public int EdgeCleanPercent { get; set; } = 60;
    public bool TtsEnabled { get; set; }
    public bool TtsContinuous { get; set; } = true;
    public int OcrSmoothnessPercent { get; set; } = 55;
    public int ListenSmoothnessPercent { get; set; } = 100;
    public int TtsSpeedPercent { get; set; } = 100;
    public bool RunInBackground { get; set; } = true;
    public bool AdvertiseOnLan { get; set; } = true;
    /// <summary>OCR captions in the desktop sidebar. Off unless the user turns it on.</summary>
    public bool CaptionsEnabled { get; set; }
    /// <summary>Capture/stream max width (each eye is half of this unless Full SBS).</summary>
    public int StreamWidth { get; set; } = 1920;
    /// <summary>JPEG quality 50–98 (also maps to H.264/AV1 bitrate).</summary>
    public int JpegQuality { get; set; } = 85;
    /// <summary>Unsharp amount 0–80 (JPEG only; skipped for MPEG/AV1).</summary>
    public int SharpenPercent { get; set; } = 25;

    public void ApplyDepthPresetDefaults()
    {
        if (DepthPreset == DepthPreset.Gaming)
        {
            DepthHz = 20;
            DepthTemporalSmoothPercent = 25;
            EdgeCleanPercent = 60;
        }
        else
        {
            DepthHz = 30;
            DepthTemporalSmoothPercent = 20;
            EdgeCleanPercent = 55;
        }
    }
}
