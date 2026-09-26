using System.Text.Json;

namespace SpatialLauncher.Desktop.Core;

/// <summary>Persists <see cref="UserSettings"/> under LocalAppData\SpatialLauncherDesktop.</summary>
public sealed class UserSettingsStore
{
    private static readonly JsonSerializerOptions JsonOpts = new()
    {
        WriteIndented = true,
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        PropertyNameCaseInsensitive = true
    };

    private readonly string _filePath;
    private readonly string _profilesPath;

    public UserSettingsStore()
    {
        string dir = System.IO.Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "SpatialLauncherDesktop");
        Directory.CreateDirectory(dir);
        _filePath = System.IO.Path.Combine(dir, "user_settings.json");
        _profilesPath = System.IO.Path.Combine(dir, "user_setting_profiles.json");
    }

    public string FilePath => _filePath;

    public UserSettings Load()
    {
        try
        {
            if (!File.Exists(_filePath))
                return new UserSettings();
            var loaded = JsonSerializer.Deserialize<UserSettings>(File.ReadAllText(_filePath), JsonOpts);
            return loaded ?? new UserSettings();
        }
        catch
        {
            return new UserSettings();
        }
    }

    public void Save(UserSettings settings)
    {
        File.WriteAllText(_filePath, JsonSerializer.Serialize(settings, JsonOpts));
    }

    /// <summary>Named settings snapshots (profiles), separate from the live file.</summary>
    public List<string> ListProfiles()
    {
        try
        {
            return LoadProfileMap().Keys.OrderBy(k => k, StringComparer.OrdinalIgnoreCase).ToList();
        }
        catch
        {
            return new List<string>();
        }
    }

    public void SaveProfile(string name, UserSettings settings)
    {
        string key = (name ?? "").Trim();
        if (string.IsNullOrEmpty(key))
            throw new ArgumentException("Profile name is empty.", nameof(name));
        var map = LoadProfileMap();
        map[key] = settings;
        WriteProfileMap(map);
    }

    public UserSettings? LoadProfile(string name)
    {
        string key = (name ?? "").Trim();
        if (string.IsNullOrEmpty(key))
            return null;
        var map = LoadProfileMap();
        return map.TryGetValue(key, out var settings) ? settings : null;
    }

    public bool DeleteProfile(string name)
    {
        string key = (name ?? "").Trim();
        if (string.IsNullOrEmpty(key))
            return false;
        var map = LoadProfileMap();
        string? hit = map.Keys.FirstOrDefault(k => k.Equals(key, StringComparison.OrdinalIgnoreCase));
        if (hit == null)
            return false;
        map.Remove(hit);
        WriteProfileMap(map);
        return true;
    }

    private Dictionary<string, UserSettings> LoadProfileMap()
    {
        try
        {
            if (!File.Exists(_profilesPath))
                return new Dictionary<string, UserSettings>(StringComparer.OrdinalIgnoreCase);
            return JsonSerializer.Deserialize<Dictionary<string, UserSettings>>(
                       File.ReadAllText(_profilesPath), JsonOpts)
                   ?? new Dictionary<string, UserSettings>(StringComparer.OrdinalIgnoreCase);
        }
        catch
        {
            return new Dictionary<string, UserSettings>(StringComparer.OrdinalIgnoreCase);
        }
    }

    private void WriteProfileMap(Dictionary<string, UserSettings> map)
    {
        File.WriteAllText(_profilesPath, JsonSerializer.Serialize(map, JsonOpts));
    }
}
