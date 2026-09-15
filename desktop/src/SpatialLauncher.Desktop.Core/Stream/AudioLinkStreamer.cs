using System.Net;
using System.Net.Sockets;
using NAudio.CoreAudioApi;
using NAudio.Wave;

namespace SpatialLauncher.Desktop.Core.Stream;

public enum AudioOutputMode
{
    /// <summary>Play on the PC only — do not send audio to Quest.</summary>
    Pc = 0,
    /// <summary>Pipe WASAPI loopback to Quest and mute the PC speakers.</summary>
    Headset = 1
}

/// <summary>
/// Low-latency WASAPI loopback → UDP PCM to Quest (port 8767).
/// Packet: [seq u32 BE][pcm s16le interleaved].
/// </summary>
public sealed class AudioLinkStreamer : IDisposable
{
    public const int DefaultPort = 8767;
    public const int SampleRate = 48000;
    public const int Channels = 2;

    private WasapiLoopbackCapture? _capture;
    private IWaveProvider? _resampler;
    private BufferedWaveProvider? _buffer;
    private UdpClient? _udp;
    private IPEndPoint? _dest;
    private Thread? _sendThread;
    private volatile bool _running;
    private uint _seq;
    private bool _mutedPc;
    private float _savedVolume = 1f;
    private bool _savedMute;
    private MMDevice? _renderDevice;
    private readonly object _pcmLock = new();
    private byte[]? _latestPcm;
    private int _pcmGen;

    public event Action<string>? StatusChanged;
    public bool IsRunning => _running;
    public int Port { get; private set; } = DefaultPort;
    public AudioOutputMode Mode { get; private set; } = AudioOutputMode.Pc;

    /// <summary>PC = local speakers only. Headset = loopback → Quest UDP + mute PC.</summary>
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
            StatusChanged?.Invoke("Audio → PC");
        }
    }

    public void Start(int port = DefaultPort)
    {
        Stop();
        Mode = AudioOutputMode.Headset;
        Port = port;
        _udp = new UdpClient();
        _udp.Client.SendBufferSize = 256 * 1024;
        // Broadcast to LAN; Quest binds :8767 and filters by presence of stream.
        _dest = new IPEndPoint(IPAddress.Broadcast, port);
        try { _udp.EnableBroadcast = true; } catch { /* ignore */ }

        _capture = new WasapiLoopbackCapture();
        var format = _capture.WaveFormat;
        _buffer = new BufferedWaveProvider(format)
        {
            DiscardOnBufferOverflow = true,
            BufferDuration = TimeSpan.FromMilliseconds(100)
        };
        _resampler = new MediaFoundationResampler(_buffer, new WaveFormat(SampleRate, 16, Channels))
        {
            ResamplerQuality = 30
        };

        _capture.DataAvailable += OnData;
        _capture.StartRecording();

        MutePcSpeakers(true);

        _running = true;
        _seq = 0;
        _sendThread = new Thread(SendLoop) { IsBackground = true, Name = "SldAudioUdp" };
        _sendThread.Start();
        StatusChanged?.Invoke($"Audio → Quest UDP :{port} (PC speakers muted)");
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

        (_resampler as MediaFoundationResampler)?.Dispose();
        _resampler = null;
        _buffer = null;

        try { _udp?.Dispose(); } catch { /* ignore */ }
        _udp = null;

        MutePcSpeakers(false);
        StatusChanged?.Invoke("Audio → PC");
    }

    private void OnData(object? sender, WaveInEventArgs e)
    {
        if (!_running || _buffer == null || e.BytesRecorded <= 0) return;
        _buffer.AddSamples(e.Buffer, 0, e.BytesRecorded);

        // Pull resampled PCM chunks (~10 ms).
        if (_resampler == null) return;
        int bytesPer10ms = SampleRate / 100 * Channels * 2;
        var chunk = new byte[bytesPer10ms];
        int read = _resampler.Read(chunk, 0, chunk.Length);
        if (read <= 0) return;
        if (read < chunk.Length)
            Array.Clear(chunk, read, chunk.Length - read);

        lock (_pcmLock)
        {
            _latestPcm = chunk;
            _pcmGen++;
            Monitor.PulseAll(_pcmLock);
        }
    }

    private void SendLoop()
    {
        int lastGen = -1;
        while (_running && _udp != null && _dest != null)
        {
            byte[]? pcm;
            int gen;
            lock (_pcmLock)
            {
                while (_running && (_latestPcm == null || _pcmGen == lastGen))
                {
                    if (!Monitor.Wait(_pcmLock, 20))
                        break;
                }
                pcm = _latestPcm;
                gen = _pcmGen;
            }
            if (pcm == null || gen == lastGen)
                continue;
            lastGen = gen;

            var packet = new byte[4 + pcm.Length];
            packet[0] = (byte)((_seq >> 24) & 0xFF);
            packet[1] = (byte)((_seq >> 16) & 0xFF);
            packet[2] = (byte)((_seq >> 8) & 0xFF);
            packet[3] = (byte)(_seq & 0xFF);
            _seq++;
            Buffer.BlockCopy(pcm, 0, packet, 4, pcm.Length);
            try
            {
                _udp.Send(packet, packet.Length, _dest);
            }
            catch
            {
                // drop
            }
        }
    }

    private void MutePcSpeakers(bool mute)
    {
        try
        {
            if (mute && !_mutedPc)
            {
                using var enumDev = new MMDeviceEnumerator();
                _renderDevice = enumDev.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);
                _savedMute = _renderDevice.AudioEndpointVolume.Mute;
                _savedVolume = _renderDevice.AudioEndpointVolume.MasterVolumeLevelScalar;
                _renderDevice.AudioEndpointVolume.Mute = true;
                _mutedPc = true;
            }
            else if (!mute && _mutedPc && _renderDevice != null)
            {
                _renderDevice.AudioEndpointVolume.Mute = _savedMute;
                _renderDevice.AudioEndpointVolume.MasterVolumeLevelScalar = _savedVolume;
                _renderDevice.Dispose();
                _renderDevice = null;
                _mutedPc = false;
            }
        }
        catch (Exception ex)
        {
            StatusChanged?.Invoke("Audio mute: " + ex.Message);
        }
    }

    public void Dispose() => Stop();
}
