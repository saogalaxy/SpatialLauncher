using System.Drawing;
using System.Drawing.Imaging;

namespace SpatialLauncher.Desktop.Core.Stream;

internal static class JpegFrames
{
    private static readonly ImageCodecInfo? JpegCodec = ImageCodecInfo
        .GetImageEncoders()
        .FirstOrDefault(c => c.FormatID == ImageFormat.Jpeg.Guid);

    public static byte[] Encode(Bitmap bitmap, long quality)
    {
        using var ms = new MemoryStream();
        if (JpegCodec == null)
        {
            bitmap.Save(ms, ImageFormat.Jpeg);
            return ms.ToArray();
        }

        using var ep = new EncoderParameters(1);
        ep.Param[0] = new EncoderParameter(Encoder.Quality, Math.Clamp(quality, 40, 98));
        bitmap.Save(ms, JpegCodec, ep);
        return ms.ToArray();
    }
}
