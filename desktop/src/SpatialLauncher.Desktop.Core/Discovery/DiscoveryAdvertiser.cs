using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;

namespace SpatialLauncher.Desktop.Core.Discovery;

/// <summary>
/// UDP beacon on port 8766 so Quest Desktop Link can auto-find the PC stream URL.
/// Broadcasts periodically and answers "SLD?" pings.
/// </summary>
public sealed class DiscoveryAdvertiser : IDisposable
{
    public const int DefaultPort = 8766;
    public const string AppId = "SpatialLauncherDesktop";

    private UdpClient? _udp;
    private Thread? _thread;
    private volatile bool _running;
    private byte[] _payloadBytes = Array.Empty<byte>();

    public event Action<string>? StatusChanged;
    public bool IsRunning => _running;

    public void Start(string streamUrl, string? displayName = null)
    {
        Stop();
        var payload = new DiscoveryPayload
        {
            App = AppId,
            Stream = streamUrl,
            Name = displayName ?? Environment.MachineName,
            Port = PortFromUrl(streamUrl)
        };
        SetPayload(payload);
        try
        {
            _udp = new UdpClient(DefaultPort);
            _udp.EnableBroadcast = true;
            _udp.Client.ReceiveTimeout = 400;
        }
        catch (SocketException)
        {
            // Port busy — still advertise via unbound client (broadcast only).
            _udp = new UdpClient();
            _udp.EnableBroadcast = true;
        }

        _running = true;
        _thread = new Thread(BeaconLoop) { IsBackground = true, Name = "SldDiscovery" };
        _thread.Start();
        StatusChanged?.Invoke($"Advertising {streamUrl}");
    }

    /// <summary>Hot-swap stream URL (JPEG ↔ MPEG) without bouncing the UDP socket.</summary>
    public void UpdateStream(string streamUrl, string? displayName = null)
    {
        if (!_running)
        {
            Start(streamUrl, displayName);
            return;
        }
        SetPayload(new DiscoveryPayload
        {
            App = AppId,
            Stream = streamUrl,
            Name = displayName ?? Environment.MachineName,
            Port = PortFromUrl(streamUrl)
        });
        StatusChanged?.Invoke($"Advertising {streamUrl}");
    }

    private void SetPayload(DiscoveryPayload payload)
    {
        var jsonOpts = new JsonSerializerOptions { PropertyNamingPolicy = JsonNamingPolicy.CamelCase };
        _payloadBytes = Encoding.UTF8.GetBytes(JsonSerializer.Serialize(payload, jsonOpts));
    }

    public void Stop()
    {
        _running = false;
        try { _udp?.Close(); } catch { /* ignore */ }
        _udp = null;
        try { _thread?.Join(500); } catch { /* ignore */ }
        _thread = null;
    }

    private void BeaconLoop()
    {
        var broadcast = new IPEndPoint(IPAddress.Broadcast, DefaultPort);
        while (_running && _udp != null)
        {
            try
            {
                _udp.Send(_payloadBytes, _payloadBytes.Length, broadcast);
            }
            catch { /* ignore */ }

            try
            {
                var remote = new IPEndPoint(IPAddress.Any, 0);
                byte[] req = _udp.Receive(ref remote);
                string text = Encoding.UTF8.GetString(req);
                if (text.StartsWith("SLD?", StringComparison.OrdinalIgnoreCase))
                    _udp.Send(_payloadBytes, _payloadBytes.Length, remote);
            }
            catch (SocketException) { /* receive timeout */ }
            catch { /* ignore */ }

            Thread.Sleep(200);
        }
    }

    public static string GetLanIpv4()
    {
        try
        {
            using var socket = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, 0);
            socket.Connect("8.8.8.8", 65530);
            if (socket.LocalEndPoint is IPEndPoint ep)
                return ep.Address.ToString();
        }
        catch { /* ignore */ }
        return "127.0.0.1";
    }

    private static int PortFromUrl(string url)
    {
        if (Uri.TryCreate(url, UriKind.Absolute, out var uri) && uri.Port > 0)
            return uri.Port;
        return 8765;
    }

    public void Dispose() => Stop();

    public sealed class DiscoveryPayload
    {
        public string App { get; set; } = AppId;
        public string Stream { get; set; } = "";
        public string Name { get; set; } = "";
        public int Port { get; set; } = 8765;
    }
}
