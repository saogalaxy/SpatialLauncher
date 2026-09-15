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
    private float[,]? _prevDepth;
    private readonly object _smoothLock = new();

    public bool HasOnnxModel => _session != null;
    public string ModelLabel => _modelLabel;
    public string DeviceLabel => _deviceLabel;
    public bool UsesMoviesPath => _maxScale;

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
            try { fresh = EstimateOnnx(frame); }
            catch { fresh = EstimateLuminance(frame); }
        }
        else
        {
            fresh = EstimateLuminance(frame);
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
                blended[y, x] = _prevDepth[y, x] * alpha + fresh[y, x] * (1f - alpha);
            _prevDepth = blended;
            return blended;
        }
    }

    private float[,] EstimateOnnx(Bitmap frame)
    {
        int size = _modelSize;
        using var resized = new Bitmap(frame, new Size(size, size));
        var input = new DenseTensor<float>(new[] { 1, 3, size, size });
        float[] mean = [0.485f, 0.456f, 0.406f];
        float[] std = [0.229f, 0.224f, 0.225f];
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
                for (int y = 0; y < size; y++)
                {
                    byte* row = basePtr + y * stride;
                    for (int x = 0; x < size; x++)
                    {
                        byte* px = row + x * 4;
                        input[0, 0, y, x] = ((px[2] / 255f) - mean[0]) / std[0];
                        input[0, 1, y, x] = ((px[1] / 255f) - mean[1]) / std[1];
                        input[0, 2, y, x] = ((px[0] / 255f) - mean[2]) / std[2];
                    }
                }
            }
        }
        finally
        {
            resized.UnlockBits(data);
        }

        using var results = _session!.Run(new[] { NamedOnnxValue.CreateFromTensor(_inputName, input) });
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
        bool invert = MeanIsFarther(raw);
        var depth = new float[h, w];
        if (_maxScale)
        {
            // iw3 Any_V3_Mono style: scale by max only (keeps outdoor/indoor relative pop).
            float scale = Math.Max(1e-6f, Math.Abs(max));
            for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
            {
                float n = raw[y, x] / scale;
                depth[y, x] = invert ? 1f - Math.Clamp(n, 0f, 1f) : Math.Clamp(n, 0f, 1f);
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

    /// <summary>If border (often sky/bg) is higher than center, larger = farther.</summary>
    private static bool MeanIsFarther(float[,] raw)
    {
        int h = raw.GetLength(0);
        int w = raw.GetLength(1);
        if (h < 8 || w < 8) return true;
        double border = 0, center = 0;
        int nb = 0, nc = 0;
        int y0 = h / 4, y1 = 3 * h / 4, x0 = w / 4, x1 = 3 * w / 4;
        for (int y = 0; y < h; y++)
        for (int x = 0; x < w; x++)
        {
            float v = raw[y, x];
            bool inCenter = y >= y0 && y < y1 && x >= x0 && x < x1;
            if (inCenter) { center += v; nc++; }
            else { border += v; nb++; }
        }
        if (nb == 0 || nc == 0) return true;
        return (border / nb) > (center / nc);
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
