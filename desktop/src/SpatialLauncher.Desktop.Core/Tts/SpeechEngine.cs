using System.Diagnostics;
using System.Speech.Synthesis;
using SpatialLauncher.Desktop.Core.Depth;

namespace SpatialLauncher.Desktop.Core.Tts;

/// <summary>Piper (lessac-high preferred) when packed; Windows SAPI fallback.</summary>
public sealed class SpeechEngine : IDisposable
{
    private readonly SpeechSynthesizer _sapi = new();
    private readonly object _lock = new();
    private string _lastSpoken = "";
    private int _speedPercent = 100;

    public string BackendName { get; private set; } = "SAPI";

    public SpeechEngine()
    {
        _sapi.Rate = 0;
        _sapi.Volume = 100;
        RefreshBackend();
    }

    public void RefreshBackend()
    {
        BackendName = File.Exists(ModelPaths.PiperExe) && File.Exists(ModelPaths.PiperModel)
            ? (File.Exists(ModelPaths.PiperHighModel) ? "Piper lessac-high" : "Piper lessac-medium")
            : "SAPI";
    }

    public void SetSpeedPercent(int percent)
    {
        _speedPercent = Math.Clamp(percent, 50, 200);
        int rate = (int)Math.Round((_speedPercent - 100) / 10.0);
        _sapi.Rate = Math.Clamp(rate, -10, 10);
    }

    public bool Speak(string line, bool force = false)
    {
        if (string.IsNullOrWhiteSpace(line)) return false;
        lock (_lock)
        {
            if (!force && line == _lastSpoken) return false;
            _lastSpoken = line;
            RefreshBackend();
            if (BackendName.StartsWith("Piper", StringComparison.Ordinal)
                && TrySpeakPiper(line))
                return true;

            BackendName = "SAPI";
            _sapi.SpeakAsyncCancelAll();
            _sapi.SpeakAsync(line);
            return true;
        }
    }

    private bool TrySpeakPiper(string line)
    {
        try
        {
            string wav = Path.Combine(Path.GetTempPath(), "sld-piper-" + Guid.NewGuid().ToString("N") + ".wav");
            string lengthScale = (100.0 / _speedPercent).ToString("0.###", System.Globalization.CultureInfo.InvariantCulture);
            var psi = new ProcessStartInfo
            {
                FileName = ModelPaths.PiperExe,
                Arguments = $"--model \"{ModelPaths.PiperModel}\" --output_file \"{wav}\" --length_scale {lengthScale}",
                UseShellExecute = false,
                RedirectStandardInput = true,
                RedirectStandardError = true,
                CreateNoWindow = true
            };
            using var proc = Process.Start(psi);
            if (proc == null) return false;
            proc.StandardInput.Write(line);
            proc.StandardInput.Close();
            if (!proc.WaitForExit(30_000))
            {
                try { proc.Kill(); } catch { /* ignore */ }
                return false;
            }
            if (!File.Exists(wav) || new FileInfo(wav).Length < 44) return false;

            _ = Task.Run(() =>
            {
                try
                {
                    using var reader = new NAudio.Wave.WaveFileReader(wav);
                    using var output = new NAudio.Wave.WaveOutEvent();
                    output.Init(reader);
                    output.Play();
                    while (output.PlaybackState == NAudio.Wave.PlaybackState.Playing)
                        Thread.Sleep(20);
                }
                finally
                {
                    try { File.Delete(wav); } catch { /* ignore */ }
                }
            });
            return true;
        }
        catch
        {
            return false;
        }
    }

    public void Stop()
    {
        lock (_lock)
        {
            _sapi.SpeakAsyncCancelAll();
        }
    }

    public void Dispose()
    {
        Stop();
        _sapi.Dispose();
    }
}
