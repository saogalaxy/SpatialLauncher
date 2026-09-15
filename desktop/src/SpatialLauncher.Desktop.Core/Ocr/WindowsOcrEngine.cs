using System.Drawing;
using Windows.Graphics.Imaging;
using Windows.Media.Ocr;
using Windows.Storage.Streams;

namespace SpatialLauncher.Desktop.Core.Ocr;

public sealed class OcrZone
{
    /// <summary>Normalized 0..1 rect in capture space.</summary>
    public double X { get; set; }
    public double Y { get; set; }
    public double W { get; set; } = 1;
    public double H { get; set; } = 0.3;
}

public sealed class WindowsOcrEngine
{
    private readonly OcrEngine? _engine;

    public WindowsOcrEngine()
    {
        _engine = OcrEngine.TryCreateFromUserProfileLanguages()
                   ?? OcrEngine.TryCreateFromLanguage(new Windows.Globalization.Language("en"));
    }

    public async Task<string> RecognizeAsync(Bitmap frame, IReadOnlyList<OcrZone>? zones = null)
    {
        if (_engine == null) return "";
        using var crop = CropZones(frame, zones);
        using var stream = new InMemoryRandomAccessStream();
        crop.Save(stream.AsStreamForWrite(), System.Drawing.Imaging.ImageFormat.Png);
        await stream.FlushAsync();
        stream.Seek(0);
        var decoder = await BitmapDecoder.CreateAsync(stream);
        var softwareBitmap = await decoder.GetSoftwareBitmapAsync(
            BitmapPixelFormat.Bgra8, BitmapAlphaMode.Premultiplied);
        var result = await _engine.RecognizeAsync(softwareBitmap);
        return string.Join(" ", result.Lines.Select(l => l.Text)).Trim();
    }

    public static Bitmap CropZones(Bitmap frame, IReadOnlyList<OcrZone>? zones)
    {
        if (zones == null || zones.Count == 0)
        {
            // Default lower dialogue band (Quest parity).
            int y = (int)(frame.Height * 0.65);
            int h = frame.Height - y;
            return frame.Clone(new Rectangle(0, y, frame.Width, Math.Max(1, h)), frame.PixelFormat);
        }

        // Stack crops vertically for multi-zone.
        var crops = new List<Bitmap>();
        int maxW = 1;
        int totalH = 0;
        foreach (var z in zones)
        {
            int x = (int)(z.X * frame.Width);
            int y = (int)(z.Y * frame.Height);
            int w = Math.Max(1, (int)(z.W * frame.Width));
            int h = Math.Max(1, (int)(z.H * frame.Height));
            x = Math.Clamp(x, 0, frame.Width - 1);
            y = Math.Clamp(y, 0, frame.Height - 1);
            w = Math.Min(w, frame.Width - x);
            h = Math.Min(h, frame.Height - y);
            var piece = frame.Clone(new Rectangle(x, y, w, h), frame.PixelFormat);
            crops.Add(piece);
            maxW = Math.Max(maxW, w);
            totalH += h;
        }

        var stacked = new Bitmap(maxW, Math.Max(1, totalH));
        using (var g = Graphics.FromImage(stacked))
        {
            int yy = 0;
            foreach (var c in crops)
            {
                g.DrawImage(c, 0, yy);
                yy += c.Height;
                c.Dispose();
            }
        }
        return stacked;
    }
}
