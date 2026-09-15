namespace SpatialLauncher.Desktop.Core;

public sealed class CaptureSourceInfo
{
    public required string Id { get; init; }
    public required string DisplayName { get; init; }
    public CaptureSourceKind Kind { get; init; }
    public IntPtr Hwnd { get; init; }
    public int MonitorIndex { get; init; } = -1;
}

public enum CaptureSourceKind
{
    Monitor,
    Window
}
