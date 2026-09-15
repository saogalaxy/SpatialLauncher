using System.Windows;
using System.Windows.Input;
using System.Windows.Media.Imaging;

namespace SpatialLauncher.Desktop;

/// <summary>Fullscreen-friendly SBS window for Virtual Desktop / Bigscreen capture.</summary>
public partial class SbsViewerWindow : Window
{
    public SbsViewerWindow()
    {
        InitializeComponent();
        KeyDown += (_, e) =>
        {
            if (e.Key == Key.F11)
            {
                if (WindowStyle == WindowStyle.None)
                {
                    WindowStyle = WindowStyle.SingleBorderWindow;
                    WindowState = WindowState.Normal;
                    ResizeMode = ResizeMode.CanResize;
                }
                else
                {
                    WindowStyle = WindowStyle.None;
                    ResizeMode = ResizeMode.NoResize;
                    WindowState = WindowState.Maximized;
                }
            }
        };
    }

    public void SetFrame(BitmapSource frame)
    {
        ViewerImage.Source = frame;
        HintText.Visibility = Visibility.Collapsed;
    }
}
