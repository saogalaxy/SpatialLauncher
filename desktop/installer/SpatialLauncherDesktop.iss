; Spatial Launcher Desktop — Inno Setup script
; Compile with Inno Setup 6+ after: tools\desktop_easy_install.ps1 -NoLaunch
; Output: desktop\installer\SpatialLauncherDesktop-Setup.exe

#define MyAppName "Spatial Launcher Desktop"
#define MyAppVersion "0.1.0"
#define MyAppPublisher "saogalaxy"
#define MyAppExeName "SpatialLauncher.Desktop.exe"

[Setup]
AppId={{A7C3E9F1-2B4D-4E8A-9C01-SpatialLauncherDesktop}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
DefaultDirName={localappdata}\SpatialLauncherDesktop\app
DefaultGroupName={#MyAppName}
DisableProgramGroupPage=yes
OutputDir=.
OutputBaseFilename=SpatialLauncherDesktop-Setup
Compression=lzma
SolidCompression=yes
PrivilegesRequired=lowest
ArchitecturesInstallIn64BitMode=x64compatible
WizardStyle=modern

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

[Files]
Source: "publish\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; Tasks: desktopicon

[Run]
Filename: "{app}\{#MyAppExeName}"; Description: "{cm:LaunchProgram,{#StringChange(MyAppName, '&', '&&')}}"; Flags: nowait postinstall skipifsilent
Filename: "netsh"; Parameters: "http add urlacl url=http://+:8765/ user=Everyone"; Flags: runhidden skipifsilent; Verb: runas
