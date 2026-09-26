using System.IO;
using System.Drawing;
using System.Drawing.Imaging;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using SpatialLauncher.Desktop.Core;
using SpatialLauncher.Desktop.Core.Capture;
using SpatialLauncher.Desktop.Core.Listen;
using SpatialLauncher.Desktop.Core.Ocr;
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

    // OCR zone selector (headset parity): rubber-band zones on the preview,
    // stored normalized in capture space. Empty = default lower band.
    private const int MaxOcrZones = 6;
    private readonly List<OcrZone> _ocrZones = new();
    private bool _zoneEditMode;
    private bool _zoneDragging;
    private System.Windows.Point _dragStart;
    private System.Windows.Point _dragNow;
    private bool _previewIsSbs;
    private int _previewBmpW;
    private int _previewBmpH;
    // Move/resize state for existing zones.
    private ZoneDragMode _dragMode = ZoneDragMode.None;
    private int _dragZoneIndex = -1;
    private int _resizeCorner;
    private System.Windows.Point _anchor;
    private System.Windows.Point _grabOffset;
    private OcrZone? _editZone;

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
            UpdatePipelineLabel();
        });
        _session.QuestLink.SettingsGetJson = () => SessionSettingsJson.ToJson(_settings);
        _session.QuestLink.ReaderOnce = () => _reader.SpeakOnceAsync();
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
                _mode = _settings.AssistMode;
                ApplyUiFromSettings();
                _uiReady = true;
                // Mirror Mode_Click engine sync so a remote mode change behaves
                // exactly like tapping the chips (Quest B button cycles these).
                if (_mode == AssistMode.Listen)
                {
                    _reader.Stop();
                    _listen.ApplySettings(_settings);
                    if (!_listen.IsRunning) _listen.Start();
                }
                else
                {
                    if (_listen.IsRunning) _listen.Stop();
                    SyncReaderRunning();
                }
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
        ZoneCanvas.MouseLeftButtonDown += ZoneCanvas_Down;
        ZoneCanvas.MouseMove += ZoneCanvas_Move;
        ZoneCanvas.MouseLeftButtonUp += ZoneCanvas_Up;
        ZoneCanvas.MouseRightButtonDown += ZoneCanvas_Right;
        ZoneCanvas.SizeChanged += (_, _) => DrawZones();
        LoadOcrZones();
        ApplyUiFromSettings();
        UpdatePipelineLabel();
        _uiReady = true;
        RefreshProfileList();
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
            var bmp = (System.Windows.Media.Imaging.BitmapImage)PreviewImage.Source;
            _previewIsSbs = false;
            _previewBmpW = bmp.PixelWidth;
            _previewBmpH = bmp.PixelHeight;
            DrawZones();
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
                BitmapImage image;
                bool isSbs = true;
                if (PreviewMonoCheck?.IsChecked == true)
                {
                    int eyeW = Math.Max(1, bmp.Width / 2);
                    using var one = new Bitmap(eyeW, bmp.Height, System.Drawing.Imaging.PixelFormat.Format32bppArgb);
                    using (var g = Graphics.FromImage(one))
                        g.DrawImage(bmp, 0, 0, new Rectangle(0, 0, eyeW, bmp.Height), GraphicsUnit.Pixel);
                    image = ToBitmapImage(one);
                    isSbs = false;
                }
                else
                {
                    image = ToBitmapImage(bmp);
                }
                PreviewImage.Source = image;
                _previewIsSbs = isSbs;
                _previewBmpW = image.PixelWidth;
                _previewBmpH = image.PixelHeight;
                DrawZones();
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

    private void LoadOcrZones()
    {
        try
        {
            _ocrZones.Clear();
            _ocrZones.AddRange(new OcrZoneStore().Load());
        }
        catch { /* default band */ }
        UpdateZoneStatus();
        DrawZones();
    }

    private void ZoneEdit_Click(object sender, RoutedEventArgs e)
    {
        _zoneEditMode = !_zoneEditMode;
        ZoneEditButton.Content = _zoneEditMode ? "Edit zones: on" : "Edit zones: off";
        ZoneCanvas.Cursor = _zoneEditMode ? System.Windows.Input.Cursors.Cross : null;
        StatusText.Text = _zoneEditMode
            ? "Drag on the preview (left eye while streaming) to add an OCR zone."
            : "Ready";
    }

    private void ZoneClear_Click(object sender, RoutedEventArgs e)
    {
        _ocrZones.Clear();
        _reader.SetZones(_ocrZones);
        UpdateZoneStatus();
        DrawZones();
        StatusText.Text = "OCR zones cleared — default lower band.";
    }

    // Mono preview: one eye full-width for zone editing. Display only —
    // the stream keeps its SBS layout either way.
    private void PreviewMonoChanged(object sender, RoutedEventArgs e)
    {
        DrawZones();
    }

    private void UpdateZoneStatus()
    {
        ZoneStatusText.Text = _ocrZones.Count == 0
            ? "Zones: default lower band"
            : $"Zones: {_ocrZones.Count} custom";
    }

    private void ZoneCanvas_Down(object sender, MouseButtonEventArgs e)
    {
        if (!_zoneEditMode || _previewBmpW <= 0 || _previewBmpH <= 0) return;
        var p = e.GetPosition(ZoneCanvas);
        if (HitZone(p, out int index, out int corner))
        {
            _dragZoneIndex = index;
            _editZone = CopyZone(_ocrZones[index]);
            var r = ZoneCanvasRect(_ocrZones[index]);
            if (corner >= 0)
            {
                _dragMode = ZoneDragMode.Resize;
                _resizeCorner = corner;
                _anchor = OppositeCorner(r, corner);
            }
            else
            {
                _dragMode = ZoneDragMode.Move;
                _grabOffset = new System.Windows.Point(p.X - r.x, p.Y - r.y);
            }
            _zoneDragging = true;
            ZoneCanvas.CaptureMouse();
            e.Handled = true;
            return;
        }
        _dragMode = ZoneDragMode.New;
        _zoneDragging = true;
        _dragStart = _dragNow = p;
        ZoneCanvas.CaptureMouse();
        e.Handled = true;
    }

    private void ZoneCanvas_Move(object sender, System.Windows.Input.MouseEventArgs e)
    {
        var p = e.GetPosition(ZoneCanvas);
        if (!_zoneDragging)
        {
            if (_zoneEditMode)
                ZoneCanvas.Cursor = HoverCursor(p);
            return;
        }
        switch (_dragMode)
        {
            case ZoneDragMode.New:
                _dragNow = p;
                break;
            case ZoneDragMode.Move:
                {
                    var r = ZoneCanvasRect(_ocrZones[_dragZoneIndex]);
                    var moved = CanvasRectToZone(p.X - _grabOffset.X, p.Y - _grabOffset.Y, r.w, r.h);
                    if (moved != null)
                        _editZone = moved;
                    break;
                }
            case ZoneDragMode.Resize:
                {
                    double x0 = Math.Min(_anchor.X, p.X), y0 = Math.Min(_anchor.Y, p.Y);
                    double x1 = Math.Max(_anchor.X, p.X), y1 = Math.Max(_anchor.Y, p.Y);
                    if (x1 - x0 < 8 || y1 - y0 < 8)
                        break;
                    var resized = CanvasRectToZone(x0, y0, x1 - x0, y1 - y0);
                    if (resized != null)
                        _editZone = resized;
                    break;
                }
        }
        DrawZones();
        e.Handled = true;
    }

    private void ZoneCanvas_Up(object sender, MouseButtonEventArgs e)
    {
        if (!_zoneDragging) return;
        _zoneDragging = false;
        try { ZoneCanvas.ReleaseMouseCapture(); } catch { /* ignore */ }
        if (_dragMode == ZoneDragMode.New)
        {
            var end = e.GetPosition(ZoneCanvas);
            double rx = Math.Min(_dragStart.X, end.X), ry = Math.Min(_dragStart.Y, end.Y);
            var zone = CanvasRectToZone(rx, ry, Math.Abs(end.X - _dragStart.X), Math.Abs(end.Y - _dragStart.Y));
            if (zone != null)
            {
                if (_ocrZones.Count >= MaxOcrZones)
                    _ocrZones.RemoveAt(0);
                _ocrZones.Add(zone);
                _reader.SetZones(_ocrZones);
                UpdateZoneStatus();
                StatusText.Text = $"OCR zone added ({_ocrZones.Count}/{MaxOcrZones}).";
            }
        }
        else if (_dragZoneIndex >= 0 && _editZone != null)
        {
            _ocrZones[_dragZoneIndex] = _editZone;
            _reader.SetZones(_ocrZones);
            UpdateZoneStatus();
            StatusText.Text = _dragMode == ZoneDragMode.Move ? "OCR zone moved." : "OCR zone resized.";
        }
        _dragMode = ZoneDragMode.None;
        _dragZoneIndex = -1;
        _editZone = null;
        DrawZones();
        e.Handled = true;
    }

    private void ZoneCanvas_Right(object sender, MouseButtonEventArgs e)
    {
        if (!_zoneEditMode) return;
        if (HitZone(e.GetPosition(ZoneCanvas), out int index, out _))
        {
            _ocrZones.RemoveAt(index);
            _reader.SetZones(_ocrZones);
            UpdateZoneStatus();
            DrawZones();
            StatusText.Text = "OCR zone deleted.";
            e.Handled = true;
        }
    }

    private enum ZoneDragMode { None, New, Move, Resize }

    private static OcrZone CopyZone(OcrZone z) => new() { X = z.X, Y = z.Y, W = z.W, H = z.H };

    // Shared preview geometry: Uniform-stretch bitmap placement in the canvas,
    // plus the eye/capture widths for SBS mapping (left eye carries capture).
    private bool GetPreviewTransform(out double scale, out double ox, out double oy,
        out double eyeW, out double capW)
    {
        scale = ox = oy = eyeW = capW = 0;
        double canvasW = ZoneCanvas.ActualWidth, canvasH = ZoneCanvas.ActualHeight;
        if (canvasW <= 0 || canvasH <= 0 || _previewBmpW <= 0 || _previewBmpH <= 0)
            return false;
        scale = Math.Min(canvasW / _previewBmpW, canvasH / _previewBmpH);
        ox = (canvasW - _previewBmpW * scale) / 2.0;
        oy = (canvasH - _previewBmpH * scale) / 2.0;
        eyeW = _previewIsSbs ? _previewBmpW / 2.0 : _previewBmpW;
        capW = _previewIsSbs ? (MirrorSession.EffectiveFullSbs(_settings) ? eyeW : eyeW * 2.0) : _previewBmpW;
        return true;
    }

    // Normalized capture-space zone -> canvas rect (left eye).
    private (double x, double y, double w, double h) ZoneCanvasRect(OcrZone z)
    {
        GetPreviewTransform(out var scale, out var ox, out var oy, out var eyeW, out _);
        return (ox + z.X * eyeW * scale, oy + z.Y * _previewBmpH * scale,
            z.W * eyeW * scale, z.H * _previewBmpH * scale);
    }

    // Canvas rect -> normalized capture-space zone (null when degenerate).
    private OcrZone? CanvasRectToZone(double x, double y, double w, double h)
    {
        if (!GetPreviewTransform(out var scale, out var ox, out var oy, out var eyeW, out var capW))
            return null;
        double bx0 = Math.Clamp((x - ox) / scale, 0, eyeW);
        double by0 = Math.Clamp((y - oy) / scale, 0, _previewBmpH);
        double bw = Math.Min(w / scale, eyeW - bx0);
        double bh = Math.Min(h / scale, _previewBmpH - by0);
        if (bw < 8 || bh < 8)
            return null;
        double capH = _previewBmpH;
        var zone = new OcrZone { X = bx0 / capW, Y = by0 / capH, W = bw / capW, H = bh / capH };
        if (zone.W <= 0.005 || zone.H <= 0.005)
            return null;
        return zone;
    }

    private const double ZoneGrip = 10.0;

    private static System.Windows.Point OppositeCorner((double x, double y, double w, double h) r, int corner) =>
        corner switch
        {
            0 => new System.Windows.Point(r.x + r.w, r.y + r.h), // TL -> BR
            1 => new System.Windows.Point(r.x, r.y + r.h),       // TR -> BL
            2 => new System.Windows.Point(r.x, r.y),             // BR -> TL
            _ => new System.Windows.Point(r.x + r.w, r.y),       // BL -> TR
        };

    // Topmost zone first; corners beat bodies. Corner index 0 TL,1 TR,2 BR,3 BL.
    private bool HitZone(System.Windows.Point p, out int index, out int corner)
    {
        index = -1;
        corner = -1;
        if (!GetPreviewTransform(out _, out _, out _, out _, out _))
            return false;
        var rects = new System.Collections.Generic.List<(double x, double y, double w, double h)>();
        for (int i = 0; i < _ocrZones.Count; i++)
            rects.Add(ZoneCanvasRect(_ocrZones[i]));
        for (int i = rects.Count - 1; i >= 0; i--)
        {
            var (x, y, w, h) = rects[i];
            System.Windows.Point[] grips =
            [
                new(x, y), new(x + w, y), new(x + w, y + h), new(x, y + h)
            ];
            for (int c = 0; c < 4; c++)
            {
                if (Math.Abs(p.X - grips[c].X) <= ZoneGrip && Math.Abs(p.Y - grips[c].Y) <= ZoneGrip)
                {
                    index = i;
                    corner = c;
                    return true;
                }
            }
        }
        for (int i = rects.Count - 1; i >= 0; i--)
        {
            var (x, y, w, h) = rects[i];
            if (p.X >= x && p.X <= x + w && p.Y >= y && p.Y <= y + h)
            {
                index = i;
                return true;
            }
        }
        return false;
    }

    private System.Windows.Input.Cursor HoverCursor(System.Windows.Point p)
    {
        if (HitZone(p, out _, out int corner))
        {
            if (corner >= 0)
                return (corner == 0 || corner == 2)
                    ? System.Windows.Input.Cursors.SizeNWSE
                    : System.Windows.Input.Cursors.SizeNESW;
            return System.Windows.Input.Cursors.SizeAll;
        }
        return System.Windows.Input.Cursors.Cross;
    }

    private void DrawZones()
    {
        if (ZoneCanvas == null) return;
        ZoneCanvas.Children.Clear();
        double canvasW = ZoneCanvas.ActualWidth, canvasH = ZoneCanvas.ActualHeight;
        if (canvasW <= 0 || canvasH <= 0 || _previewBmpW <= 0 || _previewBmpH <= 0)
            return;
        double scale = Math.Min(canvasW / _previewBmpW, canvasH / _previewBmpH);
        double ox = (canvasW - _previewBmpW * scale) / 2.0;
        double oy = (canvasH - _previewBmpH * scale) / 2.0;
        double eyeW = _previewIsSbs ? _previewBmpW / 2.0 : _previewBmpW;
        double capW = _previewIsSbs ? (MirrorSession.EffectiveFullSbs(_settings) ? eyeW : eyeW * 2.0) : _previewBmpW;
        for (int i = 0; i < _ocrZones.Count; i++)
        {
            var z = (_dragZoneIndex == i && _editZone != null) ? _editZone : _ocrZones[i];
            // Capture-normalized -> bitmap left eye -> canvas.
            double bx0 = z.X * capW * (eyeW / capW);
            double bw = z.W * capW * (eyeW / capW);
            double by0 = z.Y * _previewBmpH;
            double bh = z.H * _previewBmpH;
            double rx = ox + bx0 * scale, ry = oy + by0 * scale;
            double rw = bw * scale, rh = bh * scale;
            AddZoneRect(rx, ry, rw, rh, false);
            if (_zoneEditMode)
                AddHandles(rx, ry, rw, rh);
        }
        if (_zoneDragging && _dragMode == ZoneDragMode.New)
        {
            double rx = Math.Min(_dragStart.X, _dragNow.X);
            double ry = Math.Min(_dragStart.Y, _dragNow.Y);
            double rw = Math.Abs(_dragNow.X - _dragStart.X);
            double rh = Math.Abs(_dragNow.Y - _dragStart.Y);
            AddZoneRect(rx, ry, rw, rh, true);
        }
    }

    private void AddHandles(double x, double y, double w, double h)
    {
        const double s = 6.0;
        System.Windows.Point[] grips =
        [
            new(x, y), new(x + w, y), new(x + w, y + h), new(x, y + h)
        ];
        foreach (var g in grips)
        {
            var r = new System.Windows.Shapes.Rectangle
            {
                Width = s,
                Height = s,
                Stroke = new SolidColorBrush(Colors.White),
                StrokeThickness = 1,
                Fill = new SolidColorBrush(Colors.Lime)
            };
            Canvas.SetLeft(r, g.X - s / 2);
            Canvas.SetTop(r, g.Y - s / 2);
            ZoneCanvas.Children.Add(r);
        }
    }

    private void AddZoneRect(double x, double y, double w, double h, bool rubber)
    {
        if (w < 2 || h < 2) return;
        var r = new System.Windows.Shapes.Rectangle
        {
            Width = w,
            Height = h,
            Stroke = new SolidColorBrush(rubber ? Colors.Yellow : Colors.Lime),
            StrokeThickness = rubber ? 1 : 2,
            Fill = System.Windows.Media.Brushes.Transparent
        };
        if (rubber)
            r.StrokeDashArray = new DoubleCollection { 4, 2 };
        Canvas.SetLeft(r, x);
        Canvas.SetTop(r, y);
        ZoneCanvas.Children.Add(r);
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
            UpdatePipelineLabel();
            RefreshAudioHint();
        }
        catch (Exception ex)
        {
            StatusText.Text = "Save failed: " + ex.Message;
        }
    }

    private bool _refreshingProfiles;

    private void RefreshProfileList()
    {
        _refreshingProfiles = true;
        try
        {
            string keep = ProfileBox.Text;
            var names = _settingsStore.ListProfiles();
            ProfileBox.ItemsSource = names;
            if (names.Contains(keep))
                ProfileBox.SelectedItem = keep;
        }
        finally
        {
            _refreshingProfiles = false;
        }
    }

    private void ProfileSave_Click(object sender, RoutedEventArgs e)
    {
        string name = (ProfileBox.Text ?? "").Trim();
        if (string.IsNullOrEmpty(name))
        {
            StatusText.Text = "Type a profile name first, then Save profile.";
            return;
        }
        try
        {
            SyncSettingsFromUi();
            _settingsStore.SaveProfile(name, _settings);
            RefreshProfileList();
            ProfileBox.SelectedItem = name;
            StatusText.Text = $"Profile '{name}' saved.";
        }
        catch (Exception ex)
        {
            StatusText.Text = "Profile save failed: " + ex.Message;
        }
    }

    private void ProfileDelete_Click(object sender, RoutedEventArgs e)
    {
        string name = (ProfileBox.SelectedItem as string ?? ProfileBox.Text ?? "").Trim();
        if (string.IsNullOrEmpty(name))
        {
            StatusText.Text = "Pick a profile to delete.";
            return;
        }
        try
        {
            if (_settingsStore.DeleteProfile(name))
            {
                RefreshProfileList();
                ProfileBox.Text = "";
                StatusText.Text = $"Profile '{name}' deleted.";
            }
            else
            {
                StatusText.Text = $"Profile '{name}' not found.";
            }
        }
        catch (Exception ex)
        {
            StatusText.Text = "Profile delete failed: " + ex.Message;
        }
    }

    private void ProfileBox_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_refreshingProfiles || !_uiReady) return;
        if (ProfileBox.SelectedItem is string name && !string.IsNullOrWhiteSpace(name))
            ApplyProfile(name);
    }

    private void ApplyProfile(string name)
    {
        UserSettings? loaded;
        try
        {
            loaded = _settingsStore.LoadProfile(name);
        }
        catch (Exception ex)
        {
            StatusText.Text = "Profile load failed: " + ex.Message;
            return;
        }
        if (loaded == null)
        {
            StatusText.Text = $"Profile '{name}' not found.";
            return;
        }
        _settings = loaded;
        _mode = loaded.AssistMode;
        _useOpus = loaded.UseOpusTranslate;
        _session.ApplySettings(_settings);
        _reader.ApplySettings(_settings);
        _listen.ApplySettings(_settings);
        _uiReady = false;
        ApplyUiFromSettings();
        _uiReady = true;
        if (_mode == AssistMode.Listen)
        {
            _reader.Stop();
            _listen.ApplySettings(_settings);
            if (!_listen.IsRunning) _listen.Start();
        }
        else
        {
            if (_listen.IsRunning) _listen.Stop();
            SyncReaderRunning();
        }
        try
        {
            _settingsStore.Save(_settings);
        }
        catch { /* profile already persisted; live file best-effort */ }
        StatusText.Text = $"Profile '{name}' applied.";
        UpdatePipelineLabel();
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
            int kbps = 4000 + (Math.Clamp(_settings.JpegQuality, 50, 98) - 50) * 300;
            if (_settings.StreamCodec == StreamCodec.H264)
                kbps = Math.Max(6000, kbps + 2000);
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
            AudioSinkHint.Text = "Headset on · Opus-mirroring '" + active
                + "' to Quest. PC speakers stay on.";
        }
        else if (_settings.AudioMode == AudioOutputMode.Headset)
        {
            AudioSinkHint.Text =
                "Headset will Opus-mirror Windows speakers to Quest (both play). Start Session, connect Quest.";
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
                              + $" · {_session.DepthModelStatus}"
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
                                        ? " · audio→Quest (Opus " + _session.Audio.ActiveSinkName + ")"
                                        : " · audio→Quest (Opus)")
                                    : " · audio→PC");
        RefreshAudioHint();
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