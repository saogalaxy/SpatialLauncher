namespace SpatialLauncher.Desktop.Core.Depth;

public static class ModelPaths
{
    public static string Root
    {
        get
        {
            string local = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "SpatialLauncherDesktop", "models");
            Directory.CreateDirectory(local);
            return local;
        }
    }

    /// <summary>Gaming — Depth Anything V2 ViT-S (light).</summary>
    public static string DepthVitsOnnx => Path.Combine(Root, "depth_anything_v2_vits.onnx");

    /// <summary>Legacy Movies fallback — Depth Anything V2 ViT-B.</summary>
    public static string DepthVitbOnnx => Path.Combine(Root, "depth_anything_v2_vitb.onnx");

    /// <summary>Movies preferred — Depth Anything 3 Base (~376 MB).</summary>
    public static string DepthDa3BaseOnnx => Path.Combine(Root, "da3_base.onnx");

    /// <summary>Movies lighter DA3 if Base missing (~96 MB).</summary>
    public static string DepthDa3SmallOnnx => Path.Combine(Root, "da3_small.onnx");

    [Obsolete("Use DepthVitsOnnx")]
    public static string DepthAnythingV2Onnx => DepthVitsOnnx;

    public static string OpusJaEnDir => Path.Combine(Root, "translate", "jaen");
    public static string OpusZhEnDir => Path.Combine(Root, "translate", "zhen");
    public static string OpusKoEnDir => Path.Combine(Root, "translate", "koen");

    public static string PiperHighModel => Path.Combine(Root, "piper", "en_US-lessac-high.onnx");
    public static string PiperMediumModel => Path.Combine(Root, "piper", "en_US-lessac-medium.onnx");
    public static string PiperModel =>
        File.Exists(PiperHighModel) ? PiperHighModel : PiperMediumModel;

    public static string PiperExe => Path.Combine(Root, "piper", "piper.exe");
    public static string SenseVoiceDir => Path.Combine(Root, "asr");
    public static string PaddleOcrDir => Path.Combine(Root, "paddleocr");

    /// <summary>
    /// Gaming → DA-V2 Small. Movies → DA3 Base, else DA3 Small, else DA-V2 Base.
    /// </summary>
    public static string DepthModelFor(DepthPreset preset)
    {
        if (preset == DepthPreset.Movies)
        {
            if (File.Exists(DepthDa3BaseOnnx)) return DepthDa3BaseOnnx;
            if (File.Exists(DepthDa3SmallOnnx)) return DepthDa3SmallOnnx;
            if (File.Exists(DepthVitbOnnx)) return DepthVitbOnnx;
        }
        return DepthVitsOnnx;
    }

    public static bool IsDa3Model(string path)
    {
        string name = Path.GetFileName(path);
        return name.Contains("da3", StringComparison.OrdinalIgnoreCase);
    }
}
