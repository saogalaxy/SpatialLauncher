using System.Runtime.InteropServices;
using NAudio.CoreAudioApi;

namespace SpatialLauncher.Desktop.Core.Stream;

/// <summary>
/// Finds Spatial Launcher Audio (or fallback virtual sinks) and switches the
/// Windows default render endpoint via undocumented IPolicyConfig.
/// </summary>
public static class AudioEndpointSwitcher
{
    public const string ProductSinkLabel = "Spatial Launcher Audio";

    /// <summary>Primary product sink first; other virtual cables are last-resort only.</summary>
    private static readonly string[] VirtualNameHints =
    {
        "Spatial Launcher Audio",
        "Virtual Audio Driver",
        "Virtual Audio Driver by MTT",
        "Virtual Desktop Audio",
        "CABLE Input",
        "VB-Audio",
        "VoiceMeeter Input",
        "VoiceMeeter Aux Input",
        "Steam Streaming Speakers",
    };

    public sealed class EndpointInfo
    {
        public required string Id { get; init; }
        public required string FriendlyName { get; init; }
        /// <summary>UI label without doubling the product name.</summary>
        public string DisplayName =>
            FriendlyName.IndexOf(ProductSinkLabel, StringComparison.OrdinalIgnoreCase) >= 0
                ? FriendlyName
                : ProductSinkLabel + " — " + FriendlyName;
    }

    public static bool HasSpatialLauncherAudio()
    {
        foreach (var s in ListVirtualSinks())
        {
            if (s.FriendlyName.IndexOf(ProductSinkLabel, StringComparison.OrdinalIgnoreCase) >= 0
                || s.FriendlyName.IndexOf("Virtual Audio Driver", StringComparison.OrdinalIgnoreCase) >= 0
                || s.FriendlyName.IndexOf("Virtual Desktop Audio", StringComparison.OrdinalIgnoreCase) >= 0)
                return true;
        }
        return false;
    }

    /// <summary>All active render devices that look like a virtual sink.</summary>
    public static List<EndpointInfo> ListVirtualSinks()
    {
        var list = new List<EndpointInfo>();
        var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        using var enumerator = new MMDeviceEnumerator();
        MMDeviceCollection? devices = null;
        try
        {
            // Include Unplugged/Disabled so we still surface Virtual Desktop Audio /
            // freshly installed sinks Windows has not marked Active yet.
            devices = enumerator.EnumerateAudioEndPoints(
                DataFlow.Render,
                DeviceState.Active | DeviceState.Unplugged | DeviceState.Disabled);
            foreach (var device in devices)
            {
                string id = device.ID;
                if (!seen.Add(id))
                    continue;
                string name = device.FriendlyName ?? "";
                bool match = false;
                foreach (var hint in VirtualNameHints)
                {
                    if (name.IndexOf(hint, StringComparison.OrdinalIgnoreCase) >= 0)
                    {
                        match = true;
                        break;
                    }
                }
                if (!match)
                    continue;
                list.Add(new EndpointInfo { Id = id, FriendlyName = name });
            }
            list.Sort((a, b) =>
            {
                int c = Rank(a).CompareTo(Rank(b));
                return c != 0
                    ? c
                    : string.Compare(a.FriendlyName, b.FriendlyName, StringComparison.OrdinalIgnoreCase);
            });
        }
        finally
        {
            if (devices != null)
            {
                foreach (var device in devices)
                    try { device.Dispose(); } catch { /* ignore */ }
            }
        }
        return list;
    }

    private static int Rank(EndpointInfo e)
    {
        string n = e.FriendlyName;
        if (n.IndexOf(ProductSinkLabel, StringComparison.OrdinalIgnoreCase) >= 0) return 0;
        if (n.IndexOf("Virtual Audio Driver", StringComparison.OrdinalIgnoreCase) >= 0) return 1;
        if (n.IndexOf("Virtual Desktop Audio", StringComparison.OrdinalIgnoreCase) >= 0) return 2;
        if (n.IndexOf("CABLE", StringComparison.OrdinalIgnoreCase) >= 0) return 3;
        if (n.IndexOf("VB-Audio", StringComparison.OrdinalIgnoreCase) >= 0) return 4;
        if (n.IndexOf("VoiceMeeter", StringComparison.OrdinalIgnoreCase) >= 0) return 5;
        return 10; // Steam Streaming Speakers etc.
    }

    /// <summary>Preferred ID if still present, else best-ranked virtual sink, or null.</summary>
    public static EndpointInfo? TryFindVirtualSink(string? preferredId = null)
    {
        var sinks = ListVirtualSinks();
        if (sinks.Count == 0)
            return null;
        if (!string.IsNullOrWhiteSpace(preferredId))
        {
            foreach (var s in sinks)
            {
                if (string.Equals(s.Id, preferredId, StringComparison.OrdinalIgnoreCase))
                    return s;
            }
        }
        // Prefer Spatial Launcher Audio / Virtual Audio Driver over Steam.
        foreach (var s in sinks)
        {
            if (Rank(s) <= 1)
                return s;
        }
        return sinks[0];
    }

    public static EndpointInfo? GetDefaultRender()
    {
        try
        {
            using var enumerator = new MMDeviceEnumerator();
            using var device = enumerator.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);
            return new EndpointInfo
            {
                Id = device.ID,
                FriendlyName = device.FriendlyName ?? device.ID
            };
        }
        catch
        {
            return null;
        }
    }

    public static MMDevice? OpenDevice(string deviceId)
    {
        if (string.IsNullOrWhiteSpace(deviceId)) return null;
        try
        {
            using var enumerator = new MMDeviceEnumerator();
            return enumerator.GetDevice(deviceId);
        }
        catch
        {
            return null;
        }
    }

    public static bool TrySetDefaultRender(string deviceId)
    {
        if (string.IsNullOrWhiteSpace(deviceId)) return false;
        try
        {
            var type = Type.GetTypeFromCLSID(new Guid("870AF99C-171D-4F9E-AF0D-E63DF40C2BC9"));
            if (type == null) return false;
            object? client = Activator.CreateInstance(type);
            if (client == null) return false;
            try
            {
                var policy = (IPolicyConfig)client;
                for (int role = 0; role < 3; role++)
                {
                    int hr = policy.SetDefaultEndpoint(deviceId, role);
                    if (hr != 0)
                        return false;
                }
                return true;
            }
            finally
            {
                Marshal.FinalReleaseComObject(client);
            }
        }
        catch
        {
            return false;
        }
    }

    public static string MissingSinkHint =>
        "Headset mirrors PC speakers — no virtual driver required. Optional Spatial Launcher Audio install is blocked on Secure Boot (Code 52) until Microsoft attestation signing.";

    public static string InstallScriptRelativePath =>
        @"tools\install_spatial_audio_driver.ps1";

    [ComImport]
    [Guid("F8679F50-850A-41CF-9C72-430F290290C8")]
    [InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IPolicyConfig
    {
        [PreserveSig] int GetMixFormat([MarshalAs(UnmanagedType.LPWStr)] string pszDeviceName, IntPtr ppFormat);
        [PreserveSig] int GetDeviceFormat([MarshalAs(UnmanagedType.LPWStr)] string pszDeviceName, int bDefault, IntPtr ppFormat);
        [PreserveSig] int ResetDeviceFormat([MarshalAs(UnmanagedType.LPWStr)] string pszDeviceName);
        [PreserveSig] int SetDeviceFormat([MarshalAs(UnmanagedType.LPWStr)] string pszDeviceName, IntPtr pEndpointFormat, IntPtr mixFormat);
        [PreserveSig] int GetProcessingPeriod([MarshalAs(UnmanagedType.LPWStr)] string pszDeviceName, int bDefault, IntPtr pmftDefaultPeriod, IntPtr pmftMinimumPeriod);
        [PreserveSig] int SetProcessingPeriod([MarshalAs(UnmanagedType.LPWStr)] string pszDeviceName, IntPtr pmftPeriod);
        [PreserveSig] int GetShareMode([MarshalAs(UnmanagedType.LPWStr)] string pszDeviceName, IntPtr pMode);
        [PreserveSig] int SetShareMode([MarshalAs(UnmanagedType.LPWStr)] string pszDeviceName, IntPtr mode);
        [PreserveSig] int GetPropertyValue([MarshalAs(UnmanagedType.LPWStr)] string pszDeviceName, int bFxStore, IntPtr key, IntPtr pv);
        [PreserveSig] int SetPropertyValue([MarshalAs(UnmanagedType.LPWStr)] string pszDeviceName, int bFxStore, IntPtr key, IntPtr pv);
        [PreserveSig] int SetDefaultEndpoint([MarshalAs(UnmanagedType.LPWStr)] string pszDeviceName, int role);
        [PreserveSig] int SetEndpointVisibility([MarshalAs(UnmanagedType.LPWStr)] string pszDeviceName, int bVisible);
    }
}
