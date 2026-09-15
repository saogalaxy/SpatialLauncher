using System.Diagnostics;
using System.Drawing;
using System.Text;
using SpatialLauncher.Desktop.Core.Depth;

namespace SpatialLauncher.Desktop.Core.Ocr;

public interface IOcrEngine
{
    string EngineName { get; }
    Task<string> RecognizeAsync(Bitmap frame, IReadOnlyList<OcrZone>? zones = null);
}

/// <summary>
/// Prefers PaddleOCR PP-OCRv5 when a local CLI/models pack is present; otherwise Windows.Media.Ocr.
/// </summary>
public sealed class CompositeOcrEngine : IOcrEngine, IDisposable
{
    private readonly WindowsOcrEngine _windows = new();
    private readonly string? _paddleCli;

    public string EngineName { get; private set; } = "Windows OCR";

    public CompositeOcrEngine()
    {
        _paddleCli = ResolvePaddleCli();
        if (_paddleCli != null)
            EngineName = "PaddleOCR PP-OCRv5";
    }

    public async Task<string> RecognizeAsync(Bitmap frame, IReadOnlyList<OcrZone>? zones = null)
    {
        if (_paddleCli != null)
        {
            try
            {
                string text = await RunPaddleAsync(frame, zones);
                if (!string.IsNullOrWhiteSpace(text))
                {
                    EngineName = "PaddleOCR PP-OCRv5";
                    return text;
                }
            }
            catch
            {
                // fall through
            }
        }

        EngineName = "Windows OCR";
        return await _windows.RecognizeAsync(frame, zones);
    }

    private async Task<string> RunPaddleAsync(Bitmap frame, IReadOnlyList<OcrZone>? zones)
    {
        string tempDir = Path.Combine(Path.GetTempPath(), "sld-ocr");
        Directory.CreateDirectory(tempDir);
        string imgPath = Path.Combine(tempDir, Guid.NewGuid().ToString("N") + ".png");
        string outPath = Path.Combine(tempDir, Guid.NewGuid().ToString("N") + ".txt");
        try
        {
            using var crop = WindowsOcrEngine.CropZones(frame, zones);
            crop.Save(imgPath, System.Drawing.Imaging.ImageFormat.Png);

            var psi = new ProcessStartInfo
            {
                FileName = _paddleCli!,
                Arguments = $"--image \"{imgPath}\" --out \"{outPath}\"",
                UseShellExecute = false,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                CreateNoWindow = true,
                WorkingDirectory = ModelPaths.PaddleOcrDir
            };
            using var proc = Process.Start(psi);
            if (proc == null) return "";
            await proc.WaitForExitAsync();
            if (File.Exists(outPath))
                return (await File.ReadAllTextAsync(outPath, Encoding.UTF8)).Trim();
            return (await proc.StandardOutput.ReadToEndAsync()).Trim();
        }
        finally
        {
            try { File.Delete(imgPath); } catch { /* ignore */ }
            try { File.Delete(outPath); } catch { /* ignore */ }
        }
    }

    private static string? ResolvePaddleCli()
    {
        string[] candidates =
        [
            Path.Combine(ModelPaths.PaddleOcrDir, "paddleocr_infer.exe"),
            Path.Combine(ModelPaths.PaddleOcrDir, "ppocr.exe"),
            Path.Combine(ModelPaths.Root, "paddleocr", "paddleocr_infer.exe")
        ];
        foreach (var c in candidates)
        {
            if (File.Exists(c)) return c;
        }
        // Models present without CLI → still report Windows until CLI is installed.
        return null;
    }

    public void Dispose() { }
}
