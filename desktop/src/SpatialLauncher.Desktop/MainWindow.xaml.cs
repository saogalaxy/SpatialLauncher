using System.IO;
using System.Drawing;
using System.Drawing.Imaging;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using SpatialLauncher.Desktop.Core;
using SpatialLauncher.Desktop.Core.Capture;
using SpatialLauncher.Desktop.Core.Listen;
using SpatialLauncher.Desktop.Core.Reader;
using SpatialLauncher.Desktop.Core.Session;
using SpatialLauncher.Desktop.Core.Stream;
using Button = System.Windows.Controls.Button;
using Forms = System.Windows.Forms;

namespace SpatialLauncher.Desktop;

public partial class MainWindow : Window
{
    private readonly MirrorSession _session = new();
    private readonly ReaderPipeline _reader = new();
    private readonly ListenEngine _listen = new();
    private readonly UserSettingsStore _settingsStore = new();
    private UserSettings _settings;
    private SbsViewerWindow? _viewer;
    private AssistMode _mode = AssistMode.Read;
    private bool _useOpus = true;
    private bool _uiReady;
    private Forms.NotifyIcon? _tray;
    private readonly DispatcherTimer _sourceRefreshTimer = new() { Interval = TimeSpan.FromSeconds(3) };
    private string? _selectedSourceId;

    public MainWindow()
    {
        _settings = _settingsStore.Load();
        _mode = _settings.AssistMode;
        _useOpus = _settings.UseOpusTranslate;
        InitializeComponent();
        Loaded += OnLoaded;
        Closed += (_, _) => ShutdownAll();
    }

    private void OnLoaded(object sender, RoutedEventArgs e)
    {
        InitTray();
        RefreshSources(preserveSelection: false);
        _session.SbsFrameReady += OnSbsFrame;
        _session.StatusChanged += msg => Dispatcher.Invoke(() =>
        {
            StatusText.Text = msg;
            if (msg.StartsWith("Audio", StringComparison.OrdinalIgnoreCase))
            {
                RefreshAudioHint();
                UpdatePipelineLabel();
            }
        });
        _session.QuestLink.SettingsGetJson = () => SessionSettingsJson.ToJson(_settings);
        _session.QuestLink.SettingsApplyJson = json =>
        {
            if (!SessionSettingsJson.TryApply(json, _settings, out _))
                return null;
            // Apply on the accept thread immediately so codec/path hot-swap is live
            // before the HTTP 200 returns — do not wait for the UI dispatcher.
            _session.ApplySettings(_settings);
            Dispatcher.BeginInvoke(() =>
            {
                _uiReady = false;
                ApplyUiFromSettings();
                _uiReady = true;
                if (!string.IsNullOrEmpty(_session.QuestLinkUrl))
                    QuestLinkUrlBox.Text = _session.QuestLinkUrl;
                StatusText.Text = "Settings updated from Quest";
            });
            return SessionSettingsJson.ToJson(_settings);
        };
        _reader.CaptionChanged += text => Dispatcher.Invoke(() => ShareCaptionText.Text = text);
        _reader.StatusChanged += msg => Dispatcher.Invoke(() => StatusText.Text = msg);
        _listen.StatusChanged += msg => Dispatcher.Invoke(() => StatusText.Text = msg);
        _listen.TranscriptReady += text => Dispatcher.Invoke(() =>
        {
            ShareCaptionText.Text = text;
            StatusText.Text = "Listen: " + text;
        });
        _reader.FrameProvider = () => _session.Capture.CloneLatestFrame();
        ApplyUiFromSettings();
        UpdatePipelineLabel();
        _uiReady = true;
        SyncSettingsFromUi();
        if (_settings.AdvertiseOnLan)
            _session.StartLanAdvertise();
        _sourceRefreshTimer.Tick += (_, _) =>
        {
            if (!_session.IsRunning)
                RefreshSources(preserveSelection: true);
            // Heal a dead listener while the session is live (quick resume).
            else if (_settings.AdvertiseOnLan)
                _session.EnsureLinkListening(_settings);
        };
        _sourceRefreshTimer.Start();
        StatusText.Text = "Ready - Start Session, then tap Desktop Link on Quest (auto-find). X quits; minimize goes to tray.";
    }

    private void InitTray()
    {
        System.Drawing.Icon? icon = null;
        try
        {
            string ico = Path.Combine(AppContext.BaseDirectory, "Assets", "app.ico");
            if (File.Exists(ico))
                icon = new System.Drawing.Icon(ico);
        }
        catch { /* fallback */ }
        _tray = new Forms.NotifyIcon
        {
            Text = "Spatial Launcher Desktop",
            Visible = true,
            Icon = icon ?? System.Drawing.SystemIcons.Application
        };
        var menu = new Forms.ContextMenuStrip();
        menu.Items.Add("Show", null, (_, _) => RestoreFromTray());
        menu.Items.Add("Start Session", null, (_, _) => Dispatcher.Invoke(() => StartSession_Click(this, new RoutedEventArgs())));
        menu.Items.Add("Stop Session", null, (_, _) => Dispatcher.Invoke(() => StopSession_Click(this, new RoutedEventArgs())));
        menu.Items.Add("Quit", null, (_, _) => Dispatcher.Invoke(Close));
        _tray.ContextMenuStrip = menu;
        _tray.DoubleClick += (_, _) => RestoreFromTray();
    }

    private void RestoreFromTray()
    {
        Show();
        WindowState = WindowState.Normal;
        Activate();
    }

    private void Window_StateChanged(object? sender, EventArgs e)
    {
        // Minimize (_) -> tray when enabled
        if (!_uiReady || !_settings.RunInBackground) return;
        if (WindowState == WindowState.Minimized)
        {
            Hide();
            _tray?.ShowBalloonTip(1200, "Spatial Launcher Desktop",
                "In tray - streaming continues. Double-click tray icon to restore.", Forms.ToolTipIcon.Info);
        }
    }

    private void Window_Closing(object? sender, System.ComponentModel.CancelEventArgs e)
    {
        // X closes for real (shutdown). Tray is only via minimize.
        _sourceRefreshTimer.Stop();
    }

    private void RefreshSources_Click(object sender, RoutedEventArgs e) =>
        RefreshSources(preserveSelection: true);

    private void RefreshSources(bool preserveSelection)
    {
        string? keepId = preserveSelection ? _selectedSourceId : null;
        if (SourceList.SelectedItem is CaptureSourceInfo cur)
            keepId ??= cur.Id;

        var items = CaptureSourceCatalog.ListAll();
        SourceList.Items.Clear();
        foreach (var s in items)
            SourceList.Items.Add(s);

        int select = 0;
        if (!string.IsNullOrEmpty(keepId))
        {
            for (int i = 0; i < SourceList.Items.Count; i++)
            {
                if (SourceList.Items[i] is CaptureSourceInfo info && info.Id == keepId)
                {
                    select = i;
                    break;
                }
            }
        }
        if (SourceList.Items.Count > 0)
            SourceList.SelectedIndex = select;
    }

    private void SourceList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (SourceList.SelectedItem is not CaptureSourceInfo source) return;
        _selectedSourceId = source.Id;
        if (_session.IsRunning) return; // live SBS preview already updating
        ShowSourcePreview(source);
    }

    private void ShowSourcePreview(CaptureSourceInfo source)
    {
        try
        {
            using var frame = FrameCaptureService.CaptureOnce(source);
            if (frame == null)
            {
                PreviewHint.Text = "Could not capture that source.\nFullscreen exclusive games: pick the Primary display instead.";
                PreviewHint.Visibility = Visibility.Visible;
                PreviewImage.Source = null;
                return;
            }
            PreviewImage.Source = ToBitmapImage(frame);
            PreviewHint.Visibility = Visibility.Collapsed;
            StatusText.Text = "Preview: " + source.DisplayName;
        }
        catch (Exception ex)
        {
            PreviewHint.Text = "Preview failed: " + ex.Message;
            PreviewHint.Visibility = Visibility.Visible;
        }
    }

    private void StartSession_Click(object sender, RoutedEventArgs e)
    {
        if (SourceList.SelectedItem is not CaptureSourceInfo source)
        {
            StatusText.Text = "Select a monitor or window first.";
            return;
        }
        SyncSettingsFromUi();
        bool streamQuest = QuestLinkCheck.IsChecked == true;
        _session.Start(source, _settings, streamQuest);
        if (!string.IsNullOrEmpty(_session.QuestLinkUrl))
            QuestLinkUrlBox.Text = _session.QuestLinkUrl;
        if (_settings.RunInBackground)
        {
            WindowState = WindowState.Minimized;
            Hide();
            _tray?.ShowBalloonTip(1600, "Spatial Launcher Desktop",
                "Session running from the tray so this window is not captured on top of the source.",
                Forms.ToolTipIcon.Info);
        }
        _reader.ApplySettings(_settings);
        SyncReaderRunning();
        StartButton.IsEnabled = false;
        StopButton.IsEnabled = true;
        PreviewHint.Visibility = Visibility.Collapsed;
        // Do not auto-open SBS Viewer - Quest is the primary viewer.
    }

    private void StopSession_Click(object sender, RoutedEventArgs e)
    {
        _reader.Stop();
        _listen.Stop();
        _session.Stop(keepLinkListening: true);
        if (_settings.AdvertiseOnLan)
            _session.StartLanAdvertise();
        StartButton.IsEnabled = true;
        StopButton.IsEnabled = false;
        PreviewHint.Visibility = Visibility.Visible;
        PreviewHint.Text = "Select a source to preview - Start Session to stream";
        if (SourceList.SelectedItem is CaptureSourceInfo source)
            ShowSourcePreview(source);
    }

    private void OpenViewer_Click(object sender, RoutedEventArgs e) => EnsureViewer();

    private void EnsureViewer()
    {
        if (_viewer == null || !_viewer.IsLoaded)
        {
            _viewer = new SbsViewerWindow();
            _viewer.Closed += (_, _) => _viewer = null;
            _viewer.Show();
        }
        else
        {
            _viewer.Activate();
        }
    }

    private void OnSbsFrame(Bitmap sbs)
    {
        var bmp = sbs;
        bool needUi = IsVisible || (_viewer != null && _viewer.IsLoaded);
        if (!needUi)
        {
            bmp.Dispose();
            return;
        }
        Dispatcher.BeginInvoke(() =>
        {
            try
            {
                var image = ToBitmapImage(bmp);
                PreviewImage.Source = image;
                PreviewHint.Visibility = Visibility.Collapsed;
                _viewer?.SetFrame(image);
            }
            finally
            {
                bmp.Dispose();
            }
        });
    }

    private static BitmapImage ToBitmapImage(Bitmap bitmap)
    {
        using var ms = new MemoryStream();
        bitmap.Save(ms, ImageFormat.Jpeg);
        ms.Position = 0;
        var image = new BitmapImage();
        image.BeginInit();
        image.CacheOption = BitmapCacheOption.OnLoad;
        image.StreamSource = ms;
        image.EndInit();
        image.Freeze();
        return image;
    }

    private void DepthPreset_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not Button btn || btn.Tag is not string tag) return;
        _settings.DepthPreset = tag == "Movies" ? DepthPreset.Movies : DepthPreset.Gaming;
        _settings.ApplyDepthPresetDefaults();
        DepthHzSlider.Value = _settings.DepthHz;
        DepthSmoothSlider.Value = _settings.DepthTemporalSmoothPercent;
        StylePresetButtons();
        SyncSettingsFromUi();
    }

    private void Codec_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not Button btn || btn.Tag is not string tag) return;
        _settings.StreamCodec = tag switch
        {
            "h264" => StreamCodec.H264,
            "av1" => StreamCodec.Av1,
            _ => StreamCodec.Mjpeg
        };
        StyleCodecButtons();
        SyncSettingsFromUi();
        if (!string.IsNullOrEmpty(_session.QuestLinkUrl))
            QuestLinkUrlBox.Text = _session.QuestLinkUrl;
    }

    private void Audio_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not Button btn || btn.Tag is not string tag) return;
        _settings.AudioMode = tag == "headset"
            ? AudioOutputMode.Headset
            : AudioOutputMode.Pc;
        StyleAudioButtons();
        SyncSettingsFromUi();
    }

    private void SaveSection_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not Button btn || btn.Tag is not string section) return;
        SyncSettingsFromUi();
        try
        {
            _settingsStore.Save(_settings);
            string label = section switch
            {
                "stream" => "Stream",
                "audio" => "Sound",
                "look3d" => "3D look",
                "quest" => "Quest Link",
                "reader" => "Reader",
                _ => "Settings"
            };
            StatusText.Text = "Saved " + label + " settings";
            RefreshAudioHint();
            UpdatePipelineLabel();
        }
        catch (Exception ex)
        {
            StatusText.Text = "Save failed: " + ex.Message;
        }
    }

    private void Mode_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not Button btn || btn.Tag is not string tag) return;
        _mode = Enum.Parse<AssistMode>(tag);
        StyleModeButtons();
        SyncSettingsFromUi();
        UpdatePipelineLabel();

        if (_mode == AssistMode.Listen)
        {
            _reader.Stop();
            _listen.ApplySettings(_settings);
            if (!_listen.IsRunning) _listen.Start();
            Live3dCheck.IsChecked = false;
            SyncSettingsFromUi();
        }
        else
        {
            if (_listen.IsRunning) _listen.Stop();
            SyncReaderRunning();
        }
    }

    private void Engine_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not Button btn || btn.Tag is not string tag) return;
        _useOpus = tag == "opus";
        StyleEngineButtons();
        SyncSettingsFromUi();
        UpdatePipelineLabel();
    }

    private void SettingsChanged(object sender, RoutedEventArgs e)
    {
        if (!_uiReady) return;
        SyncSettingsFromUi();
        UpdatePipelineLabel();
    }

    private void SettingsChanged(object sender, RoutedPropertyChangedEventArgs<double> e)
    {
        if (!_uiReady) return;
        SyncSettingsFromUi();
        UpdatePipelineLabel();
    }

    private void SyncSettingsFromUi()
    {
        if (!_uiReady) return;
        _settings.AssistMode = _mode;
        _settings.UseOpusTranslate = _useOpus;
        _settings.Live3D = Live3dCheck.IsChecked == true;
        _settings.FullSbs = FullSbsCheck.IsChecked == true;
        _settings.Divergence = DepthStrengthSlider.Value / 100.0;
        _settings.Convergence = ConvergenceSlider.Value / 100.0;
        _settings.DepthHz = (int)DepthHzSlider.Value;
        _settings.DepthTemporalSmoothPercent = (int)DepthSmoothSlider.Value;
        _settings.EdgeCleanPercent = (int)EdgeCleanSlider.Value;
        _settings.TtsEnabled = TtsCheck.IsChecked == true;
        _settings.TtsContinuous = TtsContinuousCheck.IsChecked == true;
        _settings.CaptionsEnabled = CaptionsCheck.IsChecked == true;
        _settings.StreamWidth = (int)StreamWidthSlider.Value;
        _settings.JpegQuality = (int)JpegQualitySlider.Value;
        _settings.SharpenPercent = (int)SharpenSlider.Value;
        StreamWidthLabel.Text = "Stream width " + _settings.StreamWidth + " — capture size sent to Quest";
        if (_settings.StreamCodec is StreamCodec.H264 or StreamCodec.Av1)
        {
            int kbps = 2000 + (Math.Clamp(_settings.JpegQuality, 50, 98) - 50) * 250;
            string codecName = _settings.StreamCodec == StreamCodec.Av1 ? "AV1" : "H.264";
            JpegQualityLabel.Text = "Video quality " + _settings.JpegQuality + " (~" + kbps + " kbps " + codecName + ")";
        }
        else
        {
            JpegQualityLabel.Text = "JPEG quality " + _settings.JpegQuality + " — higher = sharper, bigger frames";
        }
        SharpenLabel.Text = "Sharpen " + _settings.SharpenPercent + " — edge crispness before encode";
        DepthStrengthLabel.Text = "3D pop " + (int)DepthStrengthSlider.Value + "% — how far things stick out";
        ConvergenceLabel.Text = "Focus plane " + (int)ConvergenceSlider.Value + "% — lower if eyestrain / screen feels too close";
        DepthHzLabel.Text = "Depth refresh " + _settings.DepthHz + " Hz — how often depth updates";
        DepthSmoothLabel.Text = "Motion ghosting " + _settings.DepthTemporalSmoothPercent + "% — lower = cleaner moving people";
        EdgeCleanLabel.Text = "Edge smear clean " + _settings.EdgeCleanPercent + "% — higher reduces halos around people";
        _settings.OcrSmoothnessPercent = (int)OcrSmoothSlider.Value;
        _settings.TtsSpeedPercent = (int)TtsSpeedSlider.Value;
        _settings.AdvertiseOnLan = LanAdvertiseCheck.IsChecked == true;
        _settings.RunInBackground = TrayCheck.IsChecked == true;
        _session.ApplySettings(_settings);
        if (!string.IsNullOrEmpty(_session.QuestLinkUrl))
            QuestLinkUrlBox.Text = _session.QuestLinkUrl;
        _reader.ApplySettings(_settings);
        _listen.ApplySettings(_settings);
        if (_uiReady)
            SyncReaderRunning();
    }

    private void SyncReaderRunning()
    {
        bool want = _session.IsRunning
                    && _mode != AssistMode.Listen
                    && (_settings.CaptionsEnabled || _settings.TtsEnabled);
        if (want)
        {
            if (!_reader.IsRunning)
                _reader.Start();
        }
        else if (_reader.IsRunning)
        {
            _reader.Stop();
            ShareCaptionText.Text = "(off — enable Captions / OCR)";
        }
    }

    private void ApplyUiFromSettings()
    {
        Live3dCheck.IsChecked = _settings.Live3D;
        FullSbsCheck.IsChecked = _settings.FullSbs;
        DepthStrengthSlider.Value = Math.Clamp(_settings.Divergence * 100, 10, 200);
        ConvergenceSlider.Value = Math.Clamp(_settings.Convergence * 100, 0, 100);
        DepthHzSlider.Value = _settings.DepthHz;
        DepthSmoothSlider.Value = _settings.DepthTemporalSmoothPercent;
        EdgeCleanSlider.Value = _settings.EdgeCleanPercent;
        TtsCheck.IsChecked = _settings.TtsEnabled;
        TtsContinuousCheck.IsChecked = _settings.TtsContinuous;
        CaptionsCheck.IsChecked = _settings.CaptionsEnabled;
        StreamWidthSlider.Value = _settings.StreamWidth;
        JpegQualitySlider.Value = _settings.JpegQuality;
        SharpenSlider.Value = _settings.SharpenPercent;
        OcrSmoothSlider.Value = _settings.OcrSmoothnessPercent;
        TtsSpeedSlider.Value = _settings.TtsSpeedPercent;
        LanAdvertiseCheck.IsChecked = _settings.AdvertiseOnLan;
        TrayCheck.IsChecked = _settings.RunInBackground;
        StyleModeButtons();
        StyleEngineButtons();
        StylePresetButtons();
        StyleCodecButtons();
        StyleAudioButtons();
    }

    private void StylePresetButtons()
    {
        SetChip(PresetGaming, _settings.DepthPreset == DepthPreset.Gaming);
        SetChip(PresetMovies, _settings.DepthPreset == DepthPreset.Movies);
    }

    private void StyleCodecButtons()
    {
        SetChip(CodecMjpeg, _settings.StreamCodec == StreamCodec.Mjpeg);
        SetChip(CodecH264, _settings.StreamCodec == StreamCodec.H264);
        SetChip(CodecAv1, _settings.StreamCodec == StreamCodec.Av1);
    }

    private void StyleAudioButtons()
    {
        SetChip(AudioPc, _settings.AudioMode == AudioOutputMode.Pc);
        SetChip(AudioHeadset, _settings.AudioMode == AudioOutputMode.Headset);
        RefreshAudioHint();
    }

    private void RefreshAudioHint()
    {
        string active = _session.Audio.ActiveSinkName ?? "";
        if (_settings.AudioMode == AudioOutputMode.Headset && !string.IsNullOrEmpty(active))
        {
            AudioSinkHint.Text = "Headset on · copying '" + active
                + "' to Quest. PC speakers stay on.";
        }
        else if (_settings.AudioMode == AudioOutputMode.Headset)
        {
            AudioSinkHint.Text = "Headset will copy Windows speakers to Quest (both play). Start Session, connect Quest, tap Headset.";
        }
        else
        {
            AudioSinkHint.Text = "PC speakers only. Tap Headset to also send the same mix to Quest.";
        }
    }

    private void StyleModeButtons()
    {
        SetChip(ModeRead, _mode == AssistMode.Read);
        SetChip(ModeTranslate, _mode == AssistMode.Translate);
        SetChip(ModeShare, _mode == AssistMode.Share);
        SetChip(ModeListen, _mode == AssistMode.Listen);
    }

    private void StyleEngineButtons()
    {
        SetChip(EngineOpus, _useOpus);
        SetChip(EngineOcr, !_useOpus);
    }

    private void SetChip(Button btn, bool active)
    {
        btn.Style = (Style)FindResource(active ? "ChipButtonActive" : "ChipButton");
    }

    private void UpdatePipelineLabel()
    {
        PipelineStatus.Text = _mode.PipelineLabel(_useOpus)
                              + (_settings.CaptionsEnabled || _settings.TtsEnabled
                                  ? $"\nOCR={_reader.OcrEngineName} · TTS={_reader.TtsBackendName}"
                                  : "\nCaptions/OCR off · 3D/cast only")
                              + $" · Depth={_settings.DepthPreset} @{_settings.DepthHz}Hz"
                              + $" · {_session.DepthDeviceLabel}"
                              + $" · stream {_settings.StreamWidth}px "
                              + (_settings.StreamCodec switch
                              {
                                  StreamCodec.H264 => "MPEG",
                                  StreamCodec.Av1 => "AV1",
                                  _ => "JPEG"
                              })
                              + $" q{_settings.JpegQuality} sharp{_settings.SharpenPercent}"
                              + (_settings.AudioMode == AudioOutputMode.Headset
                                  ? (!string.IsNullOrEmpty(_session.Audio.ActiveSinkName)
                                      ? " · audio→Quest (copy " + _session.Audio.ActiveSinkName + ")"
                                      : " · audio→Quest (copy speakers)")
                                  : " · audio→PC");
    }

    private void ShutdownAll()
    {
        _sourceRefreshTimer.Stop();
        if (_tray != null)
        {
            _tray.Visible = false;
            _tray.Dispose();
            _tray = null;
        }
        _reader.Dispose();
        _listen.Dispose();
        _session.Dispose();
        _viewer?.Close();
    }
}
