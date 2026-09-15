using System.Windows;
using System.Windows.Threading;

namespace SpatialLauncher.Desktop;

public partial class App : System.Windows.Application
{
    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        DispatcherUnhandledException += OnDispatcherUnhandledException;
        AppDomain.CurrentDomain.UnhandledException += (_, args) =>
        {
            if (args.ExceptionObject is Exception ex)
                ShowFatal(ex);
        };
    }

    private void OnDispatcherUnhandledException(object sender, DispatcherUnhandledExceptionEventArgs e)
    {
        ShowFatal(e.Exception);
        e.Handled = true;
        Shutdown(1);
    }

    private static void ShowFatal(Exception ex)
    {
        try
        {
            System.Windows.MessageBox.Show(
                ex.GetBaseException().Message + "\n\n" + ex,
                "Spatial Launcher Desktop crashed",
                MessageBoxButton.OK,
                MessageBoxImage.Error);
        }
        catch
        {
            // ignore secondary failures while reporting
        }
    }
}
