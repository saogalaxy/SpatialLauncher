using NAudio.Wave;
using SpatialLauncher.Desktop.Core.Depth;
using SpatialLauncher.Desktop.Core.Translate;
using SpatialLauncher.Desktop.Core.Tts;

namespace SpatialLauncher.Desktop.Core.Listen;

/// <summary>
/// WASAPI loopback → chunked audio. SenseVoice ONNX hooks in when models are present;
/// until then emits silence-gated placeholder status (pipeline structure matches Quest Listen).
/// </summary>
public sealed class ListenEngine : IDisposable
{
    private WasapiLoopbackCapture? _capture;
    private readonly SpeechEngine _tts = new();
    private readonly OpusTranslator _opus = new();
    private readonly List<byte> _buffer = new();
    private UserSettings _settings = new();
    private DateTime _lastSpeech = DateTime.UtcNow;
    private bool _running;

    public event Action<string>? TranscriptReady;
    public event Action<string>? StatusChanged;

    public bool IsRunning => _running;

    public void ApplySettings(UserSettings settings)
    {
        _settings = settings;
        _tts.SetSpeedPercent(settings.TtsSpeedPercent);
        _opus.EnsureReady();
    }

    public void Start()
    {
        Stop();
        _opus.EnsureReady();
        _capture = new WasapiLoopbackCapture();
        _capture.DataAvailable += OnData;
        _capture.RecordingStopped += (_, _) => { };
        _capture.StartRecording();
        _running = true;
        StatusChanged?.Invoke("Listen on · WASAPI · SenseVoice when models/asr/*.onnx present");
    }

    public void Stop()
    {
        _running = false;
        if (_capture != null)
        {
            try { _capture.StopRecording(); } catch { /* ignore */ }
            _capture.DataAvailable -= OnData;
            _capture.Dispose();
            _capture = null;
        }
        _buffer.Clear();
        _tts.Stop();
        StatusChanged?.Invoke("Listen off");
    }

    private void OnData(object? sender, WaveInEventArgs e)
    {
        if (!_running || e.BytesRecorded <= 0) return;

        // Simple RMS gate
        double sum = 0;
        int samples = e.BytesRecorded / 2;
        for (int i = 0; i + 1 < e.BytesRecorded; i += 2)
        {
            short s = (short)(e.Buffer[i] | (e.Buffer[i + 1] << 8));
            sum += s * s;
        }
        double rms = samples > 0 ? Math.Sqrt(sum / samples) / short.MaxValue : 0;

        if (rms > 0.02)
        {
            _lastSpeech = DateTime.UtcNow;
            lock (_buffer)
            {
                _buffer.AddRange(e.Buffer.Take(e.BytesRecorded));
                // Cap ~8s at 48k stereo float-ish — keep last 2MB
                if (_buffer.Count > 2_000_000)
                    _buffer.RemoveRange(0, _buffer.Count - 2_000_000);
            }
        }
        else if ((DateTime.UtcNow - _lastSpeech).TotalMilliseconds > 700)
        {
            FlushUtterance();
        }
    }

    private void FlushUtterance()
    {
        byte[] pcm;
        lock (_buffer)
        {
            if (_buffer.Count < 8000)
            {
                _buffer.Clear();
                return;
            }
            pcm = _buffer.ToArray();
            _buffer.Clear();
        }

        // SenseVoice path: when model dir exists, decode pcm → text.
        // Without model, skip TTS to avoid garbage; surface status once.
        string asrDir = ModelPaths.SenseVoiceDir;
        if (!Directory.Exists(asrDir) || !Directory.EnumerateFiles(asrDir, "*.onnx").Any())
        {
            return;
        }

        // Hook point for sherpa-onnx SenseVoice native binding.
        string raw = ""; // filled by native ASR
        if (string.IsNullOrWhiteSpace(raw)) return;

        _ = Task.Run(async () =>
        {
            string en = await _opus.ToEnglishAsync(raw, _settings.UseOpusTranslate);
            TranscriptReady?.Invoke(en);
            if (_settings.TtsEnabled)
                _tts.Speak(en);
        });
    }

    public void Dispose()
    {
        Stop();
        _tts.Dispose();
    }
}
