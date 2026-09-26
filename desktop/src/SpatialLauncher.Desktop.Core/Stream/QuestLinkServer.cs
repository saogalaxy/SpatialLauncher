using System.Drawing;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;
using System.Threading;
using SpatialLauncher.Desktop.Core;

namespace SpatialLauncher.Desktop.Core.Stream;

/// <summary>
/// LAN stream on TCP :8765 bound to 0.0.0.0 (no HttpListener URL-ACL).
/// JPEG: http://&lt;pc-ip&gt;:8765/sbs.mjpg (multipart MJPEG)
/// MPEG: http://&lt;pc-ip&gt;:8765/sbs.h264 (length-prefixed Annex-B AUs)
/// AV1:  http://&lt;pc-ip&gt;:8765/sbs.av1  (length-prefixed OBUs)
/// </summary>
public sealed class QuestLinkServer : IDisposable
{
    public const int DefaultPort = 8765;

    private TcpListener? _listener;
    private TcpListener? _extraListener;
    private Thread? _thread;
    private Thread? _extraThread;

    /// <summary>Extra port the adb USB forward lands on, when one is open.</summary>
    public int? UsbPort { get; private set; }
    private Thread? _encodeThread;
    private volatile bool _running;
    private readonly object _frameLock = new();
    private readonly object _encodeLock = new();
    private Bitmap? _pendingEncode;
    private byte[]? _payload;
    private int _lastPayloadBytes;
    private int _payloadGen;
    private int _port = DefaultPort;
    private StreamCodec _codec = StreamCodec.Mjpeg;
    private readonly H264FrameEncoder _h264 = new();
    private Av1FrameEncoder _av1 = new();
    private int _av1NullStreak;
    private int _h264NullStreak;
    private long _lastKeyTick;
    private string? _encodeError;
    private int _codecEpoch;

    public int JpegQuality { get; set; } = 90;
    public int SharpenPercent { get; set; } = 25;
    /// <summary>True while MirrorSession is capturing/publishing frames.</summary>
    public bool SessionActive { get; set; }
    public int ViewerCount => _viewerCount;
    private int _viewerCount;
    private IPAddress? _lastViewerAddress;
    /// <summary>LAN address of the most recent Quest video client (for audio unicast).</summary>
    public IPAddress? LastViewerAddress => _lastViewerAddress;
    public event Action<IPAddress?>? ViewerAddressChanged;
    public StreamCodec Codec
    {
        get => _codec;
        set
        {
            if (_codec == value) return;
            _codec = value;
            _codecEpoch++;
            if (_running)
                AdvertiseUrl = BuildAdvertiseUrl(_port, _codec);
            lock (_frameLock)
            {
                _payload = null;
                _lastPayloadBytes = 0;
                _payloadGen++;
                Monitor.PulseAll(_frameLock);
            }
            if (_codec == StreamCodec.H264)
            {
                _h264.RequestKeyFrame();
                _h264NullStreak = 0;
            }
            else if (_codec == StreamCodec.Av1)
            {
                _av1.RequestKeyFrame();
                _av1NullStreak = 0;
            }
            StatusChanged?.Invoke($"Quest Link · {CodecDisplayName(_codec)} · " + AdvertiseUrl);
        }
    }

    /// <summary>GET /settings JSON provider (Quest remote control).</summary>
    public Func<string>? SettingsGetJson { get; set; }
    /// <summary>POST /settings body → apply; return updated JSON or null on failure.</summary>
    public Func<string, string?>? SettingsApplyJson { get; set; }
    /// <summary>Controller one-shot (Quest A): OCR current frame + speak once.</summary>
    public Func<Task<string>>? ReaderOnce { get; set; }
    /// <summary>Optional audio diagnostics blob for GET /status.</summary>
    public Func<string>? AudioDebugJson { get; set; }

    public event Action<string>? StatusChanged;

    public bool IsRunning => _running;
    public int Port => _port;
    public string? AdvertiseUrl { get; private set; }

    /// <summary>Takes ownership of the bitmap; encode runs off the present thread.</summary>
    public void PublishFrame(Bitmap sbs)
    {
        lock (_encodeLock)
        {
            _pendingEncode?.Dispose();
            _pendingEncode = sbs;
            Monitor.Pulse(_encodeLock);
        }
    }

    public void Start(int port = DefaultPort, int? extraPort = null)
    {
        Stop();
        _port = port;
        _listener = new TcpListener(IPAddress.Any, port);
        _listener.Start();
        // A second port lets the adb USB forward land somewhere other than the
        // LAN port; both feed the same handler so USB and LAN can run together.
        TcpListener? extra = null;
        if (extraPort is > 0 && extraPort != port)
        {
            try
            {
                extra = new TcpListener(IPAddress.Any, extraPort.Value);
                extra.Start();
                UsbPort = extraPort.Value;
            }
            catch (SocketException ex)
            {
                UsbPort = null;
                StatusChanged?.Invoke($"USB port {extraPort} unavailable: {ex.Message}");
            }
        }
        else
        {
            UsbPort = null;
        }
        _extraListener = extra;
        AdvertiseUrl = BuildAdvertiseUrl(port, _codec);
        _running = true;
        _encodeError = null;
        _lastKeyTick = 0;
        _encodeThread = new Thread(EncodeLoop) { IsBackground = true, Name = "SldEncode" };
        _encodeThread.Start();
        _thread = new Thread(() => AcceptLoop(_listener)) { IsBackground = true, Name = "SldQuestLink" };
        _thread.Start();
        if (_extraListener != null)
        {
            _extraThread = new Thread(() => AcceptLoop(_extraListener)) { IsBackground = true, Name = "SldQuestLinkUsb" };
            _extraThread.Start();
        }
        StatusChanged?.Invoke($"Quest Link streaming · {CodecDisplayName(_codec)} · " + AdvertiseUrl);
    }

    public void Stop()
    {
        _running = false;
        lock (_encodeLock)
            Monitor.PulseAll(_encodeLock);
        lock (_frameLock)
            Monitor.PulseAll(_frameLock);
        try { _listener?.Stop(); } catch { /* ignore */ }
        _listener = null;
        try { _extraListener?.Stop(); } catch { /* ignore */ }
        _extraListener = null;
        try { _thread?.Join(400); } catch { /* ignore */ }
        try { _extraThread?.Join(400); } catch { /* ignore */ }
        _thread = null;
        _extraThread = null;
        UsbPort = null;
        _encodeThread = null;
        lock (_encodeLock)
        {
            _pendingEncode?.Dispose();
            _pendingEncode = null;
        }
        AdvertiseUrl = null;
    }

    private void EncodeLoop()
    {
        while (_running)
        {
            Bitmap? src = null;
            lock (_encodeLock)
            {
                while (_running && _pendingEncode == null)
                    Monitor.Wait(_encodeLock, 50);
                src = _pendingEncode;
                _pendingEncode = null;
            }
            if (src == null)
                continue;
            try
            {
                // Sharpen costs CPU before encode; skip on compressed codecs.
                if (SharpenPercent > 0 && _codec == StreamCodec.Mjpeg)
                    ImageSharpen.Apply(src, SharpenPercent);

                byte[]? bytes;
                if (_codec == StreamCodec.H264)
                {
                    // MPEG needs more bits than AV1 at the same perceived sharpness.
                    int kbps = Math.Max(6000, JpegQualityToBitrateKbps(JpegQuality) + 2000);
                    _h264.Ensure(src.Width, src.Height, kbps);
                    long now = Environment.TickCount64;
                    // Key ~1 Hz — every-frame "key" + AllSamplesIndependent was crushing quality.
                    bool wantKey = _lastKeyTick == 0 || now - _lastKeyTick >= 1000;
                    bytes = _h264.Encode(src, forceKeyFrame: wantKey);
                    if (wantKey)
                        _lastKeyTick = now;
                    if (bytes == null || bytes.Length == 0)
                    {
                        _h264NullStreak++;
                        if (_h264NullStreak == 15)
                        {
                            _encodeError = "MPEG (H.264) encoder produced no frames — try AV1 or JPEG";
                            StatusChanged?.Invoke("Encode: " + _encodeError);
                        }
                    }
                    else
                    {
                        _h264NullStreak = 0;
                    }
                }
                else if (_codec == StreamCodec.Av1)
                {
                    int kbps = Math.Max(4000, JpegQualityToBitrateKbps(JpegQuality));
                    try
                    {
                        _av1.Ensure(src.Width, src.Height, kbps);
                        // Same 1 Hz key cadence as MPEG: every-AU keys starve
                        // inter frames into mushy pixelation on detail-heavy
                        // content (Movies preset shows it first).
                        long nowAv1 = Environment.TickCount64;
                        bool wantKey = _lastKeyTick == 0 || nowAv1 - _lastKeyTick >= 1000;
                        bytes = _av1.Encode(src, forceKeyFrame: wantKey);
                        if (wantKey)
                            _lastKeyTick = nowAv1;
                    }
                    catch (Exception ex)
                    {
                        _encodeError = "AV1: " + ex.Message;
                        StatusChanged?.Invoke("Encode: " + _encodeError);
                        continue;
                    }
                    if (bytes == null || bytes.Length == 0)
                    {
                        _av1NullStreak++;
                        if (_av1NullStreak == 15)
                        {
                            _encodeError = "AV1 encoder produced no frames — use MPEG (H.264)";
                            StatusChanged?.Invoke("Encode: " + _encodeError);
                        }
                        continue;
                    }
                    _av1NullStreak = 0;
                }
                else
                {
                    bytes = JpegFrames.Encode(src, JpegQuality);
                }

                if (bytes == null || bytes.Length == 0)
                    continue;

                lock (_frameLock)
                {
                    _payload = bytes;
                    _lastPayloadBytes = bytes.Length;
                    _payloadGen++;
                    Monitor.PulseAll(_frameLock);
                }
                _encodeError = null;
            }
            catch (Exception ex)
            {
                _encodeError = ex.Message;
                StatusChanged?.Invoke("Encode: " + ex.Message);
            }
            finally
            {
                src.Dispose();
            }
        }
    }

    private static int JpegQualityToBitrateKbps(int jpegQuality)
    {
        int q = Math.Clamp(jpegQuality, 50, 98);
        // ~4–18 Mbps — room for Full SBS / 72 Hz without looking softer than JPEG.
        return 4000 + (q - 50) * 300;
    }

    private static string CodecDisplayName(StreamCodec codec) => codec switch
    {
        StreamCodec.H264 => "MPEG (H.264)",
        StreamCodec.Av1 => "AV1",
        _ => "JPEG"
    };

    private void AcceptLoop(TcpListener? listener)
    {
        while (_running && listener != null)
        {
            try
            {
                var client = listener.AcceptTcpClient();
                client.NoDelay = true;
                // Wi‑Fi JPEG/AU writes need headroom; 500ms left half-dead viewers
                // holding slots so the headset could not reclaim a stream.
                client.SendTimeout = 8000;
                client.ReceiveTimeout = 8000;
                _ = Task.Run(() => HandleClient(client));
            }
            catch
            {
                if (!_running) break;
            }
        }
    }

    private async Task HandleClient(TcpClient client)
    {
        using (client)
        using (var stream = client.GetStream())
        {
            try
            {
                client.ReceiveTimeout = 8000;
                ReadHttpRequest(stream, out string method, out string path, out string body);

                if (path.StartsWith("/settings", StringComparison.OrdinalIgnoreCase))
                {
                    HandleSettings(stream, method, body);
                    return;
                }

                if (path.Equals("/reader/once", StringComparison.OrdinalIgnoreCase))
                {
                    string text = "";
                    try
                    {
                        if (ReaderOnce != null)
                            text = await ReaderOnce();
                    }
                    catch { /* empty */ }
                    byte[] onceBody = Encoding.UTF8.GetBytes(
                        $"{{\"ok\":true,\"text\":{JsonString(text)}}}");
                    WriteHttp(stream, "200 OK", "application/json", onceBody);
                    return;
                }

                if (path is "/" or "/status")
                {
                    string codec = SessionSettingsJson.CodecLabel(_codec);
                    string streamPath = "/" + SessionSettingsJson.StreamPath(_codec);
                    string err = _encodeError == null ? "" : $",\"encodeError\":{JsonString(_encodeError)}";
                    string session = SessionActive ? "true" : "false";
                    string av1c = "";
                    if (_codec == StreamCodec.Av1)
                    {
                        byte[]? cfg = _av1.Av1C;
                        if (cfg is { Length: > 0 })
                            av1c = ",\"av1c\":\"" + Convert.ToBase64String(cfg) + "\"";
                    }
                    string audioDiag = "";
                    try
                    {
                        // MirrorSession wires Audio; optional hook avoids Core→Session cycle.
                        if (AudioDebugJson != null)
                            audioDiag = ",\"audio\":" + AudioDebugJson();
                    }
                    catch { /* ignore */ }
                    byte[] statusBody = Encoding.UTF8.GetBytes(
                        $"{{\"ok\":true,\"stream\":\"{streamPath}\",\"settings\":\"/settings\",\"port\":{_port},\"codec\":\"{codec}\",\"sessionActive\":{session},\"viewers\":{_viewerCount},\"lastPayloadBytes\":{_lastPayloadBytes},\"audioPort\":{AudioLinkStreamer.DefaultPort},\"audioCodec\":\"opus\",\"viewer\":\"{(_lastViewerAddress?.ToString() ?? "")}\"{audioDiag}{err}{av1c}}}");
                    WriteHttp(stream, "200 OK", "application/json", statusBody);
                    return;
                }

                bool wantAv1 = path is "/sbs.av1" or "/stream.av1";
                bool wantH264 = path is "/sbs.h264" or "/stream.h264";
                bool wantJpeg = path is "/sbs.mjpg" or "/stream";
                if (!wantH264 && !wantJpeg && !wantAv1)
                {
                    WriteHttp(stream, "404 Not Found", "text/plain", Encoding.UTF8.GetBytes("not found"));
                    return;
                }

                StreamCodec requested = wantAv1 ? StreamCodec.Av1
                    : wantH264 ? StreamCodec.H264
                    : StreamCodec.Mjpeg;
                if (requested != _codec)
                {
                    // Tell the headset to switch URL instead of hanging on an empty body.
                    string want = SessionSettingsJson.CodecLabel(_codec);
                    string streamPath = "/" + SessionSettingsJson.StreamPath(_codec);
                    byte[] redirect = Encoding.UTF8.GetBytes(
                        $"{{\"ok\":false,\"error\":\"codec_mismatch\",\"codec\":\"{want}\",\"stream\":\"{streamPath}\"}}");
                    WriteHttp(stream, "409 Conflict", "application/json", redirect);
                    return;
                }

                if (wantAv1)
                    WriteLengthPrefixedLoop(client, stream, StreamCodec.Av1, "av1");
                else if (wantH264)
                    WriteLengthPrefixedLoop(client, stream, StreamCodec.H264, "h264");
                else
                    WriteMjpegLoop(client, stream);
            }
            catch
            {
                // client gone
            }
        }
    }

    private void WriteMjpegLoop(TcpClient client, NetworkStream stream)
    {
        if (!TryAcquireViewerSlot(client, stream))
            return;
        try
        {
            var header = Encoding.ASCII.GetBytes(
                "HTTP/1.1 200 OK\r\n" +
                "Content-Type: multipart/x-mixed-replace; boundary=frame\r\n" +
                "Cache-Control: no-cache, no-store\r\n" +
                "Pragma: no-cache\r\n" +
                "Connection: close\r\n\r\n");
            stream.Write(header);

            var ascii = Encoding.ASCII;
            int lastGen = -1;
            int epoch = _codecEpoch;
            while (_running && client.Connected)
            {
                if (_codec != StreamCodec.Mjpeg || _codecEpoch != epoch)
                    break;

                byte[]? payload = WaitLatestPayload(ref lastGen);
                if (payload == null)
                    continue;

                try
                {
                    var preamble = ascii.GetBytes(
                        "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: "
                        + payload.Length + "\r\n\r\n");
                    stream.Write(preamble);
                    stream.Write(payload);
                    stream.Write(ascii.GetBytes("\r\n"));
                    stream.Flush();
                }
                catch (IOException)
                {
                    // Client gone — exit so the viewer slot frees for reconnect.
                    break;
                }
            }
        }
        finally
        {
            NoteViewerLeft();
        }
    }

    private void WriteLengthPrefixedLoop(TcpClient client, NetworkStream stream, StreamCodec expected, string xCodec)
    {
        if (!TryAcquireViewerSlot(client, stream))
            return;
        try
        {
            var header = Encoding.ASCII.GetBytes(
                "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/octet-stream\r\n" +
                "Cache-Control: no-cache, no-store\r\n" +
                "Pragma: no-cache\r\n" +
                "X-Codec: " + xCodec + "\r\n" +
                "Connection: close\r\n\r\n");
            stream.Write(header);
            if (expected == StreamCodec.H264)
                _h264.RequestKeyFrame();
            else if (expected == StreamCodec.Av1)
                _av1.RequestKeyFrame();

            int lastGen = -1;
            int epoch = _codecEpoch;
            while (_running && client.Connected)
            {
                if (_codec != expected || _codecEpoch != epoch)
                    break;

                byte[]? payload = WaitLatestPayload(ref lastGen);
                if (payload == null)
                    continue;

                try
                {
                    var len = new byte[4];
                    len[0] = (byte)((payload.Length >> 24) & 0xFF);
                    len[1] = (byte)((payload.Length >> 16) & 0xFF);
                    len[2] = (byte)((payload.Length >> 8) & 0xFF);
                    len[3] = (byte)(payload.Length & 0xFF);
                    stream.Write(len);
                    stream.Write(payload);
                    stream.Flush();
                }
                catch (IOException)
                {
                    // Client gone — exit so the viewer slot frees for reconnect.
                    break;
                }
            }
        }
        finally
        {
            NoteViewerLeft();
        }
    }

    /// <summary>
    /// At most three live stream pumps. Reconnect storms used to leave dozens of
    /// half-dead clients; writers now exit on IOException so slots free quickly.
    /// </summary>
    private bool TryAcquireViewerSlot(TcpClient client, NetworkStream stream)
    {
        int n = Interlocked.Increment(ref _viewerCount);
        if (n > 3)
        {
            Interlocked.Decrement(ref _viewerCount);
            try
            {
                WriteHttp(stream, "503 Service Unavailable", "application/json",
                    Encoding.UTF8.GetBytes("{\"ok\":false,\"error\":\"busy\",\"viewers\":" + n + "}"));
            }
            catch { /* client gone */ }
            return false;
        }
        NoteViewerAddress(client);
        StatusChanged?.Invoke(
            SessionActive
                ? $"Quest connected ({n}) · streaming"
                : $"Quest connected ({n}) · waiting for Start Session frames");
        return true;
    }

    private void NoteViewerAddress(TcpClient client)
    {
        try
        {
            if (client.Client.RemoteEndPoint is IPEndPoint ep)
            {
                var addr = ep.Address;
                try
                {
                    if (addr.IsIPv4MappedToIPv6)
                        addr = addr.MapToIPv4();
                }
                catch
                {
                    // keep original
                }
                _lastViewerAddress = addr;
                ViewerAddressChanged?.Invoke(_lastViewerAddress);
            }
        }
        catch
        {
            // ignore
        }
    }

    private void NoteViewerLeft()
    {
        int n = Math.Max(0, Interlocked.Decrement(ref _viewerCount));
        if (n == 0)
        {
            _lastViewerAddress = null;
            ViewerAddressChanged?.Invoke(null);
        }
        StatusChanged?.Invoke(
            n > 0
                ? $"Quest viewers: {n}"
                : "Quest disconnected · Link still listening");
    }

    private byte[]? WaitLatestPayload(ref int lastGen)
    {
        byte[]? payload;
        int gen;
        lock (_frameLock)
        {
            while (_running && (_payload == null || _payloadGen == lastGen))
            {
                // Short wait so codec-epoch bumps wake writers quickly.
                if (!Monitor.Wait(_frameLock, 40))
                    break;
            }
            payload = _payload;
            gen = _payloadGen;
        }
        if (payload == null || gen == lastGen)
            return null;
        lastGen = gen;
        return payload;
    }

    private void HandleSettings(NetworkStream stream, string method, string body)
    {
        if (method.Equals("GET", StringComparison.OrdinalIgnoreCase))
        {
            string json = SettingsGetJson?.Invoke() ?? "{\"error\":\"unavailable\"}";
            WriteHttp(stream, "200 OK", "application/json", Encoding.UTF8.GetBytes(json));
            return;
        }

        if (method.Equals("POST", StringComparison.OrdinalIgnoreCase)
            || method.Equals("PUT", StringComparison.OrdinalIgnoreCase))
        {
            string? updated = SettingsApplyJson?.Invoke(body);
            if (updated == null)
            {
                WriteHttp(stream, "400 Bad Request", "application/json",
                    Encoding.UTF8.GetBytes("{\"ok\":false}"));
                return;
            }
            WriteHttp(stream, "200 OK", "application/json", Encoding.UTF8.GetBytes(updated));
            return;
        }

        WriteHttp(stream, "405 Method Not Allowed", "text/plain", Encoding.UTF8.GetBytes("use GET or POST"));
    }

    private static string ReadHttpRequest(NetworkStream stream, out string method, out string path, out string body)
    {
        method = "GET";
        path = "/";
        body = "";
        var ms = new MemoryStream();
        var buf = new byte[4096];
        int total = 0;
        int headerEnd = -1;
        while (total < 256 * 1024)
        {
            int n = stream.Read(buf, 0, buf.Length);
            if (n <= 0) break;
            ms.Write(buf, 0, n);
            total += n;
            var soFar = ms.ToArray();
            headerEnd = IndexOfHeaderEnd(soFar);
            if (headerEnd >= 0)
            {
                string headText = Encoding.ASCII.GetString(soFar, 0, headerEnd);
                int contentLen = 0;
                foreach (var line in headText.Split('\n'))
                {
                    if (line.StartsWith("Content-Length:", StringComparison.OrdinalIgnoreCase))
                        int.TryParse(line.AsSpan(15).Trim(), out contentLen);
                }
                int haveBody = soFar.Length - (headerEnd + 4);
                while (haveBody < contentLen)
                {
                    n = stream.Read(buf, 0, Math.Min(buf.Length, contentLen - haveBody));
                    if (n <= 0) break;
                    ms.Write(buf, 0, n);
                    haveBody += n;
                }
                break;
            }
            if (!stream.DataAvailable && total > 0 && headerEnd < 0 && total > 16)
                break;
        }

        byte[] all = ms.ToArray();
        string text = Encoding.UTF8.GetString(all);
        var lines = text.Split('\n');
        if (lines.Length > 0)
        {
            var parts = lines[0].Trim().Split(' ');
            if (parts.Length >= 2)
            {
                method = parts[0];
                path = parts[1].Split('?')[0];
            }
        }
        int he = IndexOfHeaderEnd(all);
        if (he >= 0 && he + 4 < all.Length)
            body = Encoding.UTF8.GetString(all, he + 4, all.Length - (he + 4));
        return text;
    }

    private static int IndexOfHeaderEnd(byte[] data)
    {
        for (int i = 0; i + 3 < data.Length; i++)
        {
            if (data[i] == '\r' && data[i + 1] == '\n' && data[i + 2] == '\r' && data[i + 3] == '\n')
                return i;
        }
        return -1;
    }

    private static void WriteHttp(NetworkStream stream, string status, string type, byte[] body)
    {
        var head = Encoding.ASCII.GetBytes(
            $"HTTP/1.1 {status}\r\nContent-Type: {type}\r\nContent-Length: {body.Length}\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n");
        stream.Write(head);
        stream.Write(body);
        stream.Flush();
    }

    private static string BuildAdvertiseUrl(int port, StreamCodec codec)
    {
        return $"http://{GetLanIpv4()}:{port}/{SessionSettingsJson.StreamPath(codec)}";
    }

    private static string JsonString(string s) =>
        "\"" + s.Replace("\\", "\\\\").Replace("\"", "\\\"") + "\"";

    public static string GetLanIpv4()
    {
        try
        {
            foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (ni.OperationalStatus != OperationalStatus.Up) continue;
                if (ni.NetworkInterfaceType is NetworkInterfaceType.Loopback) continue;
                foreach (var ua in ni.GetIPProperties().UnicastAddresses)
                {
                    if (ua.Address.AddressFamily == AddressFamily.InterNetwork
                        && !IPAddress.IsLoopback(ua.Address))
                        return ua.Address.ToString();
                }
            }
        }
        catch { /* ignore */ }
        return "127.0.0.1";
    }

    public void Dispose()
    {
        Stop();
        _h264.Dispose();
        _av1.Dispose();
    }
}
