using System.Diagnostics;

namespace SpatialLauncher.Desktop.Core;

/// <summary>Hard floor for capture, stereo present, and Quest MJPEG.</summary>
public static class FramePacing
{
    public const int TargetFps = 72;
    public static readonly long FrameTicks = Math.Max(1, Stopwatch.Frequency / TargetFps);

    public static void WaitRemainder(Stopwatch sw)
    {
        long remain = FrameTicks - sw.ElapsedTicks;
        if (remain <= 0)
            return;

        long sleepMs = remain * 1000 / Stopwatch.Frequency;
        if (sleepMs > 1)
            Thread.Sleep((int)sleepMs - 1);

        while (sw.ElapsedTicks < FrameTicks)
            Thread.SpinWait(40);
    }
}
