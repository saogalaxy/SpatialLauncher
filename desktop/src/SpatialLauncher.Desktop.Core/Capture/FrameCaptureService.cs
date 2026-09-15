using System.Diagnostics;
using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;
using System.Text;

namespace SpatialLauncher.Desktop.Core.Capture;

/// <summary>Enumerates monitors and top-level windows for Start Session.</summary>
public static class CaptureSourceCatalog
{
    public const int MaxWorkWidth = 1920;

    public static IReadOnlyList<CaptureSourceInfo> ListMonitors()
    {
        var list = new List<CaptureSourceInfo>();
        int i = 0;
        foreach (var screen in System.Windows.Forms.Screen.AllScreens)
        {
            var bounds = screen.Bounds;
            list.Add(new CaptureSourceInfo
            {
                Id = $"monitor:{i}",
                DisplayName = $"{screen.DeviceName} ({bounds.Width}x{bounds.Height})"
                    + (screen.Primary ? " · Primary" : ""),
                Kind = CaptureSourceKind.Monitor,
                MonitorIndex = i
            });
            i++;
        }
        return list;
    }

    public static IReadOnlyList<CaptureSourceInfo> ListWindows()
    {
        var list = new List<CaptureSourceInfo>();
        uint selfPid = (uint)Environment.ProcessId;
        EnumWindows((hwnd, _) =>
        {
            if (!IsWindowVisible(hwnd)) return true;
            if (GetWindow(hwnd, 4 /* GW_OWNER */) != IntPtr.Zero) return true;
            GetWindowThreadProcessId(hwnd, out uint pid);
            if (pid == selfPid) return true;
            int len = GetWindowTextLength(hwnd);
            if (len <= 0) return true;
            var sb = new StringBuilder(len + 1);
            GetWindowText(hwnd, sb, sb.Capacity);
            string title = sb.ToString().Trim();
            if (string.IsNullOrWhiteSpace(title)) return true;
            if (title is "Program Manager") return true;
            string proc = "";
            try { proc = Process.GetProcessById(unchecked((int)pid)).ProcessName; } catch { /* ignore */ }
            if (proc.Equals("SpatialLauncher.Desktop", StringComparison.OrdinalIgnoreCase))
                return true;
            list.Add(new CaptureSourceInfo
            {
                Id = $"hwnd:{(long)hwnd}",
                DisplayName = string.IsNullOrEmpty(proc) ? title : $"{title}  ({proc})",
                Kind = CaptureSourceKind.Window,
                Hwnd = hwnd
            });
            return true;
        }, IntPtr.Zero);
        return list.OrderBy(w => w.DisplayName, StringComparer.OrdinalIgnoreCase).ToList();
    }

    public static IReadOnlyList<CaptureSourceInfo> ListAll()
    {
        var all = new List<CaptureSourceInfo>();
        all.AddRange(ListMonitors());
        all.AddRange(ListWindows());
        return all;
    }

    private delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);

    [DllImport("user32.dll")]
    private static extern bool EnumWindows(EnumWindowsProc lpEnumFunc, IntPtr lParam);

    [DllImport("user32.dll")]
    private static extern bool IsWindowVisible(IntPtr hWnd);

    [DllImport("user32.dll")]
    private static extern IntPtr GetWindow(IntPtr hWnd, uint uCmd);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern int GetWindowText(IntPtr hWnd, StringBuilder lpString, int nMaxCount);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern int GetWindowTextLength(IntPtr hWnd);

    [DllImport("user32.dll")]
    private static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint lpdwProcessId);
}

/// <summary>GDI capture of a monitor or window, downscaled for the SBS pipeline.</summary>
public sealed class FrameCaptureService : IDisposable
{
    private CaptureSourceInfo? _source;
    private readonly object _lock = new();
    private Bitmap? _lastFrame;
    private bool _running;

    public event Action<Bitmap>? FrameCaptured;
    public int WorkWidth { get; set; } = CaptureSourceCatalog.MaxWorkWidth;

    public bool IsRunning => _running;
    public CaptureSourceInfo? CurrentSource => _source;

    public void Start(CaptureSourceInfo source)
    {
        Stop();
        _source = source ?? throw new ArgumentNullException(nameof(source));
        _running = true;
        var thread = new Thread(CaptureLoop)
        {
            IsBackground = true,
            Name = "SldCapture"
        };
        thread.Start();
    }

    public void Stop()
    {
        _running = false;
        lock (_lock)
        {
            _lastFrame?.Dispose();
            _lastFrame = null;
        }
        _source = null;
    }

    public Bitmap? CloneLatestFrame()
    {
        lock (_lock)
        {
            if (_lastFrame == null) return null;
            return (Bitmap)_lastFrame.Clone();
        }
    }

    private void CaptureLoop()
    {
        var sw = System.Diagnostics.Stopwatch.StartNew();
        while (_running && _source != null)
        {
            sw.Restart();
            try
            {
                var frame = GrabFrame(_source, WorkWidth);
                if (frame != null)
                {
                    lock (_lock)
                    {
                        _lastFrame?.Dispose();
                        _lastFrame = frame;
                    }
                }
            }
            catch
            {
                // Keep session alive on transient capture errors.
            }
            FramePacing.WaitRemainder(sw);
        }
    }

    private static Bitmap? GrabFrame(CaptureSourceInfo source, int maxWidth)
    {
        maxWidth = Math.Clamp(maxWidth, 960, 3840);
        if (source.Kind == CaptureSourceKind.Monitor)
            return GrabRegionScaled(MonitorBounds(source.MonitorIndex), maxWidth);
        if (source.Hwnd == IntPtr.Zero || !IsWindow(source.Hwnd))
            return null;
        if (!GetWindowRect(source.Hwnd, out RECT wnd) || wnd.Width <= 4 || wnd.Height <= 4)
            return null;
        var scaled = GrabRegionScaled(new Rectangle(wnd.Left, wnd.Top, wnd.Width, wnd.Height), maxWidth);
        if (scaled != null)
            return scaled;
        var printed = PrintWindowBitmap(source.Hwnd);
        if (printed == null) return null;
        var down = Downscale(printed, maxWidth);
        if (!ReferenceEquals(down, printed))
            printed.Dispose();
        return down;
    }

    private static Rectangle? MonitorBounds(int index)
    {
        var screens = System.Windows.Forms.Screen.AllScreens;
        if (index < 0 || index >= screens.Length) return null;
        return screens[index].Bounds;
    }

    private static Bitmap? GrabRegionScaled(Rectangle? boundsNullable, int maxWidth)
    {
        if (boundsNullable is not Rectangle bounds || bounds.Width <= 0 || bounds.Height <= 0)
            return null;

        int dstW = Math.Min(bounds.Width, maxWidth);
        int dstH = Math.Max(1, bounds.Height * dstW / bounds.Width);

        var hdcScreen = GetDC(IntPtr.Zero);
        if (hdcScreen == IntPtr.Zero) return null;
        IntPtr hdcMem = IntPtr.Zero;
        IntPtr hBitmap = IntPtr.Zero;
        IntPtr old = IntPtr.Zero;
        try
        {
            hdcMem = CreateCompatibleDC(hdcScreen);
            hBitmap = CreateCompatibleBitmap(hdcScreen, dstW, dstH);
            old = SelectObject(hdcMem, hBitmap);
            SetStretchBltMode(hdcMem, 4 /* HALFTONE */);
            bool ok = StretchBlt(
                hdcMem, 0, 0, dstW, dstH,
                hdcScreen, bounds.X, bounds.Y, bounds.Width, bounds.Height,
                0x00CC0020);
            if (!ok)
                return null;
            SelectObject(hdcMem, old);
            old = IntPtr.Zero;
            var bmp = Image.FromHbitmap(hBitmap);
            return bmp;
        }
        finally
        {
            if (old != IntPtr.Zero && hdcMem != IntPtr.Zero)
                SelectObject(hdcMem, old);
            if (hBitmap != IntPtr.Zero) DeleteObject(hBitmap);
            if (hdcMem != IntPtr.Zero) DeleteDC(hdcMem);
            ReleaseDC(IntPtr.Zero, hdcScreen);
        }
    }

    private static Bitmap? PrintWindowBitmap(IntPtr hwnd)
    {
        if (!GetClientRect(hwnd, out RECT client) || client.Width <= 0 || client.Height <= 0)
            return null;
        var hdcWindow = GetDC(hwnd);
        if (hdcWindow == IntPtr.Zero) return null;
        try
        {
            var hdcMem = CreateCompatibleDC(hdcWindow);
            var hBitmap = CreateCompatibleBitmap(hdcWindow, client.Width, client.Height);
            var old = SelectObject(hdcMem, hBitmap);
            PrintWindow(hwnd, hdcMem, 2);
            SelectObject(hdcMem, old);
            var bmp = Image.FromHbitmap(hBitmap);
            DeleteObject(hBitmap);
            DeleteDC(hdcMem);
            return bmp;
        }
        finally
        {
            ReleaseDC(hwnd, hdcWindow);
        }
    }

    public static Bitmap Downscale(Bitmap src, int maxWidth)
    {
        if (src.Width <= maxWidth) return src;
        int w = maxWidth;
        int h = Math.Max(1, src.Height * maxWidth / src.Width);
        var dst = new Bitmap(w, h, PixelFormat.Format32bppArgb);
        using var g = Graphics.FromImage(dst);
        g.InterpolationMode = System.Drawing.Drawing2D.InterpolationMode.Bilinear;
        g.DrawImage(src, 0, 0, w, h);
        return dst;
    }

    /// <summary>One-shot preview grab (no session required).</summary>
    public static Bitmap? CaptureOnce(CaptureSourceInfo source, int maxWidth = CaptureSourceCatalog.MaxWorkWidth)
        => GrabFrame(source, maxWidth);

    public void Dispose() => Stop();

    [StructLayout(LayoutKind.Sequential)]
    private struct RECT
    {
        public int Left, Top, Right, Bottom;
        public int Width => Right - Left;
        public int Height => Bottom - Top;
    }

    [DllImport("user32.dll")] private static extern bool IsWindow(IntPtr hWnd);
    [DllImport("user32.dll")] private static extern bool GetClientRect(IntPtr hWnd, out RECT lpRect);
    [DllImport("user32.dll")] private static extern bool GetWindowRect(IntPtr hWnd, out RECT lpRect);
    [DllImport("user32.dll")] private static extern IntPtr GetDC(IntPtr hWnd);
    [DllImport("user32.dll")] private static extern int ReleaseDC(IntPtr hWnd, IntPtr hDC);
    [DllImport("gdi32.dll")] private static extern IntPtr CreateCompatibleDC(IntPtr hdc);
    [DllImport("gdi32.dll")] private static extern IntPtr CreateCompatibleBitmap(IntPtr hdc, int nWidth, int nHeight);
    [DllImport("gdi32.dll")] private static extern IntPtr SelectObject(IntPtr hdc, IntPtr hgdiobj);
    [DllImport("gdi32.dll")] private static extern bool StretchBlt(
        IntPtr hdcDest, int xDest, int yDest, int wDest, int hDest,
        IntPtr hdcSrc, int xSrc, int ySrc, int wSrc, int hSrc, int rop);
    [DllImport("gdi32.dll")] private static extern int SetStretchBltMode(IntPtr hdc, int mode);
    [DllImport("gdi32.dll")] private static extern bool DeleteObject(IntPtr hObject);
    [DllImport("gdi32.dll")] private static extern bool DeleteDC(IntPtr hdc);
    [DllImport("user32.dll")] private static extern bool PrintWindow(IntPtr hwnd, IntPtr hdcBlt, uint nFlags);
}
