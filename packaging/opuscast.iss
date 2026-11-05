; Windows installer (Inno Setup 6):  iscc /DAppVersion=1.1 packaging\opuscast.iss
; Installs dist\OpusCast (made by PyInstaller) for the current user, or for everyone if run as admin.
#ifndef AppVersion
  #define AppVersion "0"
#endif

[Setup]
AppId={{6A0E5C1B-4F43-4F7E-9C2B-0D8A5E3B7C21}
AppName=OpusCast
AppVersion={#AppVersion}
AppPublisher=OpusCast
AppPublisherURL=https://github.com/m1omg/OpusCast
DefaultDirName={autopf}\OpusCast
DefaultGroupName=OpusCast
DisableProgramGroupPage=yes
UninstallDisplayIcon={app}\OpusCast.exe
OutputDir=..\dist
OutputBaseFilename=OpusCast-Setup
SetupIconFile=..\data\opuscast.ico
Compression=lzma2
SolidCompression=yes
LZMANumBlockThreads=4
WizardStyle=modern
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog
CloseApplications=yes

[Languages]
Name: "en"; MessagesFile: "compiler:Default.isl"
Name: "sk"; MessagesFile: "compiler:Languages\Slovak.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

[Files]
Source: "..\dist\OpusCast\*"; DestDir: "{app}"; Flags: recursesubdirs ignoreversion

[Icons]
Name: "{autoprograms}\OpusCast"; Filename: "{app}\OpusCast.exe"
Name: "{autodesktop}\OpusCast"; Filename: "{app}\OpusCast.exe"; Tasks: desktopicon

[Registry]
; "Open with → OpusCast" for videos, music and photos
Root: HKA; Subkey: "Software\Classes\Applications\OpusCast.exe"; ValueType: string; ValueName: "FriendlyAppName"; ValueData: "OpusCast"; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\Classes\Applications\OpusCast.exe\shell\open\command"; ValueType: string; ValueData: """{app}\OpusCast.exe"" ""%1"""
Root: HKA; Subkey: "Software\Classes\Applications\OpusCast.exe\SupportedTypes"; ValueType: string; ValueName: ".mkv"; ValueData: ""
Root: HKA; Subkey: "Software\Classes\.mkv\OpenWithList\OpusCast.exe"; ValueType: string; ValueData: ""; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\Classes\Applications\OpusCast.exe\SupportedTypes"; ValueType: string; ValueName: ".mp4"; ValueData: ""
Root: HKA; Subkey: "Software\Classes\.mp4\OpenWithList\OpusCast.exe"; ValueType: string; ValueData: ""; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\Classes\Applications\OpusCast.exe\SupportedTypes"; ValueType: string; ValueName: ".m4v"; ValueData: ""
Root: HKA; Subkey: "Software\Classes\.m4v\OpenWithList\OpusCast.exe"; ValueType: string; ValueData: ""; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\Classes\Applications\OpusCast.exe\SupportedTypes"; ValueType: string; ValueName: ".mov"; ValueData: ""
Root: HKA; Subkey: "Software\Classes\.mov\OpenWithList\OpusCast.exe"; ValueType: string; ValueData: ""; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\Classes\Applications\OpusCast.exe\SupportedTypes"; ValueType: string; ValueName: ".avi"; ValueData: ""
Root: HKA; Subkey: "Software\Classes\.avi\OpenWithList\OpusCast.exe"; ValueType: string; ValueData: ""; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\Classes\Applications\OpusCast.exe\SupportedTypes"; ValueType: string; ValueName: ".webm"; ValueData: ""
Root: HKA; Subkey: "Software\Classes\.webm\OpenWithList\OpusCast.exe"; ValueType: string; ValueData: ""; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\Classes\Applications\OpusCast.exe\SupportedTypes"; ValueType: string; ValueName: ".ts"; ValueData: ""
Root: HKA; Subkey: "Software\Classes\.ts\OpenWithList\OpusCast.exe"; ValueType: string; ValueData: ""; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\Classes\Applications\OpusCast.exe\SupportedTypes"; ValueType: string; ValueName: ".m2ts"; ValueData: ""
Root: HKA; Subkey: "Software\Classes\.m2ts\OpenWithList\OpusCast.exe"; ValueType: string; ValueData: ""; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\Classes\Applications\OpusCast.exe\SupportedTypes"; ValueType: string; ValueName: ".mts"; ValueData: ""
Root: HKA; Subkey: "Software\Classes\.mts\OpenWithList\OpusCast.exe"; ValueType: string; ValueData: ""; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\Classes\Applications\OpusCast.exe\SupportedTypes"; ValueType: string; ValueName: ".mpg"; ValueData: ""
Root: HKA; Subkey: "Software\Classes\.mpg\OpenWithList\OpusCast.exe"; ValueType: string; ValueData: ""; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\Classes\Applications\OpusCast.exe\SupportedTypes"; ValueType: string; ValueName: ".mpeg"; ValueData: ""
Root: HKA; Subkey: "Software\Classes\.mpeg\OpenWithList\OpusCast.exe"; ValueType: string; ValueData: ""; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\Classes\Applications\OpusCast.exe\SupportedTypes"; ValueType: string; ValueName: ".flv"; ValueData: ""
Root: HKA; Subkey: "Software\Classes\.flv\OpenWithList\OpusCast.exe"; ValueType: string; ValueData: ""; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\Classes\Applications\OpusCast.exe\SupportedTypes"; ValueType: string; ValueName: ".wmv"; ValueData: ""
Root: HKA; Subkey: "Software\Classes\.wmv\OpenWithList\OpusCast.exe"; ValueType: string; ValueData: ""; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\Classes\Applications\OpusCast.exe\SupportedTypes"; ValueType: string; ValueName: ".3gp"; ValueData: ""
Root: HKA; Subkey: "Software\Classes\.3gp\OpenWithList\OpusCast.exe"; ValueType: string; ValueData: ""; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\Classes\Applications\OpusCast.exe\SupportedTypes"; ValueType: string; ValueName: ".mp3"; ValueData: ""
Root: HKA; Subkey: "Software\Classes\.mp3\OpenWithList\OpusCast.exe"; ValueType: string; ValueData: ""; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\Classes\Applications\OpusCast.exe\SupportedTypes"; ValueType: string; ValueName: ".flac"; ValueData: ""
Root: HKA; Subkey: "Software\Classes\.flac\OpenWithList\OpusCast.exe"; ValueType: string; ValueData: ""; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\Classes\Applications\OpusCast.exe\SupportedTypes"; ValueType: string; ValueName: ".m4a"; ValueData: ""
Root: HKA; Subkey: "Software\Classes\.m4a\OpenWithList\OpusCast.exe"; ValueType: string; ValueData: ""; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\Classes\Applications\OpusCast.exe\SupportedTypes"; ValueType: string; ValueName: ".m4b"; ValueData: ""
Root: HKA; Subkey: "Software\Classes\.m4b\OpenWithList\OpusCast.exe"; ValueType: string; ValueData: ""; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\Classes\Applications\OpusCast.exe\SupportedTypes"; ValueType: string; ValueName: ".alac"; ValueData: ""
Root: HKA; Subkey: "Software\Classes\.alac\OpenWithList\OpusCast.exe"; ValueType: string; ValueData: ""; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\Classes\Applications\OpusCast.exe\SupportedTypes"; ValueType: string; ValueName: ".aac"; ValueData: ""
Root: HKA; Subkey: "Software\Classes\.aac\OpenWithList\OpusCast.exe"; ValueType: string; ValueData: ""; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\Classes\Applications\OpusCast.exe\SupportedTypes"; ValueType: string; ValueName: ".ogg"; ValueData: ""
Root: HKA; Subkey: "Software\Classes\.ogg\OpenWithList\OpusCast.exe"; ValueType: string; ValueData: ""; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\Classes\Applications\OpusCast.exe\SupportedTypes"; ValueType: string; ValueName: ".oga"; ValueData: ""
Root: HKA; Subkey: "Software\Classes\.oga\OpenWithList\OpusCast.exe"; ValueType: string; ValueData: ""; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\Classes\Applications\OpusCast.exe\SupportedTypes"; ValueType: string; ValueName: ".opus"; ValueData: ""
Root: HKA; Subkey: "Software\Classes\.opus\OpenWithList\OpusCast.exe"; ValueType: string; ValueData: ""; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\Classes\Applications\OpusCast.exe\SupportedTypes"; ValueType: string; ValueName: ".wav"; ValueData: ""
Root: HKA; Subkey: "Software\Classes\.wav\OpenWithList\OpusCast.exe"; ValueType: string; ValueData: ""; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\Classes\Applications\OpusCast.exe\SupportedTypes"; ValueType: string; ValueName: ".wma"; ValueData: ""
Root: HKA; Subkey: "Software\Classes\.wma\OpenWithList\OpusCast.exe"; ValueType: string; ValueData: ""; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\Classes\Applications\OpusCast.exe\SupportedTypes"; ValueType: string; ValueName: ".aif"; ValueData: ""
Root: HKA; Subkey: "Software\Classes\.aif\OpenWithList\OpusCast.exe"; ValueType: string; ValueData: ""; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\Classes\Applications\OpusCast.exe\SupportedTypes"; ValueType: string; ValueName: ".aiff"; ValueData: ""
Root: HKA; Subkey: "Software\Classes\.aiff\OpenWithList\OpusCast.exe"; ValueType: string; ValueData: ""; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\Classes\Applications\OpusCast.exe\SupportedTypes"; ValueType: string; ValueName: ".mka"; ValueData: ""
Root: HKA; Subkey: "Software\Classes\.mka\OpenWithList\OpusCast.exe"; ValueType: string; ValueData: ""; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\Classes\Applications\OpusCast.exe\SupportedTypes"; ValueType: string; ValueName: ".jpg"; ValueData: ""
Root: HKA; Subkey: "Software\Classes\.jpg\OpenWithList\OpusCast.exe"; ValueType: string; ValueData: ""; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\Classes\Applications\OpusCast.exe\SupportedTypes"; ValueType: string; ValueName: ".jpeg"; ValueData: ""
Root: HKA; Subkey: "Software\Classes\.jpeg\OpenWithList\OpusCast.exe"; ValueType: string; ValueData: ""; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\Classes\Applications\OpusCast.exe\SupportedTypes"; ValueType: string; ValueName: ".png"; ValueData: ""
Root: HKA; Subkey: "Software\Classes\.png\OpenWithList\OpusCast.exe"; ValueType: string; ValueData: ""; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\Classes\Applications\OpusCast.exe\SupportedTypes"; ValueType: string; ValueName: ".gif"; ValueData: ""
Root: HKA; Subkey: "Software\Classes\.gif\OpenWithList\OpusCast.exe"; ValueType: string; ValueData: ""; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\Classes\Applications\OpusCast.exe\SupportedTypes"; ValueType: string; ValueName: ".bmp"; ValueData: ""
Root: HKA; Subkey: "Software\Classes\.bmp\OpenWithList\OpusCast.exe"; ValueType: string; ValueData: ""; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\Classes\Applications\OpusCast.exe\SupportedTypes"; ValueType: string; ValueName: ".webp"; ValueData: ""
Root: HKA; Subkey: "Software\Classes\.webp\OpenWithList\OpusCast.exe"; ValueType: string; ValueData: ""; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\Classes\Applications\OpusCast.exe\SupportedTypes"; ValueType: string; ValueName: ".heic"; ValueData: ""
Root: HKA; Subkey: "Software\Classes\.heic\OpenWithList\OpusCast.exe"; ValueType: string; ValueData: ""; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\Classes\Applications\OpusCast.exe\SupportedTypes"; ValueType: string; ValueName: ".heif"; ValueData: ""
Root: HKA; Subkey: "Software\Classes\.heif\OpenWithList\OpusCast.exe"; ValueType: string; ValueData: ""; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\Classes\Applications\OpusCast.exe\SupportedTypes"; ValueType: string; ValueName: ".avif"; ValueData: ""
Root: HKA; Subkey: "Software\Classes\.avif\OpenWithList\OpusCast.exe"; ValueType: string; ValueData: ""; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\Classes\Applications\OpusCast.exe\SupportedTypes"; ValueType: string; ValueName: ".tif"; ValueData: ""
Root: HKA; Subkey: "Software\Classes\.tif\OpenWithList\OpusCast.exe"; ValueType: string; ValueData: ""; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\Classes\Applications\OpusCast.exe\SupportedTypes"; ValueType: string; ValueName: ".tiff"; ValueData: ""
Root: HKA; Subkey: "Software\Classes\.tiff\OpenWithList\OpusCast.exe"; ValueType: string; ValueData: ""; Flags: uninsdeletekey

[Run]
; with admin rights the installer lets OpusCast through the firewall on home networks right away
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall add rule name=""OpusCast"" dir=in action=allow program=""{app}\OpusCast.exe"" profile=private enable=yes"; Flags: runhidden; Check: IsAdminInstallMode
Filename: "{app}\OpusCast.exe"; Description: "{cm:LaunchProgram,OpusCast}"; Flags: nowait postinstall skipifsilent

[UninstallRun]
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""OpusCast"""; Flags: runhidden; Check: IsAdminInstallMode; RunOnceId: "firewall"
