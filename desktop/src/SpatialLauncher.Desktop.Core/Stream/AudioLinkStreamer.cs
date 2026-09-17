using System.Net;
using System.Net.Sockets;
using Concentus;
using Concentus.Enums;
using NAudio.CoreAudioApi;
using NAudio.Wave;
using NAudio.Wave.SampleProviders;

namespace SpatialLauncher.Desktop.Core.Stream;

public enum AudioOutputMode
{
    /// <summary>Play on the PC only — do not send audio to Quest.</summary>
    Pc = 0,
    /// <summary>Mirror PC speakers (WASAPI loopback) → Quest UDP Opus. Speakers stay audible.</summary>
    Headset = 1
}

/// <summary>
/// Headset audio: WASAPI loopback of the Windows default render device
/// (PC speakers keep playing), Opus-compressed UDP to the Quest (port 8767).
/// Packet: [OP magic][ver][flags][seq][pts_us][len][opus].
/// </summary>
public sealed class AudioLinkStreamer : IDisposable
{
    public const int DefaultPort = 8767;
    public const int SampleRate = 48000;
    public const int Channels = 2;
    public const int FrameSamples = 960; // 20 ms @ 48 kHz
    public const int FrameShorts = FrameSamples * Channels;
    public const ushort PacketMagic = 0x4F50; // 'OP'
    public const byte PacketVersion = 1;

    private WasapiLoopbackCapture? _capture;
    private MMDevice? _captureDevice;
    private BufferedWaveProvider? _captureBuffer;
    private SampleToWaveProvider16? _pcm16;
    private IOpusEncoder? _encoder;
    private readonly short[] _pcmFrame = new short[FrameShorts];
    private readonly byte[] _pcmBytes = new byte[FrameShorts * 2];
    private readonly byte[] _opusScratch = new byte[1276];
    private UdpClient? _udp;
    private IPEndPoint? _dest;
    private IPAddress? _questIp;
    private Thread? _sendThread;
    private Thread? _paceThread;
    private volatile bool _running;
    private uint _seq;
    private long _ptsUs;
    private readonly object _pcmLock = new();
    private readonly object _destLock = new();
    private readonly Queue<byte[]> _frameQueue = new();
    private const int MaxQueuedFrames = 3; // ~60 ms — keep send latency low
    private string? _activeSinkName;
    private long _captureCallbacks;
    private long _framesEncoded;
    private long _framesSent;
    private long _framesDroppedNoDest;
    private long _lastStatusTick;

    public event Action<string>? StatusChanged;
    public bool IsRunning => _running;
    public int Port { get; private set; } = DefaultPort;
    public AudioOutputMode Mode { get; private set; } = AudioOutputMode.Pc;
    public string? ActiveSinkName => _activeSinkName;
    public long FramesSent => Interlocked.Read(ref _framesSent);
    public long FramesEncoded => Interlocked.Read(ref _framesEncoded);
    public long FramesDroppedNoDest => Interlocked.Read(ref _framesDroppedNoDest);
    public string? DestLabel
    {
        get
        {
            lock (_destLock)
                return _dest?.ToString();
        }
    }

    public string DebugJson()
    {
        string dest = DestLabel ?? "none";
        string sink = _activeSinkName == null
            ? "null"
            : "\"" + _activeSinkName.Replace("\\", "\\\\").Replace("\"", "\\\"") + "\"";
        return $"{{\"running\":{(_running ? "true" : "false")},\"mode\":\"{(Mode == AudioOutputMode.Headset ? "headset" : "pc")}\",\"dest\":\"{dest}\",\"sink\":{sink},\"encoded\":{FramesEncoded},\"sent\":{FramesSent},\"droppedNoDest\":{FramesDroppedNoDest},\"captureCallbacks\":{Interlocked.Read(ref _captureCallbacks)}}}";
    }

    public void SetQuestEndpoint(IPAddress? address)
    {
        address = NormalizeIp(address);
        lock (_destLock)
        {
            _questIp = address;
            if (_running)
                _dest = BuildDest(Port);
        }
        if (_running && address != null)
            StatusChanged?.Invoke($"Audio → Quest {address}:{Port} (Opus mirror)");
    }

    private static IPAddress? NormalizeIp(IPAddress? address)
    {
        if (address == null)
            return null;
        try
        {
            if (address.IsIPv4MappedToIPv6)
                return address.MapToIPv4();
        }
        catch
        {
            // keep original
        }
        return address;
    }

    public void ApplyMode(AudioOutputMode mode, int port = DefaultPort)
    {
        Mode = mode;
        if (mode == AudioOutputMode.Headset)
        {
            if (!_running)
                Start(port);
        }
        else if (_running)
        {
            Stop();
        }
        else
        {
            _activeSinkName = null;
            StatusChanged?.Invoke("Audio → PC");
        }
    }

    public void Start(int port = DefaultPort)
    {
        Stop();
        Mode = AudioOutputMode.Headset;
        Port = port;
        Interlocked.Exchange(ref _captureCallbacks, 0);
        Interlocked.Exchange(ref _framesEncoded, 0);
        Interlocked.Exchange(ref _framesSent, 0);
        Interlocked.Exchange(ref _framesDroppedNoDest, 0);

        using var enumerator = new MMDeviceEnumerator();
        MMDevice? device;
        try
        {
            device = enumerator.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);
        }
        catch
        {
            device = null;
        }
        if (device == null)
        {
            _activeSinkName = null;
            Mode = AudioOutputMode.Pc;
            StatusChanged?.Invoke("Audio: no Windows playback device to mirror");
            return;
        }

        _captureDevice = device;
        _udp = new UdpClient();
        _udp.Client.SendBufferSize = 256 * 1024;
        lock (_destLock)
            _dest = BuildDest(port);

        _capture = new WasapiLoopbackCapture(_captureDevice);
        var srcFormat = _capture.WaveFormat;

        // Capture → buffer → float → stereo → 48 kHz → s16le → Opus.
        // Explicit float path avoids MediaFoundationResampler static on IeeeFloat loopback.
        _captureBuffer = new BufferedWaveProvider(srcFormat)
        {
            DiscardOnBufferOverflow = true,
            BufferDuration = TimeSpan.FromMilliseconds(400)
        };

        ISampleProvider samples = _captureBuffer.ToSampleProvider();
        samples = ToStereo(samples);
        if (samples.WaveFormat.SampleRate != SampleRate)
            samples = new WdlResamplingSampleProvider(samples, SampleRate);
        _pcm16 = new SampleToWaveProvider16(samples);

        _encoder = OpusCodecFactory.CreateEncoder(SampleRate, Channels, OpusApplication.OPUS_APPLICATION_AUDIO);
        _encoder.Bitrate = 128000;
        _encoder.Complexity = 3; // lower = less encode latency on CPU
        _encoder.UseInbandFEC = true;

        _capture.DataAvailable += OnData;
        _capture.StartRecording();

        _running = true;
        _seq = 0;
        _ptsUs = 0;
        _activeSinkName = device.FriendlyName;
        _sendThread = new Thread(SendLoop) { IsBackground = true, Name = "SldAudioUdp" };
        _paceThread = new Thread(PaceLoop) { IsBackground = true, Name = "SldAudioPace" };
        _sendThread.Start();
        _paceThread.Start();

        string destLabel;
        lock (_destLock)
            destLabel = _questIp != null ? $"{_questIp}:{port}" : $"pending:{port}";
        StatusChanged?.Invoke(
            $"Audio → Quest ({destLabel}) · Opus mirroring '{device.FriendlyName}' "
            + $"({srcFormat.SampleRate}Hz {srcFormat.Channels}ch → 48k Opus)");
    }

    public void Stop()
    {
        _running = false;
        try { _paceThread?.Join(400); } catch { /* ignore */ }
        try { _sendThread?.Join(400); } catch { /* ignore */ }
        _paceThread = null;
        _sendThread = null;

        if (_capture != null)
        {
            try { _capture.StopRecording(); } catch { /* ignore */ }
            _capture.DataAvailable -= OnData;
            _capture.Dispose();
            _capture = null;
        }

        _pcm16 = null;
        _captureBuffer = null;
        _encoder = null;
        lock (_pcmLock)
            _frameQueue.Clear();

        try { _captureDevice?.Dispose(); } catch { /* ignore */ }
        _captureDevice = null;

        try { _udp?.Dispose(); } catch { /* ignore */ }
        _udp = null;

        _activeSinkName = null;
        if (Mode == AudioOutputMode.Headset)
            Mode = AudioOutputMode.Pc;
        StatusChanged?.Invoke("Audio → PC");
    }

    private static ISampleProvider ToStereo(ISampleProvider samples)
    {
        int ch = samples.WaveFormat.Channels;
        if (ch == 2)
            return samples;
        if (ch == 1)
            return new MonoToStereoSampleProvider(samples);

        var mux = new MultiplexingSampleProvider(new[] { samples }, 2);
        mux.ConnectInputToOutput(0, 0);
        if (ch > 1)
            mux.ConnectInputToOutput(1, 1);
        else
            mux.ConnectInputToOutput(0, 1);
        return mux;
    }

    private IPEndPoint? BuildDest(int port)
    {
        var ip = NormalizeIp(_questIp);
        if (ip != null && !ip.Equals(IPAddress.Any) && !ip.Equals(IPAddress.None))
            return new IPEndPoint(ip, port);
        return null; // wait for viewer IP — never broadcast (double-play static)
    }

    private void OnData(object? sender, WaveInEventArgs e)
    {
        if (!_running || _captureBuffer == null || e.BytesRecorded <= 0)
            return;
        Interlocked.Increment(ref _captureCallbacks);
        _captureBuffer.AddSamples(e.Buffer, 0, e.BytesRecorded);
    }

    /// <summary>
    /// Pace Opus frames every 20 ms. WASAPI loopback often goes quiet when the
    /// render graph is idle — pad silence so Quest still gets a live stream.
    /// </summary>
    private void PaceLoop()
    {
        var sw = System.Diagnostics.Stopwatch.StartNew();
        long nextMs = 0;
        while (_running)
        {
            nextMs += 20;
            long sleep = nextMs - sw.ElapsedMilliseconds;
            if (sleep > 0)
                Thread.Sleep((int)Math.Min(sleep, 20));
            else if (sleep < -200)
                nextMs = sw.ElapsedMilliseconds; // resync after stall

            EncodeOneFrame();
            MaybeLogStats();
        }
    }

    private void EncodeOneFrame()
    {
        if (!_running || _pcm16 == null || _encoder == null)
            return;

        int read = _pcm16.Read(_pcmBytes, 0, _pcmBytes.Length);
        if (read < _pcmBytes.Length)
            Array.Clear(_pcmBytes, Math.Max(0, read), _pcmBytes.Length - Math.Max(0, read));

        Buffer.BlockCopy(_pcmBytes, 0, _pcmFrame, 0, _pcmBytes.Length);
        int encoded;
        try
        {
            encoded = _encoder.Encode(_pcmFrame, FrameSamples, _opusScratch, _opusScratch.Length);
        }
        catch
        {
            return;
        }
        if (encoded <= 0)
            return;

        var packet = new byte[18 + encoded];
        WriteHeader(packet, _seq++, _ptsUs, (ushort)encoded);
        Buffer.BlockCopy(_opusScratch, 0, packet, 18, encoded);
        _ptsUs += 20_000;
        Interlocked.Increment(ref _framesEncoded);

        lock (_pcmLock)
        {
            if (_frameQueue.Count >= MaxQueuedFrames)
                _frameQueue.Dequeue();
            _frameQueue.Enqueue(packet);
            Monitor.PulseAll(_pcmLock);
        }
    }

    private void MaybeLogStats()
    {
        long now = Environment.TickCount64;
        if (now - _lastStatusTick < 3000)
            return;
        _lastStatusTick = now;
        string dest = DestLabel ?? "pending";
        StatusChanged?.Invoke(
            $"Audio Opus · dest={dest} · sent={FramesSent} enc={FramesEncoded} "
            + $"dropNoDest={FramesDroppedNoDest} capCb={Interlocked.Read(ref _captureCallbacks)}");
    }

    private static void WriteHeader(byte[] packet, uint seq, long ptsUs, ushort opusLen)
    {
        packet[0] = (byte)(PacketMagic >> 8);
        packet[1] = (byte)(PacketMagic & 0xFF);
        packet[2] = PacketVersion;
        packet[3] = 0; // flags
        packet[4] = (byte)((seq >> 24) & 0xFF);
        packet[5] = (byte)((seq >> 16) & 0xFF);
        packet[6] = (byte)((seq >> 8) & 0xFF);
        packet[7] = (byte)(seq & 0xFF);
        ulong pts = unchecked((ulong)ptsUs);
        packet[8] = (byte)((pts >> 56) & 0xFF);
        packet[9] = (byte)((pts >> 48) & 0xFF);
        packet[10] = (byte)((pts >> 40) & 0xFF);
        packet[11] = (byte)((pts >> 32) & 0xFF);
        packet[12] = (byte)((pts >> 24) & 0xFF);
        packet[13] = (byte)((pts >> 16) & 0xFF);
        packet[14] = (byte)((pts >> 8) & 0xFF);
        packet[15] = (byte)(pts & 0xFF);
        packet[16] = (byte)((opusLen >> 8) & 0xFF);
        packet[17] = (byte)(opusLen & 0xFF);
    }

    private void SendLoop()
    {
        while (_running && _udp != null)
        {
            byte[]? packet = null;
            lock (_pcmLock)
            {
                while (_running && _frameQueue.Count == 0)
                {
                    if (!Monitor.Wait(_pcmLock, 20))
                        break;
                }
                if (_frameQueue.Count > 0)
                    packet = _frameQueue.Dequeue();
            }
            if (packet == null)
                continue;

            IPEndPoint? dest;
            lock (_destLock)
                dest = _dest;
            if (dest == null)
            {
                Interlocked.Increment(ref _framesDroppedNoDest);
                continue;
            }

            try
            {
                _udp.Send(packet, packet.Length, dest);
                Interlocked.Increment(ref _framesSent);
            }
            catch
            {
                // drop
            }
        }
    }

    public void Dispose() => Stop();
}
