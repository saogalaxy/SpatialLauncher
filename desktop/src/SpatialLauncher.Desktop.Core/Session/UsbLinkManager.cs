using System.Diagnostics;

namespace SpatialLauncher.Desktop.Core.Session;

/// <summary>
/// Quest USB link: TCP port forward over adb so the headset reaches the PC
/// stream without Wi-Fi (Quest connects to its own localhost:8765).
/// Video + settings/reader HTTP ride the forward; Opus audio is UDP and
/// cannot adb-forward, so headset audio stays on Wi-Fi.
/// </summary>
public static class UsbLinkManager
{
    public const int Port = 8765;

    public static string? FindAdb()
    {
        var candidates = new List<string> { "adb" };
        try
        {
            string local = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
            candidates.Add(Path.Combine(local, "Android", "Sdk", "platform-tools", "adb.exe"));
            string? androidHome = Environment.GetEnvironmentVariable("ANDROID_HOME");
            if (!string.IsNullOrWhiteSpace(androidHome))
                candidates.Add(Path.Combine(androidHome, "platform-tools", "adb.exe"));
            string programFiles = Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles);
            candidates.Add(Path.Combine(programFiles, "Android", "platform-tools", "adb.exe"));
        }
        catch { /* ignore */ }
        foreach (var c in candidates)
        {
            try
            {
                var psi = new ProcessStartInfo
                {
                    FileName = c,
                    Arguments = "version",
                    UseShellExecute = false,
                    RedirectStandardOutput = true,
                    RedirectStandardError = true,
                    CreateNoWindow = true
                };
                using var proc = Process.Start(psi);
                if (proc == null) continue;
                if (proc.WaitForExit(5000) && proc.ExitCode == 0)
                    return c;
            }
            catch { /* try next */ }
        }
        return null;
    }

    public static async Task<(bool ok, string message)> EnsureForwardAsync()
    {
        string? adb = FindAdb();
        if (adb == null)
            return (false, "adb not found — install Android platform-tools and retry.");
        var (devicesExit, devicesOut) = await RunAsync(adb, "devices -l");
        if (devicesExit != 0)
            return (false, "adb devices failed.");
        string? serial = ParseDeviceSerial(devicesOut);
        if (serial == null)
            return (false, "No USB device — plug in Quest 3 (USB debugging on) and authorize it.");
        var (fwdExit, fwdErr) = await RunAsync(adb, $"-s {serial} forward tcp:{Port} tcp:{Port}");
        if (fwdExit != 0)
            return (false, "adb forward failed: " + fwdErr.Trim());
        return (true, $"USB link ready ({serial}) — on Quest use USB connect. Audio stays on Wi-Fi.");
    }

    /// <summary>
    /// Live check: device still attached AND the tcp:8765 forward still listed.
    /// </summary>
    public static async Task<(bool on, string detail)> CheckForwardAsync()
    {
        string? adb = FindAdb();
        if (adb == null)
            return (false, "no adb");
        var (_, devicesOut) = await RunAsync(adb, "devices -l");
        string? serial = ParseDeviceSerial(devicesOut);
        if (serial == null)
            return (false, "no device");
        var (fwdExit, fwdOut) = await RunAsync(adb, $"-s {serial} forward --list");
        if (fwdExit != 0)
            return (false, "forward query failed");
        foreach (var raw in fwdOut.Split('\n'))
        {
            string line = raw.Trim();
            if (line.StartsWith(serial, StringComparison.Ordinal)
                && line.Contains($"tcp:{Port}", StringComparison.Ordinal))
                return (true, serial);
        }
        return (false, "forward missing");
    }

    private static string? ParseDeviceSerial(string output)
    {
        foreach (var raw in output.Split('\n'))
        {
            string line = raw.Trim();
            if (string.IsNullOrEmpty(line) || line.StartsWith("List of", StringComparison.OrdinalIgnoreCase))
                continue;
            var parts = line.Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries);
            if (parts.Length >= 2 && parts[1].Equals("device", StringComparison.OrdinalIgnoreCase))
                return parts[0];
        }
        return null;
    }

    private static Task<(int exit, string output)> RunAsync(string file, string args)
    {
        return Task.Run(() =>
        {
            try
            {
                var psi = new ProcessStartInfo
                {
                    FileName = file,
                    Arguments = args,
                    UseShellExecute = false,
                    RedirectStandardOutput = true,
                    RedirectStandardError = true,
                    CreateNoWindow = true
                };
                using var proc = Process.Start(psi);
                if (proc == null) return (1, "no process");
                string stdout = proc.StandardOutput.ReadToEnd();
                string stderr = proc.StandardError.ReadToEnd();
                if (!proc.WaitForExit(15000)) return (1, "timeout");
                string output = string.IsNullOrWhiteSpace(stderr) ? stdout : stdout + "\n" + stderr;
                return (proc.ExitCode, output);
            }
            catch (Exception ex)
            {
                return (1, ex.Message);
            }
        });
    }
}
