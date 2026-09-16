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

    public UserSettingsStore()
    {
        string dir = System.IO.Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "SpatialLauncherDesktop");
        Directory.CreateDirectory(dir);
        _filePath = System.IO.Path.Combine(dir, "user_settings.json");
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
}
