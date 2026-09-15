using System.Drawing;
using System.Drawing.Imaging;

namespace SpatialLauncher.Desktop.Core.Stream;

/// <summary>Light unsharp mask (CPU) before JPEG encode.</summary>
internal static class ImageSharpen
{
    public static void Apply(Bitmap bmp, int percent)
    {
        float amount = Math.Clamp(percent, 0, 80) / 80f * 1.6f;
        if (amount <= 0.02f) return;
        if (bmp.PixelFormat != PixelFormat.Format32bppArgb)
            return;

        int w = bmp.Width;
        int h = bmp.Height;
        if (w < 3 || h < 3) return;

        var data = bmp.LockBits(new Rectangle(0, 0, w, h), ImageLockMode.ReadWrite, PixelFormat.Format32bppArgb);
        try
        {
            unsafe
            {
                byte* p = (byte*)data.Scan0;
                int stride = data.Stride;
                var copy = new byte[stride * h];
                System.Runtime.InteropServices.Marshal.Copy(data.Scan0, copy, 0, copy.Length);

                for (int y = 1; y < h - 1; y++)
                {
                    int row = y * stride;
                    for (int x = 1; x < w - 1; x++)
                    {
                        int i = row + x * 4;
                        for (int c = 0; c < 3; c++)
                        {
                            // 3x3 box blur vs center (unsharp).
                            int blur =
                                copy[i + c - stride - 4] + copy[i + c - stride] + copy[i + c - stride + 4]
                                + copy[i + c - 4] + copy[i + c] + copy[i + c + 4]
                                + copy[i + c + stride - 4] + copy[i + c + stride] + copy[i + c + stride + 4];
                            blur /= 9;
                            int center = copy[i + c];
                            int v = (int)(center + amount * (center - blur));
                            if (v < 0) v = 0;
                            else if (v > 255) v = 255;
                            p[i + c] = (byte)v;
                        }
                    }
                }
            }
        }
        finally
        {
            bmp.UnlockBits(data);
        }
    }
}
