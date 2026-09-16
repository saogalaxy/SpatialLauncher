using System.Net;
using System.Net.Sockets;
using NAudio.CoreAudioApi;
using NAudio.Wave;
using NAudio.Wave.SampleProviders;

namespace SpatialLauncher.Desktop.Core.Stream;

public enum AudioOutputMode
{
    /// <summary>Play on the PC only — do not send audio to Quest.</summary>
    Pc = 0,
    /// <summary>Mirror PC speakers (WASAPI loopback) → Quest UDP. Speakers stay audible.</summary>
    Headset = 1
}

/// <summary>
/// Headset audio: WASAPI loopback of the current Windows default render device
/// (PC speakers keep playing), UDP PCM to the Quest (port 8767).
/// Prefer unicast to the connected viewer; fall back to broadcast.
/// Packet: [seq u32 BE][pcm s16le interleaved stereo 48 kHz].
/// </summary>
public sealed class AudioLinkStreamer : IDisposable
{
    public const int DefaultPort = 8767;
    public const int SampleRate = 48000;
    public const int Channels = 2;
    /// <summary>10 ms of stereo s16le.</summary>
    public const int FrameBytes = SampleRate / 100 * Channels * 2; // 1920

    private WasapiLoopbackCapture? _capture;
    private MMDevice? _captureDevice;
    private BufferedWaveProvider? _captureBuffer;
    private SampleToWaveProvider16? _pcm16;
    private readonly byte[] _frameScratch = new byte[FrameBytes];
    private readonly byte[] _sendScratch = new byte[4 + FrameBytes];
    private UdpClient? _udp;
    private IPEndPoint? _dest;
    private IPAddress? _questIp;
    private Thread? _sendThread;
    private volatile bool _running;
    private uint _seq;
    private readonly object _pcmLock = new();
    private readonly object _destLock = new();
    private readonly Queue<byte[]> _frameQueue = new();
    private const int MaxQueuedFrames = 10; // ~100 ms
    private string? _activeSinkName;
    private string? _preferredSinkId;

    public event Action<string>? StatusChanged;
    public bool IsRunning => _running;
    public int Port { get; private set; } = DefaultPort;
    public AudioOutputMode Mode { get; private set; } = AudioOutputMode.Pc;
    public string? ActiveSinkName => _activeSinkName;

    public string? PreferredSinkId
    {
        get => _preferredSinkId;
        set => _preferredSinkId = string.IsNullOrWhiteSpace(value) ? null : value;
    }

    public void SetQuestEndpoint(IPAddress? address)
    {
        lock (_destLock)
        {
            _questIp = address;
            if (_running)
                _dest = BuildDest(Port);
        }
        if (_running && address != null)
            StatusChanged?.Invoke($"Audio → Quest {address}:{Port} (mirroring speakers)");
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

        var captureInfo = AudioEndpointSwitcher.GetDefaultRender();
        if (captureInfo == null)
        {
            _activeSinkName = null;
            Mode = AudioOutputMode.Pc;
            StatusChanged?.Invoke("Audio: no Windows playback device to mirror");
            return;
        }

        _captureDevice = AudioEndpointSwitcher.OpenDevice(captureInfo.Id);
        if (_captureDevice == null)
        {
            _activeSinkName = null;
            Mode = AudioOutputMode.Pc;
            StatusChanged?.Invoke("Audio: failed to open " + captureInfo.FriendlyName);
            return;
        }

        _udp = new UdpClient();
        _udp.Client.SendBufferSize = 256 * 1024;
        try { _udp.EnableBroadcast = true; } catch { /* ignore */ }
        lock (_destLock)
            _dest = BuildDest(port);

        _capture = new WasapiLoopbackCapture(_captureDevice);
        var srcFormat = _capture.WaveFormat;

        // Capture → buffer → float samples → stereo → 48 kHz → s16le.
        // Explicit float path avoids MediaFoundationResampler static on IeeeFloat loopback.
        _captureBuffer = new BufferedWaveProvider(srcFormat)
        {
            DiscardOnBufferOverflow = true,
            BufferDuration = TimeSpan.FromMilliseconds(200)
        };

        ISampleProvider samples = _captureBuffer.ToSampleProvider();
        samples = ToStereo(samples);
        if (samples.WaveFormat.SampleRate != SampleRate)
            samples = new WdlResamplingSampleProvider(samples, SampleRate);
        _pcm16 = new SampleToWaveProvider16(samples);

        _capture.DataAvailable += OnData;
        _capture.StartRecording();

        _running = true;
        _seq = 0;
        _activeSinkName = captureInfo.FriendlyName;
        _sendThread = new Thread(SendLoop) { IsBackground = true, Name = "SldAudioUdp" };
        _sendThread.Start();

        string destLabel;
        lock (_destLock)
            destLabel = _questIp != null ? $"{_questIp}:{port}" : $"broadcast:{port}";
        StatusChanged?.Invoke(
            $"Audio → Quest ({destLabel}) · mirroring '{captureInfo.FriendlyName}' "
            + $"({srcFormat.SampleRate}Hz {srcFormat.Channels}ch {srcFormat.Encoding} → 48k s16le)");
    }

    public void Stop()
    {
        _running = false;
        try { _sendThread?.Join(400); } catch { /* ignore */ }
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

        // 5.1 / 7.1 etc.: take first two channels (usually L/R).
        var mux = new MultiplexingSampleProvider(new[] { samples }, 2);
        mux.ConnectInputToOutput(0, 0);
        if (ch > 1)
            mux.ConnectInputToOutput(1, 1);
        else
            mux.ConnectInputToOutput(0, 1);
        return mux;
    }

    private IPEndPoint BuildDest(int port)
    {
        if (_questIp != null && !_questIp.Equals(IPAddress.Any) && !_questIp.Equals(IPAddress.None))
            return new IPEndPoint(_questIp, port);
        return new IPEndPoint(IPAddress.Broadcast, port);
    }

    private void OnData(object? sender, WaveInEventArgs e)
    {
        if (!_running || _captureBuffer == null || _pcm16 == null || e.BytesRecorded <= 0)
            return;

        _captureBuffer.AddSamples(e.Buffer, 0, e.BytesRecorded);

        // Drain every full 10 ms frame so we do not pad silence into the middle of speech.
        while (true)
        {
            int read = _pcm16.Read(_frameScratch, 0, FrameBytes);
            if (read < FrameBytes)
                break;

            var copy = new byte[FrameBytes];
            Buffer.BlockCopy(_frameScratch, 0, copy, 0, FrameBytes);
            lock (_pcmLock)
            {
                if (_frameQueue.Count >= MaxQueuedFrames)
                    _frameQueue.Dequeue(); // drop oldest under backlog
                _frameQueue.Enqueue(copy);
                Monitor.PulseAll(_pcmLock);
            }
        }
    }

    private void SendLoop()
    {
        while (_running && _udp != null)
        {
            byte[]? pcm = null;
            lock (_pcmLock)
            {
                while (_running && _frameQueue.Count == 0)
                {
                    if (!Monitor.Wait(_pcmLock, 20))
                        break;
                }
                if (_frameQueue.Count > 0)
                    pcm = _frameQueue.Dequeue();
            }
            if (pcm == null)
                continue;

            IPEndPoint? dest;
            lock (_destLock)
                dest = _dest;
            if (dest == null)
                continue;

            _sendScratch[0] = (byte)((_seq >> 24) & 0xFF);
            _sendScratch[1] = (byte)((_seq >> 16) & 0xFF);
            _sendScratch[2] = (byte)((_seq >> 8) & 0xFF);
            _sendScratch[3] = (byte)(_seq & 0xFF);
            _seq++;
            Buffer.BlockCopy(pcm, 0, _sendScratch, 4, FrameBytes);
            try
            {
                // Single destination — do not also broadcast (double play = static).
                _udp.Send(_sendScratch, 4 + FrameBytes, dest);
            }
            catch
            {
                // drop
            }
        }
    }

    public void Dispose() => Stop();
}
