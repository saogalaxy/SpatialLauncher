using System.Drawing;
using SpatialLauncher.Desktop.Core.Ocr;
using SpatialLauncher.Desktop.Core.Translate;
using SpatialLauncher.Desktop.Core.Tts;

namespace SpatialLauncher.Desktop.Core.Reader;

/// <summary>PaddleOCR/Windows OCR → optional OPUS → Piper/SAPI (PC-first reader).</summary>
public sealed class ReaderPipeline : IDisposable
{
    private readonly CompositeOcrEngine _ocr = new();
    private readonly OpusTranslator _opus = new();
    private readonly SpeechEngine _tts = new();
    private readonly DialogueDeduper _deduper = new();
    private readonly OcrZoneStore _zoneStore = new();
    private UserSettings _settings = new();
    private IReadOnlyList<OcrZone> _zones = Array.Empty<OcrZone>();
    private CancellationTokenSource? _cts;
    private string _shareCaption = "";

    public event Action<string>? CaptionChanged;
    public event Action<string>? StatusChanged;

    public string ShareCaption => _shareCaption;
    public string OcrEngineName => _ocr.EngineName;
    public string TtsBackendName => _tts.BackendName;

    public ReaderPipeline()
    {
        _zones = _zoneStore.Load();
    }

    public bool IsRunning => _cts != null && !_cts.IsCancellationRequested;

    public void ApplySettings(UserSettings settings)
    {
        _settings = settings;
        _tts.SetSpeedPercent(settings.TtsSpeedPercent);
        if (settings.CaptionsEnabled || settings.TtsEnabled)
            _opus.EnsureReady();
    }

    public void SetZones(IReadOnlyList<OcrZone> zones)
    {
        _zones = zones;
        _zoneStore.Save(zones);
    }

    /// <summary>
    /// Controller one-shot (Quest A button): OCR the current frame and speak
    /// it once, bypassing the settle/dedupe of the continuous loop. Explicit
    /// user action, so it speaks even when continuous TTS is off.
    /// </summary>
    public async Task<string> SpeakOnceAsync()
    {
        try
        {
            if (FrameProvider == null)
                return "";
            using var frame = FrameProvider();
            if (frame == null)
                return "";
            string raw = await _ocr.RecognizeAsync(frame, _zones);
            raw = raw.Replace('\n', ' ').Trim();
            if (string.IsNullOrEmpty(raw))
                return "";
            bool translate = _settings.UseOpusTranslate
                             && _settings.AssistMode is AssistMode.Translate or AssistMode.Share;
            string spoken = translate
                ? await _opus.ToEnglishAsync(raw, true)
                : await _opus.ToEnglishAsync(raw, false);
            _shareCaption = spoken;
            CaptionChanged?.Invoke(spoken);
            // Explicit button press: force re-speak even if the text matches
            // the last utterance (SpeechEngine drops identical repeats).
            _tts.Speak(spoken, force: true);
            StatusChanged?.Invoke("Spoke once: " + Truncate(spoken, 80));
            return spoken;
        }
        catch (Exception ex)
        {
            StatusChanged?.Invoke("Speak once: " + ex.Message);
            return "";
        }
    }

    public void Start()
    {
        Stop();
        _cts = new CancellationTokenSource();
        StatusChanged?.Invoke($"Reader · OCR={_ocr.EngineName} · TTS={_tts.BackendName} · {_opus.Status}");
        _ = LoopAsync(_cts.Token);
    }

    public void Stop()
    {
        _cts?.Cancel();
        _cts = null;
        _tts.Stop();
    }

    public Func<Bitmap?>? FrameProvider { get; set; }

    private async Task LoopAsync(CancellationToken ct)
    {
        int settleMs = 200 + (int)(_settings.OcrSmoothnessPercent / 100.0 * 800);
        string pending = "";
        DateTime lastChange = DateTime.UtcNow;

        while (!ct.IsCancellationRequested)
        {
            try
            {
                if (_settings.AssistMode == AssistMode.Listen)
                {
                    await Task.Delay(200, ct);
                    continue;
                }

                bool wantOcr = _settings.CaptionsEnabled
                               || (_settings.TtsEnabled && _settings.TtsContinuous);

                if (!wantOcr || FrameProvider == null)
                {
                    await Task.Delay(200, ct);
                    continue;
                }

                using var frame = FrameProvider();
                if (frame == null)
                {
                    await Task.Delay(100, ct);
                    continue;
                }

                string raw = await _ocr.RecognizeAsync(frame, _zones);
                raw = raw.Replace('\n', ' ').Trim();
                if (string.IsNullOrEmpty(raw))
                {
                    await Task.Delay(settleMs / 2, ct);
                    continue;
                }

                if (raw != pending)
                {
                    pending = raw;
                    lastChange = DateTime.UtcNow;
                }

                if ((DateTime.UtcNow - lastChange).TotalMilliseconds < settleMs)
                {
                    await Task.Delay(50, ct);
                    continue;
                }

                bool translate = _settings.UseOpusTranslate
                                 && _settings.AssistMode is AssistMode.Translate or AssistMode.Share;
                string spoken = await _opus.ToEnglishAsync(pending, translate);

                if (_settings.CaptionsEnabled)
                {
                    _shareCaption = spoken;
                    CaptionChanged?.Invoke(spoken);
                }

                if (_settings.TtsEnabled && _settings.TtsContinuous
                    && _deduper.TryAccept(spoken, out _))
                {
                    _tts.Speak(spoken);
                    StatusChanged?.Invoke("Spoke: " + Truncate(spoken, 80));
                }

                pending = "";
                await Task.Delay(Math.Max(80, settleMs / 3), ct);
            }
            catch (OperationCanceledException) { break; }
            catch (Exception ex)
            {
                StatusChanged?.Invoke("Reader: " + ex.Message);
                await Task.Delay(300, ct);
            }
        }
    }

    private static string Truncate(string s, int n) =>
        s.Length <= n ? s : s[..(n - 1)] + "…";

    public void Dispose()
    {
        Stop();
        _tts.Dispose();
        _ocr.Dispose();
    }
}
