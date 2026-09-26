using System.Drawing;
using System.Drawing.Imaging;
using Microsoft.ML.OnnxRuntime;
using Microsoft.ML.OnnxRuntime.Tensors;

namespace SpatialLauncher.Desktop.Core.Depth;

/// <summary>
/// Gaming: Depth Anything V2 Small (minmax). Movies: Depth Anything 3 (max-scale)
/// with DA-V2 Base fallback. Temporal smoothing optional.
/// </summary>
public sealed class DepthEstimator : IDisposable
{
    private readonly InferenceSession? _session;
    private readonly string _inputName;
    private readonly string _modelLabel;
    private readonly string _deviceLabel;
    private readonly int _modelSize;
    private readonly bool _maxScale;
    private readonly DepthInputLayout _inputLayout;
    private float[,]? _prevDepth;
    private readonly object _smoothLock = new();
    private int _polarityVote;
    private bool _polarityLocked;
    private readonly object _polarityLock = new();

    public bool HasOnnxModel => _session != null;
    public string ModelLabel => _modelLabel;
    public string DeviceLabel => _deviceLabel;
    public bool UsesMoviesPath => _maxScale;
    /// <summary>True when inference failed and brightness is standing in for depth.</summary>
    public bool FellBackToLuminance { get; private set; }

    public DepthEstimator(DepthPreset preset = DepthPreset.Gaming)
        : this(ModelPaths.DepthModelFor(preset), preset)
    {
    }

    public DepthEstimator(string? modelPath, DepthPreset preset = DepthPreset.Gaming)
    {
        modelPath ??= ModelPaths.DepthModelFor(preset);
        _modelLabel = Path.GetFileName(modelPath);
        _maxScale = preset == DepthPreset.Movies || ModelPaths.IsDa3Model(modelPath);
        _modelSize = 518;
        if (File.Exists(modelPath))
        {
            try
            {
                var opts = new SessionOptions();
                opts.AppendExecutionProvider_DML();
                opts.GraphOptimizationLevel = GraphOptimizationLevel.ORT_ENABLE_ALL;
                _session = new InferenceSession(modelPath, opts);
                _inputName = _session.InputMetadata.Keys.First();
                _modelSize = ReadInputSize(_session, _inputName, 518);
                _inputLayout = ReadInputLayout(_session, _inputName);
                _deviceLabel = "DirectML GPU/VRAM";
                return;
            }
            catch
            {
                try
                {
                    _session = new InferenceSession(modelPath);
                    _inputName = _session.InputMetadata.Keys.First();
                    _modelSize = ReadInputSize(_session, _inputName, 518);
                    _inputLayout = ReadInputLayout(_session, _inputName);
                    _deviceLabel = "CPU/RAM (ONNX, DirectML failed)";
                    return;
                }
                catch
                {
                    _session = null;
                }
            }
        }
        _inputName = "image";
        _modelLabel = "luminance-fallback";
        _deviceLabel = "CPU/RAM luminance (no ONNX)";
    }

    private static int ReadInputSize(InferenceSession session, string inputName, int fallback)
    {
        try
        {
            var dims = session.InputMetadata[inputName].Dimensions;
            if (dims.Length >= 4)
            {
                int a = (int)dims[^2];
                int b = (int)dims[^1];
                if (a > 0 && b > 0) return Math.Max(a, b);
                if (a > 0) return a;
                if (b > 0) return b;
            }
        }
        catch { /* ignore */ }
        return fallback;
    }

    /// <summary>Returns depth01[row,col] in 0..1 (near=1), optionally temporally smoothed.</summary>
    public float[,] Estimate(Bitmap frame, int temporalSmoothPercent = 0)
    {
        float[,] fresh;
        if (_session != null)
        {
            try { fresh = EstimateOnnx(frame); FellBackToLuminance = false; }
            catch { fresh = EstimateLuminance(frame); FellBackToLuminance = true; }
        }
        else
        {
            fresh = EstimateLuminance(frame);
            FellBackToLuminance = true;
        }

        if (temporalSmoothPercent <= 0)
        {
            lock (_smoothLock) { _prevDepth = fresh; }
            return fresh;
        }

        lock (_smoothLock)
        {
            float alpha = Math.Clamp(temporalSmoothPercent / 100f, 0f, 0.95f);
            if (_prevDepth == null
                || _prevDepth.GetLength(0) != fresh.GetLength(0)
                || _prevDepth.GetLength(1) != fresh.GetLength(1))
            {
                _prevDepth = fresh;
                return fresh;
            }

            int h = fresh.GetLength(0);
            int w = fresh.GetLength(1);
            var blended = new float[h, w];
            for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
            {
                float prev = _prevDepth[y, x];
                float next = fresh[y, x];
                // Large depth jumps (moving people) keep more of the new sample so
                // temporal blend does not leave a ghost trail / smear.
                float delta = Math.Abs(next - prev);
                float a = alpha;
                if (delta > 0.08f)
                    a *= 1f - Math.Clamp((delta - 0.08f) * 5f, 0f, 0.92f);
                blended[y, x] = prev * a + next * (1f - a);
            }
            _prevDepth = blended;
            return blended;
        }
    }

    private float[,] EstimateOnnx(Bitmap frame)
    {
        int size = _modelSize;
        // Bilinear resize — Bitmap(size) defaults to nearest and blockifies Movies depth.
        using var resized = new Bitmap(size, size, PixelFormat.Format32bppArgb);
        using (var g = Graphics.FromImage(resized))
        {
            g.InterpolationMode = System.Drawing.Drawing2D.InterpolationMode.HighQualityBilinear;
            g.PixelOffsetMode = System.Drawing.Drawing2D.PixelOffsetMode.HighQuality;
            g.DrawImage(frame, new Rectangle(0, 0, size, size));
        }

        // DA3 declares "image" as NHWC uint8 raw RGB, while DA-V2 declares
        // "pixel_values" as NCHW normalized float. Feeding DA3 the V2 layout
        // throws InvalidArgument and silently drops to the luminance fallback,
        // which warps by brightness. Build whatever the model actually asks for.
        float[] mean = [0.485f, 0.456f, 0.406f];
        float[] std = [0.229f, 0.224f, 0.225f];
        NamedOnnxValue input;
        var data = resized.LockBits(
            new Rectangle(0, 0, size, size),
            ImageLockMode.ReadOnly,
            PixelFormat.Format32bppArgb);
        try
        {
            unsafe
            {
                byte* basePtr = (byte*)data.Scan0;
                int stride = data.Stride;
                if (_inputLayout == DepthInputLayout.NhwcByte)
                {
                    var rawBytes = new DenseTensor<byte>(new[] { 1, size, size, 3 });
                    for (int y = 0; y < size; y++)
                    {
                        byte* row = basePtr + y * stride;
                        for (int x = 0; x < size; x++)
                        {
                            byte* px = row + x * 4;
                            rawBytes[0, y, x, 0] = px[2];
                            rawBytes[0, y, x, 1] = px[1];
                            rawBytes[0, y, x, 2] = px[0];
                        }
                    }
                    input = NamedOnnxValue.CreateFromTensor(_inputName, rawBytes);
                }
                else
                {
                    var norm = new DenseTensor<float>(new[] { 1, 3, size, size });
                    for (int y = 0; y < size; y++)
                    {
                        byte* row = basePtr + y * stride;
                        for (int x = 0; x < size; x++)
                        {
                            byte* px = row + x * 4;
                            norm[0, 0, y, x] = ((px[2] / 255f) - mean[0]) / std[0];
                            norm[0, 1, y, x] = ((px[1] / 255f) - mean[1]) / std[1];
                            norm[0, 2, y, x] = ((px[0] / 255f) - mean[2]) / std[2];
                        }
                    }
                    input = NamedOnnxValue.CreateFromTensor(_inputName, norm);
                }
            }
        }
        finally
        {
            resized.UnlockBits(data);
        }

        using var results = _session!.Run(new[] { input });
        var output = PickDepthTensor(results);
        int h = output.Dimensions.Length >= 3 ? (int)output.Dimensions[^2] : size;
        int w = output.Dimensions.Length >= 3 ? (int)output.Dimensions[^1] : size;
        float min = float.MaxValue, max = float.MinValue;
        var raw = new float[h, w];
        for (int y = 0; y < h; y++)
        for (int x = 0; x < w; x++)
        {
            float v = output.Dimensions.Length switch
            {
                4 => output[0, 0, y, x],
                3 => output[0, y, x],
                _ => output[y * w + x]
            };
            raw[y, x] = v;
            if (v < min) min = v;
            if (v > max) max = v;
        }

        // Depth Anything outputs are typically larger = farther → invert to near=1.
        bool invert = ResolveInvert(raw);
        var depth = new float[h, w];
        if (_maxScale)
        {
            // Movies/DA3: normalize over a robust 2-98 percentile window.
            // Dividing by the raw max clipped most of a letterboxed/vignetted
            // frame to one depth value, and the plateau borders warped into
            // hard seams that read as a bad lenticular.
            var (lo, hi) = RobustRange(raw, min, max);
            float range = Math.Max(1e-6f, hi - lo);
            for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
            {
                float n = Math.Clamp((raw[y, x] - lo) / range, 0f, 1f);
                depth[y, x] = invert ? 1f - n : n;
            }
        }
        else
        {
            float range = Math.Max(1e-6f, max - min);
            for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
            {
                float n = (raw[y, x] - min) / range;
                depth[y, x] = invert ? 1f - n : n;
            }
        }
        return depth;
    }

    /// <summary>Channel layout a depth model declares for its image input.</summary>
    private enum DepthInputLayout
    {
        /// <summary>DA-V2 style: [1,3,H,W] float32, ImageNet-normalized.</summary>
        NchwFloat,
        /// <summary>DA3 style: [1,H,W,3] uint8 raw RGB.</summary>
        NhwcByte,
    }

    /// <summary>
    /// Reads the declared input contract instead of assuming DA-V2's layout. DA3
    /// exports "image" as NHWC uint8; feeding it NCHW float makes every run throw
    /// and the session silently degrades to brightness-as-depth.
    /// </summary>
    private static DepthInputLayout ReadInputLayout(InferenceSession session, string inputName)
    {
        try
        {
            var meta = session.InputMetadata[inputName];
            if (meta.Dimensions.Length != 4) return DepthInputLayout.NchwFloat;
            bool isByte = meta.ElementType == typeof(byte);
            bool channelsLast = meta.Dimensions[3] == 3;
            bool channelsFirst = meta.Dimensions[1] == 3;
            if (isByte && channelsLast) return DepthInputLayout.NhwcByte;
            if (!isByte && channelsFirst) return DepthInputLayout.NchwFloat;
            // Ambiguous: fall back to the declared element type.
            return isByte ? DepthInputLayout.NhwcByte : DepthInputLayout.NchwFloat;
        }
        catch { return DepthInputLayout.NchwFloat; }
    }

    private static Tensor<float> PickDepthTensor(IDisposableReadOnlyCollection<DisposableNamedOnnxValue> results)
    {
        Tensor<float>? best = null;
        int bestArea = -1;
        foreach (var r in results)
        {
            var t = r.AsTensor<float>();
            if (t == null) continue;
            int area = 1;
            foreach (var d in t.Dimensions)
                if (d > 0) area *= (int)d;
            if (area > bestArea)
            {
                bestArea = area;
                best = t;
            }
        }
        return best ?? results.First().AsTensor<float>();
    }

    /// <summary>
    /// Robust low/high cut points (2nd/98th percentile) from a 1024-bin histogram.
    /// DA3 raw output has outlier tails, so a min/max window lets one far pixel
    /// dominate and squeeze the rest of the frame into a narrow, clipping band.
    /// </summary>
    private static (float lo, float hi) RobustRange(float[,] raw, float min, float max)
    {
        int h = raw.GetLength(0);
        int w = raw.GetLength(1);
        const int Bins = 1024;
        float span = max - min;
        if (span <= 1e-6f) return (min, min + 1e-6f);
        float scale = (Bins - 1) / span;
        var hist = new int[Bins];
        for (int y = 0; y < h; y++)
        for (int x = 0; x < w; x++)
            hist[Math.Clamp((int)((raw[y, x] - min) * scale), 0, Bins - 1)]++;

        int total = h * w;
        int loTarget = (int)(total * 0.02f);
        int hiTarget = (int)(total * 0.98f);
        int acc = 0, loBin = 0, hiBin = Bins - 1;
        bool gotLo = false;
        for (int b = 0; b < Bins; b++)
        {
            acc += hist[b];
            if (!gotLo && acc >= loTarget) { loBin = b; gotLo = true; }
            if (acc >= hiTarget) { hiBin = b; break; }
        }
        if (hiBin <= loBin) return (min, max);
        return (min + loBin / scale, min + hiBin / scale);
    }

    /// <summary>
    /// Sticky near/far polarity. Flipping this inverts the entire depth map, so the
    /// warp snaps to the opposite direction and pixels visibly tear — up to 30x a
    /// second in Movies. The first frame decides; after that only a decisive margin
    /// is allowed to switch.
    /// </summary>
    private bool ResolveInvert(float[,] raw)
    {
        float margin = FartherMargin(raw);
        lock (_polarityLock)
        {
            if (!_polarityLocked)
            {
                _polarityVote = margin > 0f ? 1 : -1;
                _polarityLocked = true;
            }
            else if (Math.Abs(margin) > 0.25f)
            {
                _polarityVote = margin > 0f ? 1 : -1;
            }
            return _polarityVote > 0;
        }
    }

    /// <summary>
    /// Signed evidence that larger = farther, scaled by the border spread so the
    /// threshold means the same thing on any frame. Letterbox bars and vignettes sit
    /// in the border ring, so on cinematic content this hovers near zero.
    /// </summary>
    private static float FartherMargin(float[,] raw)
    {
        int h = raw.GetLength(0);
        int w = raw.GetLength(1);
        if (h < 8 || w < 8) return 1f;
        double border = 0, center = 0;
        double bMin = double.MaxValue, bMax = double.MinValue;
        int nb = 0, nc = 0;
        int y0 = h / 4, y1 = 3 * h / 4, x0 = w / 4, x1 = 3 * w / 4;
        for (int y = 0; y < h; y++)
        for (int x = 0; x < w; x++)
        {
            float v = raw[y, x];
            bool inCenter = y >= y0 && y < y1 && x >= x0 && x < x1;
            if (inCenter) { center += v; nc++; }
            else
            {
                border += v; nb++;
                if (v < bMin) bMin = v;
                if (v > bMax) bMax = v;
            }
        }
        if (nb == 0 || nc == 0) return 1f;
        double spread = Math.Max(1e-6, bMax - bMin);
        return (float)((border / nb - center / nc) / spread);
    }

    private static float[,] EstimateLuminance(Bitmap frame)
    {
        int w = Math.Min(frame.Width, 320);
        int h = Math.Min(frame.Height, 180);
        using var small = new Bitmap(frame, new Size(w, h));
        var depth = new float[h, w];
        for (int y = 0; y < h; y++)
        for (int x = 0; x < w; x++)
        {
            var c = small.GetPixel(x, y);
            depth[y, x] = (0.299f * c.R + 0.587f * c.G + 0.114f * c.B) / 255f;
        }
        return depth;
    }

    public void Dispose() => _session?.Dispose();
}
