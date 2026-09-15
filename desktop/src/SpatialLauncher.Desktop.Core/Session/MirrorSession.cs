using System.Drawing;
using SpatialLauncher.Desktop.Core.Capture;
using SpatialLauncher.Desktop.Core.Depth;
using SpatialLauncher.Desktop.Core.Discovery;
using SpatialLauncher.Desktop.Core.Stereo;
using SpatialLauncher.Desktop.Core.Stream;

namespace SpatialLauncher.Desktop.Core.Session;

/// <summary>PC-first session: capture → depth → SBS → Quest Link + LAN discovery.</summary>
public sealed class MirrorSession : IDisposable
{
    private readonly FrameCaptureService _capture = new();
    private readonly QuestLinkServer _questLink = new();
    private readonly DiscoveryAdvertiser _discovery = new();
    private readonly AudioLinkStreamer _audio = new();
    private DepthEstimator? _depth;
    private UserSettings _settings = new();
    private readonly object _lock = new();
    private Bitmap? _latestSbs;
    private Thread? _processThread;
    private Thread? _depthThread;
    private volatile bool _processing;
    private long _lastDepthTick;
    private float[,]? _cachedDepth;
    private bool _audioHooked;
    private bool _questLinkStatusHooked;

    public event Action<Bitmap>? SbsFrameReady;
    public event Action<string>? StatusChanged;

    public bool IsRunning => _capture.IsRunning;
    public bool DepthModelLoaded => _depth?.HasOnnxModel == true;
    public string DepthModelLabel => _depth?.ModelLabel ?? "none";
    public string DepthDeviceLabel => _depth?.DeviceLabel ?? "none";
    private DepthPreset _loadedPreset;
    public FrameCaptureService Capture => _capture;
    public QuestLinkServer QuestLink => _questLink;
    public DiscoveryAdvertiser Discovery => _discovery;
    public AudioLinkStreamer Audio => _audio;
    public string? QuestLinkUrl => _questLink.AdvertiseUrl;

    public void ApplySettings(UserSettings settings)
    {
        _settings = settings;
        _capture.WorkWidth = Math.Clamp(settings.StreamWidth, 960, 3840);
        int q = Math.Clamp(settings.JpegQuality, 50, 98);
        if (settings.FullSbs)
            q = Math.Max(50, q - 8);
        _questLink.JpegQuality = q;
        _questLink.SharpenPercent = Math.Clamp(settings.SharpenPercent, 0, 80);
        bool codecChanged = _questLink.Codec != settings.StreamCodec;
        _questLink.Codec = settings.StreamCodec;
        if (codecChanged && _settings.AdvertiseOnLan && !string.IsNullOrEmpty(_questLink.AdvertiseUrl))
            _discovery.UpdateStream(_questLink.AdvertiseUrl);
        else if (_settings.AdvertiseOnLan && _discovery.IsRunning && !string.IsNullOrEmpty(_questLink.AdvertiseUrl))
            _discovery.UpdateStream(_questLink.AdvertiseUrl);

        if (_settings.AdvertiseOnLan)
            EnsureLinkListening();

        if (_processing)
            SyncAudioUnlocked();
        else if (_audio.IsRunning)
            _audio.ApplyMode(AudioOutputMode.Pc);
    }

    private void EnsureAudioHooked()
    {
        if (_audioHooked) return;
        _audio.StatusChanged += msg => StatusChanged?.Invoke(msg);
        _audioHooked = true;
    }

    private void SyncAudioUnlocked()
    {
        EnsureAudioHooked();
        try
        {
            _audio.ApplyMode(_settings.AudioMode);
        }
        catch (Exception ex)
        {
            StatusChanged?.Invoke("Audio: " + ex.Message);
        }
    }

    /// <summary>
    /// Start or heal Quest Link HTTP + LAN advertise without tearing down capture
    /// (quick resume after Quest drop / Stop Session).
    /// </summary>
    public void EnsureLinkListening(UserSettings? settings = null)
    {
        if (settings != null)
            _settings = settings;
        _questLink.Codec = _settings.StreamCodec;
        if (!_questLinkStatusHooked)
        {
            _questLink.StatusChanged += msg => StatusChanged?.Invoke(msg);
            _questLinkStatusHooked = true;
        }
        if (!_questLink.IsRunning)
        {
            try
            {
                _questLink.Start();
                StatusChanged?.Invoke("Quest Link listening · " + _questLink.AdvertiseUrl);
            }
            catch (Exception ex)
            {
                StatusChanged?.Invoke("Quest Link failed to bind: " + ex.Message);
                return;
            }
        }
        if (_settings.AdvertiseOnLan && !string.IsNullOrEmpty(_questLink.AdvertiseUrl))
            _discovery.Start(_questLink.AdvertiseUrl, Environment.MachineName);
    }

    /// <summary>Advertise on LAN; also ensures the HTTP listener is up.</summary>
    public void StartLanAdvertise(string? streamUrl = null)
    {
        if (!_settings.AdvertiseOnLan) return;
        EnsureLinkListening();
        string url = streamUrl ?? _questLink.AdvertiseUrl
                     ?? $"http://{DiscoveryAdvertiser.GetLanIpv4()}:{QuestLinkServer.DefaultPort}/"
                        + SessionSettingsJson.StreamPath(_settings.StreamCodec);
        _discovery.Start(url, Environment.MachineName);
        StatusChanged?.Invoke(
            _processing
                ? "LAN discovery on · streaming"
                : "LAN discovery on · Link ready (Start Session to send frames)");
    }

    public void StopLanAdvertise() => _discovery.Stop();

    public void Start(CaptureSourceInfo source, UserSettings settings, bool streamToQuest = true)
    {
        // Keep HTTP warm across Restart so Quest can rejoin without a port bounce.
        StopCaptureOnly();
        _settings = settings;
        _depth = new DepthEstimator(settings.DepthPreset);
        _loadedPreset = settings.DepthPreset;
        _questLink.Codec = _settings.StreamCodec;
        if (streamToQuest)
        {
            EnsureLinkListening();
            SyncAudioUnlocked();
        }
        else
        {
            _questLink.Stop();
            try { _audio.ApplyMode(AudioOutputMode.Pc); } catch { /* ignore */ }
        }

        if (_settings.AdvertiseOnLan && !string.IsNullOrEmpty(_questLink.AdvertiseUrl))
            _discovery.Start(_questLink.AdvertiseUrl, Environment.MachineName);

        string depthMsg = _depth.HasOnnxModel
            ? $"Session started · {_settings.DepthPreset} · {_depth.ModelLabel} · {_depth.DeviceLabel}"
            : "Session started · luminance depth fallback (CPU/RAM — installer did not load DA-V2 ONNX)";
        StatusChanged?.Invoke(depthMsg + $" · present {FramePacing.TargetFps} Hz");
        _capture.Start(source);
        _processing = true;
        _questLink.SessionActive = true;
        _lastDepthTick = 0;
        _cachedDepth = null;
        _depthThread = new Thread(DepthLoop) { IsBackground = true, Name = "SldDepth" };
        _depthThread.Start();
        _processThread = new Thread(ProcessLoop)
        {
            IsBackground = true,
            Name = "SldStereo"
        };
        _processThread.Start();
    }

    /// <summary>
    /// Stop capture/depth. By default keeps Quest Link listening so Find/Connect
    /// on the headset still work (quick resume after Start Session again).
    /// </summary>
    public void Stop(bool keepLinkListening = true)
    {
        StopCaptureOnly();
        if (keepLinkListening && _settings.AdvertiseOnLan)
        {
            EnsureLinkListening();
            StatusChanged?.Invoke("Session paused · Quest Link still listening");
        }
        else
        {
            _questLink.Stop();
            _discovery.Stop();
            StatusChanged?.Invoke("Session stopped");
        }
    }

    private void StopCaptureOnly()
    {
        _processing = false;
        _questLink.SessionActive = false;
        _capture.Stop();
        try { _audio.ApplyMode(AudioOutputMode.Pc); } catch { /* ignore */ }
        try { _processThread?.Join(400); } catch { /* ignore */ }
        try { _depthThread?.Join(400); } catch { /* ignore */ }
        _processThread = null;
        _depthThread = null;
        _depth?.Dispose();
        _depth = null;
        lock (_lock)
        {
            _latestSbs?.Dispose();
            _latestSbs = null;
        }
    }

    private void DepthLoop()
    {
        var sw = System.Diagnostics.Stopwatch.StartNew();
        while (_processing)
        {
            sw.Restart();
            if (!_settings.Live3D)
            {
                FramePacing.WaitRemainder(sw);
                continue;
            }

            int hz = Math.Clamp(_settings.DepthHz, 5, FramePacing.TargetFps);
            using var frame = _capture.CloneLatestFrame();
            if (frame != null)
            {
                try
                {
                    EnsureDepthMatchesSettings();
                    _cachedDepth = _depth!.Estimate(frame, _settings.DepthTemporalSmoothPercent);
                    _lastDepthTick = Environment.TickCount64;
                }
                catch (Exception ex)
                {
                    StatusChanged?.Invoke("Depth: " + ex.Message);
                }
            }

            long intervalTicks = System.Diagnostics.Stopwatch.Frequency / hz;
            long remain = intervalTicks - sw.ElapsedTicks;
            if (remain > 0)
            {
                int ms = (int)(remain * 1000 / System.Diagnostics.Stopwatch.Frequency);
                if (ms > 0)
                    Thread.Sleep(ms);
            }
        }
    }

    private void ProcessLoop()
    {
        long lastUiTick = 0;
        var sw = System.Diagnostics.Stopwatch.StartNew();
        while (_processing)
        {
            sw.Restart();
            using var frame = _capture.CloneLatestFrame();
            if (frame == null)
            {
                // Heal a dead listener without bouncing the whole session.
                if (_settings.AdvertiseOnLan && !_questLink.IsRunning)
                    EnsureLinkListening();
                FramePacing.WaitRemainder(sw);
                continue;
            }

            try
            {
                bool fullSbs = _settings.FullSbs;
                Bitmap sbs;
                var depth = _cachedDepth;
                if (_settings.Live3D && depth != null)
                {
                    sbs = SbsStereoRenderer.Render(
                        frame, depth, _settings.Divergence, _settings.Convergence, fullSbs,
                        _settings.DepthPreset);
                }
                else
                {
                    sbs = SbsStereoRenderer.RenderFlat(frame, fullSbs);
                }

                long uiNow = Environment.TickCount64;
                bool sendUi = uiNow - lastUiTick >= 200;
                if (_questLink.IsRunning)
                {
                    if (sendUi)
                    {
                        lastUiTick = uiNow;
                        var ui = (Bitmap)sbs.Clone();
                        SbsFrameReady?.Invoke(ui);
                    }
                    _questLink.PublishFrame(sbs);
                }
                else
                {
                    if (_settings.AdvertiseOnLan)
                        EnsureLinkListening();
                    if (sendUi)
                    {
                        lastUiTick = uiNow;
                        SbsFrameReady?.Invoke(sbs);
                    }
                    else
                    {
                        sbs.Dispose();
                    }
                }
            }
            catch (Exception ex)
            {
                StatusChanged?.Invoke("Frame error: " + ex.Message);
            }
            FramePacing.WaitRemainder(sw);
        }
    }

    /// <summary>
    /// Swap Gaming/Movies ONNX without tearing down the stream listener.
    /// </summary>
    private void EnsureDepthMatchesSettings()
    {
        var want = _settings.DepthPreset;
        if (_depth != null && _loadedPreset == want)
            return;

        StatusChanged?.Invoke($"Loading {want} depth model…");
        var next = new DepthEstimator(want);
        var old = _depth;
        _depth = next;
        _loadedPreset = want;
        old?.Dispose();
        StatusChanged?.Invoke(
            next.HasOnnxModel
                ? $"{want} ready · {next.ModelLabel} · {next.DeviceLabel}"
                : $"{want} · luminance fallback (CPU/RAM)");
    }

    public void Dispose()
    {
        Stop(keepLinkListening: false);
        _discovery.Dispose();
        _questLink.Dispose();
        _audio.Dispose();
        _capture.Dispose();
    }
}
