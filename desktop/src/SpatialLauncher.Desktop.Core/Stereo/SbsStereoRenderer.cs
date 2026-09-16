using System.Drawing;
using System.Drawing.Imaging;
using SpatialLauncher.Desktop.Core;

namespace SpatialLauncher.Desktop.Core.Stereo;

/// <summary>
/// Warps a 2D frame into Half/Full SBS.
/// Gaming: fast backward sample. Movies: forward-fill + hole inpaint (iw3-inspired).
/// EdgeClean reduces silhouette halos by biasing discontinuities toward background depth.
/// </summary>
public static class SbsStereoRenderer
{
    public static Bitmap Render(
        Bitmap frame,
        float[,] depth01,
        double divergence,
        double convergence,
        bool fullSbs,
        DepthPreset preset = DepthPreset.Gaming,
        int edgeCleanPercent = 60)
    {
        // DA3 Movies still uses the proven backward warp for SBS packing.
        // Forward-fill is reserved for a later pass (it was blanking the right eye).
        return RenderGaming(frame, depth01, divergence, convergence, fullSbs, edgeCleanPercent);
    }

    private static Bitmap RenderGaming(
        Bitmap frame,
        float[,] depth01,
        double divergence,
        double convergence,
        bool fullSbs,
        int edgeCleanPercent)
    {
        int srcW = frame.Width;
        int srcH = frame.Height;
        int eyeW = fullSbs ? srcW : Math.Max(1, srcW / 2);
        int eyeH = srcH;
        int outW = eyeW * 2;
        var output = new Bitmap(outW, eyeH, PixelFormat.Format32bppArgb);

        ComputeDepthStats(depth01, convergence, divergence, out float mean, out float convBias, out float div);
        float edgeClean = Math.Clamp(edgeCleanPercent / 100f, 0f, 1f);

        var srcData = frame.LockBits(
            new Rectangle(0, 0, srcW, srcH),
            ImageLockMode.ReadOnly,
            PixelFormat.Format32bppArgb);
        var dstData = output.LockBits(
            new Rectangle(0, 0, outW, eyeH),
            ImageLockMode.WriteOnly,
            PixelFormat.Format32bppArgb);
        try
        {
            IntPtr srcPtr = srcData.Scan0;
            IntPtr dstPtr = dstData.Scan0;
            int srcStride = srcData.Stride;
            int dstStride = dstData.Stride;
            int dw = depth01.GetLength(1);
            int dh = depth01.GetLength(0);
            Parallel.For(0, eyeH, y => WarpLineBackward(
                srcPtr, dstPtr, srcStride, dstStride,
                y, eyeW, eyeH, srcW, srcH, dw, dh,
                depth01, mean, convBias, div, edgeClean));
        }
        finally
        {
            frame.UnlockBits(srcData);
            output.UnlockBits(dstData);
        }
        return output;
    }

    /// <summary>
    /// Movies path: depth-ordered forward warp (fewer ghosts) + horizontal hole inpaint.
    /// Approximates iw3 forward_fill + light_inpaint without the ML weights.
    /// </summary>
    private static Bitmap RenderMovies(
        Bitmap frame,
        float[,] depth01,
        double divergence,
        double convergence,
        bool fullSbs)
    {
        int srcW = frame.Width;
        int srcH = frame.Height;
        int eyeW = fullSbs ? srcW : Math.Max(1, srcW / 2);
        int eyeH = srcH;
        int outW = eyeW * 2;
        var output = new Bitmap(outW, eyeH, PixelFormat.Format32bppArgb);

        ComputeDepthStats(depth01, convergence, divergence, out float mean, out float convBias, out float div);
        int dw = depth01.GetLength(1);
        int dh = depth01.GetLength(0);

        var srcData = frame.LockBits(
            new Rectangle(0, 0, srcW, srcH),
            ImageLockMode.ReadOnly,
            PixelFormat.Format32bppArgb);
        var dstData = output.LockBits(
            new Rectangle(0, 0, outW, eyeH),
            ImageLockMode.ReadWrite,
            PixelFormat.Format32bppArgb);
        try
        {
            unsafe
            {
                // Clear alpha=0 so holes are detectable.
                byte* dstBase = (byte*)dstData.Scan0;
                int dstStride = dstData.Stride;
                Parallel.For(0, eyeH, y =>
                {
                    byte* row = dstBase + y * dstStride;
                    for (int x = 0; x < outW; x++)
                        row[x * 4 + 3] = 0;
                });

                byte* srcBase = (byte*)srcData.Scan0;
                int srcStride = srcData.Stride;

                Parallel.For(0, eyeH, y =>
                {
                    float v = (y + 0.5f) / eyeH;
                    int sy = Math.Clamp((int)(v * srcH), 0, srcH - 1);
                    int dy = Math.Clamp((int)(v * dh), 0, dh - 1);
                    byte* srcRow = srcBase + sy * srcStride;
                    byte* dstRow = dstBase + y * dstStride;
                    float[] zL = new float[eyeW];
                    float[] zR = new float[eyeW];
                    for (int i = 0; i < eyeW; i++)
                    {
                        zL[i] = float.NegativeInfinity;
                        zR[i] = float.NegativeInfinity;
                    }

                    // Forward splat: nearer (higher disparity) wins.
                    for (int sx = 0; sx < srcW; sx++)
                    {
                        float u = (sx + 0.5f) / srcW;
                        int dx = Math.Clamp((int)(u * dw), 0, dw - 1);
                        float d = depth01[dy, dx];
                        float shiftAmt = div * (d - mean - convBias) * 0.04f;
                        int xl = (int)Math.Round((u + shiftAmt) * eyeW - 0.5f);
                        int xr = (int)Math.Round((u - shiftAmt) * eyeW - 0.5f);
                        byte* sp = srcRow + sx * 4;

                        if ((uint)xl < (uint)eyeW && d >= zL[xl])
                        {
                            zL[xl] = d;
                            byte* dp = dstRow + xl * 4;
                            dp[0] = sp[0]; dp[1] = sp[1]; dp[2] = sp[2]; dp[3] = 255;
                        }
                        if ((uint)xr < (uint)eyeW && d >= zR[xr])
                        {
                            zR[xr] = d;
                            byte* dp = dstRow + (eyeW + xr) * 4;
                            dp[0] = sp[0]; dp[1] = sp[1]; dp[2] = sp[2]; dp[3] = 255;
                        }
                    }

                    InpaintEyeRow(dstRow, 0, eyeW);
                    InpaintEyeRow(dstRow, eyeW, eyeW);
                });
            }
        }
        finally
        {
            frame.UnlockBits(srcData);
            output.UnlockBits(dstData);
        }
        return output;
    }

    private static unsafe void InpaintEyeRow(byte* dstRow, int x0, int eyeW)
    {
        // Left→right then right→left pull from nearest filled neighbor (light inpaint).
        int last = -1;
        for (int x = 0; x < eyeW; x++)
        {
            byte* p = dstRow + (x0 + x) * 4;
            if (p[3] != 0) { last = x; continue; }
            if (last < 0) continue;
            byte* s = dstRow + (x0 + last) * 4;
            p[0] = s[0]; p[1] = s[1]; p[2] = s[2]; p[3] = 255;
        }
        last = -1;
        for (int x = eyeW - 1; x >= 0; x--)
        {
            byte* p = dstRow + (x0 + x) * 4;
            if (p[3] != 0) { last = x; continue; }
            if (last < 0) continue;
            byte* s = dstRow + (x0 + last) * 4;
            p[0] = s[0]; p[1] = s[1]; p[2] = s[2]; p[3] = 255;
        }
        // Any remaining edge holes: black.
        for (int x = 0; x < eyeW; x++)
        {
            byte* p = dstRow + (x0 + x) * 4;
            if (p[3] == 0) { p[0] = p[1] = p[2] = 0; p[3] = 255; }
        }
    }

    public static Bitmap RenderFlat(Bitmap frame, bool fullSbs)
    {
        int srcW = frame.Width;
        int srcH = frame.Height;
        int eyeW = fullSbs ? srcW : Math.Max(1, srcW / 2);
        var output = new Bitmap(eyeW * 2, srcH, PixelFormat.Format32bppArgb);
        using var g = Graphics.FromImage(output);
        // Half-SBS downscale: bilinear avoids blocky eyes when stretched on Quest.
        g.InterpolationMode = System.Drawing.Drawing2D.InterpolationMode.HighQualityBilinear;
        g.PixelOffsetMode = System.Drawing.Drawing2D.PixelOffsetMode.HighQuality;
        g.DrawImage(frame, new Rectangle(0, 0, eyeW, srcH), 0, 0, srcW, srcH, GraphicsUnit.Pixel);
        g.DrawImage(frame, new Rectangle(eyeW, 0, eyeW, srcH), 0, 0, srcW, srcH, GraphicsUnit.Pixel);
        return output;
    }

    private static void ComputeDepthStats(
        float[,] depth01,
        double convergence,
        double divergence,
        out float mean,
        out float convBias,
        out float div)
    {
        int dh = depth01.GetLength(0);
        int dw = depth01.GetLength(1);
        double sum = 0;
        int n = 0;
        int yStep = Math.Max(1, dh / 32);
        int xStep = Math.Max(1, dw / 32);
        for (int y = 0; y < dh; y += yStep)
        for (int x = 0; x < dw; x += xStep)
        {
            sum += depth01[y, x];
            n++;
        }
        mean = n > 0 ? (float)(sum / n) : 0.5f;
        convBias = (float)((convergence - 0.5) * 0.4);
        div = (float)divergence;
    }

    private static unsafe void WarpLineBackward(
        IntPtr srcPtr,
        IntPtr dstPtr,
        int srcStride,
        int dstStride,
        int y,
        int eyeW,
        int eyeH,
        int srcW,
        int srcH,
        int dw,
        int dh,
        float[,] depth01,
        float mean,
        float convBias,
        float divergence,
        float edgeClean)
    {
        float v = (y + 0.5f) / eyeH;
        // Continuous coords so low-res depth (e.g. 518) does not stair-step into blocky pop.
        float depthY = Math.Clamp(v * Math.Max(1, dh - 1), 0f, Math.Max(0, dh - 1));
        float srcY = Math.Clamp(v * Math.Max(1, srcH - 1), 0f, Math.Max(0, srcH - 1));
        byte* srcBase = (byte*)srcPtr;
        byte* dstRow = (byte*)dstPtr + y * dstStride;
        WarpRowBackward(
            srcBase, srcStride, dstRow, 0, +1, eyeW, srcW, srcH, dw, dh,
            depth01, mean, convBias, divergence, edgeClean, srcY, depthY);
        WarpRowBackward(
            srcBase, srcStride, dstRow, eyeW, -1, eyeW, srcW, srcH, dw, dh,
            depth01, mean, convBias, divergence, edgeClean, srcY, depthY);
    }

    private static unsafe void WarpRowBackward(
        byte* srcBase,
        int srcStride,
        byte* dstRow,
        int dstX0,
        int direction,
        int eyeW,
        int srcW,
        int srcH,
        int dw,
        int dh,
        float[,] depth01,
        float mean,
        float convBias,
        float divergence,
        float edgeClean,
        float srcY,
        float depthY)
    {
        float depthMaxX = Math.Max(0, dw - 1);
        float texel = dw > 1 ? 1f : 0f;
        for (int x = 0; x < eyeW; x++)
        {
            float u = (x + 0.5f) / eyeW;
            float depthX = Math.Clamp(u * depthMaxX, 0f, depthMaxX);
            float d = SampleDepthBilinear(depth01, depthX, depthY, dw, dh);
            float dL = SampleDepthBilinear(depth01, Math.Max(0f, depthX - texel), depthY, dw, dh);
            float dR = SampleDepthBilinear(depth01, Math.Min(depthMaxX, depthX + texel), depthY, dw, dh);
            float edge = Math.Max(Math.Abs(d - dL), Math.Abs(d - dR));

            // At silhouettes, prefer farther depth and shrink parallax so foreground
            // doesn't stretch a bright halo into the background.
            float shiftScale = 1f;
            if (edgeClean > 0f && edge > 0.06f)
            {
                float t = Math.Clamp(edge * 5f, 0f, 1f) * edgeClean;
                float far = Math.Min(d, Math.Min(dL, dR));
                d = d * (1f - t) + far * t;
                shiftScale = 1f - 0.75f * t;
            }

            float shift = divergence * (d - mean - convBias) * direction * shiftScale;
            float srcU = u + shift * 0.04f;
            float srcX = Math.Clamp(srcU * Math.Max(1, srcW - 1), 0f, Math.Max(0, srcW - 1));
            byte* dstPx = dstRow + (dstX0 + x) * 4;
            SampleBgraBilinear(srcBase, srcStride, srcW, srcH, srcX, srcY, dstPx);
        }
    }

    private static float SampleDepthBilinear(float[,] depth, float x, float y, int w, int h)
    {
        if (w <= 1 || h <= 1)
            return depth[Math.Clamp((int)y, 0, h - 1), Math.Clamp((int)x, 0, w - 1)];

        int x0 = (int)x;
        int y0 = (int)y;
        int x1 = Math.Min(x0 + 1, w - 1);
        int y1 = Math.Min(y0 + 1, h - 1);
        float fx = x - x0;
        float fy = y - y0;
        float a = depth[y0, x0] * (1f - fx) + depth[y0, x1] * fx;
        float b = depth[y1, x0] * (1f - fx) + depth[y1, x1] * fx;
        return a * (1f - fy) + b * fy;
    }

    private static unsafe void SampleBgraBilinear(
        byte* srcBase,
        int srcStride,
        int srcW,
        int srcH,
        float x,
        float y,
        byte* dst)
    {
        if (srcW <= 1 || srcH <= 1)
        {
            int sx = Math.Clamp((int)x, 0, srcW - 1);
            int sy = Math.Clamp((int)y, 0, srcH - 1);
            byte* p = srcBase + sy * srcStride + sx * 4;
            dst[0] = p[0]; dst[1] = p[1]; dst[2] = p[2]; dst[3] = 255;
            return;
        }

        int x0 = (int)x;
        int y0 = (int)y;
        int x1 = Math.Min(x0 + 1, srcW - 1);
        int y1 = Math.Min(y0 + 1, srcH - 1);
        float fx = x - x0;
        float fy = y - y0;
        float ifx = 1f - fx;
        float ify = 1f - fy;

        byte* p00 = srcBase + y0 * srcStride + x0 * 4;
        byte* p10 = srcBase + y0 * srcStride + x1 * 4;
        byte* p01 = srcBase + y1 * srcStride + x0 * 4;
        byte* p11 = srcBase + y1 * srcStride + x1 * 4;

        for (int c = 0; c < 3; c++)
        {
            float top = p00[c] * ifx + p10[c] * fx;
            float bot = p01[c] * ifx + p11[c] * fx;
            dst[c] = (byte)(top * ify + bot * fy + 0.5f);
        }
        dst[3] = 255;
    }
}
