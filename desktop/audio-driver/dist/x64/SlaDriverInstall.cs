// Compiled at install time via Add-Type -Path (not embedded in .ps1).
using System;
using System.Runtime.InteropServices;
using System.Text;

public static class SlaDriverInstall
{
    const int DIGCF_PRESENT = 0x00000002;
    const int DIF_REGISTERDEVICE = 0x00000019;
    const int SPDRP_HARDWAREID = 0x00000001;
    const uint INSTALLFLAG_FORCE = 0x00000001;
    const int MAX_CLASS_NAME_LEN = 32;

    [StructLayout(LayoutKind.Sequential)]
    public struct SP_DEVINFO_DATA
    {
        public int cbSize;
        public Guid ClassGuid;
        public int DevInst;
        public IntPtr Reserved;
    }

    [DllImport("setupapi.dll", CharSet = CharSet.Auto, SetLastError = true)]
    static extern IntPtr SetupDiCreateDeviceInfoList(ref Guid ClassGuid, IntPtr hwndParent);

    [DllImport("setupapi.dll", CharSet = CharSet.Auto, SetLastError = true)]
    static extern bool SetupDiDestroyDeviceInfoList(IntPtr DeviceInfoSet);

    [DllImport("setupapi.dll", CharSet = CharSet.Auto, SetLastError = true)]
    static extern bool SetupDiCreateDeviceInfo(
        IntPtr DeviceInfoSet,
        string DeviceName,
        ref Guid ClassGuid,
        string DeviceDescription,
        IntPtr hwndParent,
        int CreationFlags,
        ref SP_DEVINFO_DATA DeviceInfoData);

    [DllImport("setupapi.dll", CharSet = CharSet.Auto, SetLastError = true)]
    static extern bool SetupDiSetDeviceRegistryProperty(
        IntPtr DeviceInfoSet,
        ref SP_DEVINFO_DATA DeviceInfoData,
        int Property,
        byte[] PropertyBuffer,
        int PropertyBufferSize);

    [DllImport("setupapi.dll", CharSet = CharSet.Auto, SetLastError = true)]
    static extern bool SetupDiCallClassInstaller(
        int InstallFunction,
        IntPtr DeviceInfoSet,
        ref SP_DEVINFO_DATA DeviceInfoData);

    [DllImport("setupapi.dll", CharSet = CharSet.Auto, SetLastError = true)]
    static extern bool SetupDiGetINFClass(
        string InfName,
        out Guid ClassGuid,
        StringBuilder ClassName,
        int ClassNameSize,
        IntPtr RequiredSize);

    [DllImport("newdev.dll", CharSet = CharSet.Auto, SetLastError = true)]
    static extern bool UpdateDriverForPlugAndPlayDevices(
        IntPtr hwndParent,
        string HardwareId,
        string FullInfPath,
        uint InstallFlags,
        out bool bRebootRequired);

    [DllImport("setupapi.dll", CharSet = CharSet.Auto, SetLastError = true)]
    static extern IntPtr SetupDiGetClassDevs(
        ref Guid ClassGuid,
        string Enumerator,
        IntPtr hwndParent,
        int Flags);

    [DllImport("setupapi.dll", CharSet = CharSet.Auto, SetLastError = true)]
    static extern bool SetupDiEnumDeviceInfo(
        IntPtr DeviceInfoSet,
        int MemberIndex,
        ref SP_DEVINFO_DATA DeviceInfoData);

    [DllImport("setupapi.dll", CharSet = CharSet.Auto, SetLastError = true)]
    static extern bool SetupDiGetDeviceRegistryProperty(
        IntPtr DeviceInfoSet,
        ref SP_DEVINFO_DATA DeviceInfoData,
        int Property,
        out int PropertyRegDataType,
        byte[] PropertyBuffer,
        int PropertyBufferSize,
        out int RequiredSize);

    public static string Install(string infPath, string hardwareId, string description)
    {
        Guid classGuid;
        var className = new StringBuilder(MAX_CLASS_NAME_LEN);
        if (!SetupDiGetINFClass(infPath, out classGuid, className, className.Capacity, IntPtr.Zero))
            return "SetupDiGetINFClass failed: " + Marshal.GetLastWin32Error();

        if (DeviceExists(classGuid, hardwareId))
            return "OK (already installed)";

        IntPtr set = SetupDiCreateDeviceInfoList(ref classGuid, IntPtr.Zero);
        if (set == IntPtr.Zero || set == new IntPtr(-1))
            return "SetupDiCreateDeviceInfoList failed: " + Marshal.GetLastWin32Error();

        try
        {
            var data = new SP_DEVINFO_DATA();
            data.cbSize = Marshal.SizeOf(typeof(SP_DEVINFO_DATA));
            if (!SetupDiCreateDeviceInfo(
                    set, className.ToString(), ref classGuid, description, IntPtr.Zero, 0x00000001, ref data))
                return "SetupDiCreateDeviceInfo failed: " + Marshal.GetLastWin32Error();

            byte[] hwid = Encoding.Unicode.GetBytes(hardwareId + "\0\0");
            if (!SetupDiSetDeviceRegistryProperty(set, ref data, SPDRP_HARDWAREID, hwid, hwid.Length))
                return "SetupDiSetDeviceRegistryProperty failed: " + Marshal.GetLastWin32Error();

            if (!SetupDiCallClassInstaller(DIF_REGISTERDEVICE, set, ref data))
                return "SetupDiCallClassInstaller(REGISTERDEVICE) failed: " + Marshal.GetLastWin32Error();

            bool reboot;
            if (!UpdateDriverForPlugAndPlayDevices(IntPtr.Zero, hardwareId, infPath, INSTALLFLAG_FORCE, out reboot))
                return "UpdateDriverForPlugAndPlayDevices failed: " + Marshal.GetLastWin32Error();

            return reboot ? "OK (reboot recommended)" : "OK";
        }
        finally
        {
            SetupDiDestroyDeviceInfoList(set);
        }
    }

    static bool DeviceExists(Guid classGuid, string hardwareId)
    {
        IntPtr set = SetupDiGetClassDevs(ref classGuid, null, IntPtr.Zero, DIGCF_PRESENT);
        if (set == IntPtr.Zero || set == new IntPtr(-1))
            return false;
        try
        {
            var data = new SP_DEVINFO_DATA();
            data.cbSize = Marshal.SizeOf(typeof(SP_DEVINFO_DATA));
            for (int i = 0; SetupDiEnumDeviceInfo(set, i, ref data); i++)
            {
                int type, req;
                byte[] buf = new byte[512];
                if (!SetupDiGetDeviceRegistryProperty(
                        set, ref data, SPDRP_HARDWAREID, out type, buf, buf.Length, out req))
                    continue;
                string ids = Encoding.Unicode.GetString(buf);
                if (ids.IndexOf(hardwareId, StringComparison.OrdinalIgnoreCase) >= 0)
                    return true;
            }
            return false;
        }
        finally
        {
            SetupDiDestroyDeviceInfoList(set);
        }
    }
}
