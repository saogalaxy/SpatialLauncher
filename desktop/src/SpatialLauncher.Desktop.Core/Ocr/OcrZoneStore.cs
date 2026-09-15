using System.Text.Json;

namespace SpatialLauncher.Desktop.Core.Ocr;

public sealed class OcrZoneStore
{
    private readonly string _path;

    public OcrZoneStore()
    {
        string dir = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "SpatialLauncherDesktop");
        Directory.CreateDirectory(dir);
        _path = Path.Combine(dir, "ocr_zones.json");
    }

    public List<OcrZone> Load(string? profileKey = null)
    {
        try
        {
            if (!File.Exists(_path)) return new List<OcrZone>();
            var map = JsonSerializer.Deserialize<Dictionary<string, List<OcrZone>>>(File.ReadAllText(_path))
                      ?? new Dictionary<string, List<OcrZone>>();
            string key = string.IsNullOrEmpty(profileKey) ? "_default" : profileKey;
            return map.TryGetValue(key, out var zones) ? zones : new List<OcrZone>();
        }
        catch
        {
            return new List<OcrZone>();
        }
    }

    public void Save(IReadOnlyList<OcrZone> zones, string? profileKey = null)
    {
        var map = new Dictionary<string, List<OcrZone>>();
        try
        {
            if (File.Exists(_path))
            {
                map = JsonSerializer.Deserialize<Dictionary<string, List<OcrZone>>>(File.ReadAllText(_path))
                      ?? new Dictionary<string, List<OcrZone>>();
            }
        }
        catch { /* ignore */ }

        string key = string.IsNullOrEmpty(profileKey) ? "_default" : profileKey;
        map[key] = zones.ToList();
        File.WriteAllText(_path, JsonSerializer.Serialize(map, new JsonSerializerOptions { WriteIndented = true }));
    }
}
