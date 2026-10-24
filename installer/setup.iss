; BotStory client installer, for people who run their OWN BotStory server.
;
; It adds BotStory's kaentake launcher to a MapleStory v83 client the player already has, with their own Cosmic
; or Community Repack WZ files, and points it at their server (127.0.0.1:8484 unless they change it). It
; downloads nothing, and nothing here connects to anyone else's server: no Tailscale, no patch server.
;
; Build: installer\build.ps1 -Kaentake "<BotStory-Kaentake checkout>" (see README.md).
; Silent: BotStory-Setup-x.y.z.exe /VERYSILENT /DIR="C:\Nexon\MapleStory" [/HOST=127.0.0.1] [/PORT=8484]
;         [/SERVERDIR="<server folder>"] [/CURRENTUSER]. /DIR must hold MapleStory v83 already.

#define AppVersion "1.0.0"
#ifndef Payload
  #define Payload "payload"
#endif

[Setup]
AppId={{5B8F2F0B-6E0A-4C1E-9C1D-2A8F7D3C9B10}
AppName=BotStory Client
AppVersion={#AppVersion}
AppPublisher=BotStory
AppPublisherURL=https://github.com/Incluwin/BotStory
DefaultDirName={sd}\Nexon\MapleStory
DisableDirPage=no
DirExistsWarning=no
AppendDefaultDirName=no
DisableProgramGroupPage=yes
; Inno 6 skips the Welcome page by default; it says what this installer needs and that it only connects to your server.
DisableWelcomePage=no
PrivilegesRequired=admin
; /CURRENTUSER installs without admin rights (shortcuts and uninstall entry for this user only).
PrivilegesRequiredOverridesAllowed=commandline
WizardStyle=modern dynamic
OutputDir=dist
OutputBaseFilename=BotStory-Setup-{#AppVersion}
Compression=lzma2
SolidCompression=yes
UninstallFilesDir={app}\botstory-uninstall
UninstallDisplayName=BotStory Client (the launcher; the game files stay)
SetupLogging=yes

[Messages]
WelcomeLabel2=This adds BotStory's launcher to your MapleStory v83 client, for your own BotStory server.%n%nBring your own client: MapleStory v83 installed, with a Cosmic or Community Repack WZ set in its folder. The installer downloads nothing, and the client connects only to the server you name.
SelectDirLabel3=Pick the folder MapleStory v83 is installed in: the one with your Cosmic or Community Repack WZ files.

[Tasks]
Name: desktopicon; Description: "Create a desktop shortcut"

[Files]
Source: "{#Payload}\Launch Game.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#Payload}\Kaentake.dll"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#Payload}\Custom.wz"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{autoprograms}\BotStory"; Filename: "{app}\Launch Game.exe"; WorkingDir: "{app}"
Name: "{autodesktop}\BotStory"; Filename: "{app}\Launch Game.exe"; WorkingDir: "{app}"; Tasks: desktopicon

[Run]
Filename: "{cmd}"; Parameters: "{code:ResidentsParams}"; WorkingDir: "{code:GetServerDir}"; \
  Description: "Make the Residents' portraits and quest log now (needs Java and Python; your server must have started once)"; \
  Flags: postinstall skipifsilent unchecked; Check: HasServerDir
Filename: "{app}\Launch Game.exe"; WorkingDir: "{app}"; Description: "Start BotStory"; \
  Flags: postinstall nowait skipifsilent shellexec

[Code]
const
  // The stock GMS v83 MapleStory.exe, and the same file with its large-address-aware flag set (the flag byte
  // at offset 318, 0x0F -> 0x2F, and the PE checksum byte at 384, 0xDC -> 0xFC). As botstory_wz.py exe.
  StockExeSize = 4286088;
  StockExeSha256 = 'ed5a699407b9705528b6a653cdee7395e6e1b7317b9711dcfb34681092072848';
  LaaExeSha256 = '33bb280921cd93dad593080e9e44a2cfe1af507d71f76f4b64c6d80613d52e77';
  // The WZ files that tell the sets apart (the others are the same in all of them).
  WzCount = 12;
  NoClientMsg = 'MapleStory v83 is not in this folder. Install it and put your Cosmic or Community Repack WZ files ' +
    'in its folder (see "The game client" in docs\SETUP.md, in the server folder), then pick that folder.';

var
  GameFilesPage: TOutputMsgWizardPage;
  ServerPage: TInputQueryWizardPage;
  ResidentsPage: TInputOptionWizardPage;
  ServerDirPage: TInputDirWizardPage;
  DetectedBase: String;        // cosmic, repack, stock, other
  Matching: Integer;           // how many of the 12 WZ files match the detected set's sizes
  ExeNote: String;
  ServerPrefilledFor: String;  // the folder whose config.ini last filled the server page


function WzName(I: Integer): String;
begin
  case I of
    0: Result := 'Character.wz';
    1: Result := 'Etc.wz';
    2: Result := 'Item.wz';
    3: Result := 'List.wz';
    4: Result := 'Map.wz';
    5: Result := 'Mob.wz';
    6: Result := 'Npc.wz';
    7: Result := 'Quest.wz';
    8: Result := 'Reactor.wz';
    9: Result := 'Skill.wz';
    10: Result := 'String.wz';
    11: Result := 'UI.wz';
  end;
end;

// Sizes of the 12 files in P0nk/Cosmic-client's cosmic-wz (commit 6b7328b), to recognise a Cosmic client.
function CosmicWzSize(Name: String): Int64;
begin
  Result := -1;
  if Name = 'Character.wz' then Result := 206267331
  else if Name = 'Etc.wz' then Result := 1803008
  else if Name = 'Item.wz' then Result := 18397440
  else if Name = 'List.wz' then Result := 13336
  else if Name = 'Map.wz' then Result := 638428788
  else if Name = 'Mob.wz' then Result := 479990067
  else if Name = 'Npc.wz' then Result := 53498512
  else if Name = 'Quest.wz' then Result := 5993452
  else if Name = 'Reactor.wz' then Result := 54348491
  else if Name = 'Skill.wz' then Result := 76505373
  else if Name = 'String.wz' then Result := 3561285
  else if Name = 'UI.wz' then Result := 28315281;
end;

// Community Repack sizes of the same files, to recognise a Repack client.
function RepackWzSize(Name: String): Int64;
begin
  Result := -1;
  if Name = 'Character.wz' then Result := 2014706243
  else if Name = 'Etc.wz' then Result := 1803008
  else if Name = 'Item.wz' then Result := 474161845
  else if Name = 'List.wz' then Result := 13336
  else if Name = 'Map.wz' then Result := 1442051954
  else if Name = 'Mob.wz' then Result := 1432923627
  else if Name = 'Npc.wz' then Result := 580489730
  else if Name = 'Quest.wz' then Result := 5993452
  else if Name = 'Reactor.wz' then Result := 58356511
  else if Name = 'Skill.wz' then Result := 122790093
  else if Name = 'String.wz' then Result := 8155512
  else if Name = 'UI.wz' then Result := 30972026;
end;

// Stock GMS v83 sizes: what the v83 setup installs, which the server cannot use.
function StockWzSize(Name: String): Int64;
begin
  Result := -1;
  if Name = 'Character.wz' then Result := 186792151
  else if Name = 'Map.wz' then Result := 635444895
  else if Name = 'Npc.wz' then Result := 51137866
  else if Name = 'String.wz' then Result := 3251564;
end;

function FileSizeOf(Path: String): Int64;
begin
  if not FileSize64(Path, Result) then
    Result := -1;
end;

function ClientDir: String;
begin
  Result := AddBackslash(WizardDirValue);
end;

function HasClient: Boolean;
begin
  Result := FileExists(ClientDir + 'MapleStory.exe');
end;

function DetectBase: String;
var
  I, Cosmic, Repack, Stock: Integer;
  Have: Int64;
begin
  Cosmic := 0;
  Repack := 0;
  Stock := 0;
  for I := 0 to WzCount - 1 do begin
    Have := FileSizeOf(ClientDir + WzName(I));
    if Have = CosmicWzSize(WzName(I)) then Cosmic := Cosmic + 1;
    if Have = RepackWzSize(WzName(I)) then Repack := Repack + 1;
    if Have = StockWzSize(WzName(I)) then Stock := Stock + 1;
  end;
  // 7 of 12: the Residents step rewrites Npc, String, Quest and Etc, leaving 8 matching files.
  // Etc.wz and List.wz are the same files (same bytes) in both sets, so they count for both.
  Matching := 0;
  if (Cosmic >= 7) and (Cosmic > Repack) then begin Result := 'cosmic'; Matching := Cosmic; end
  else if (Repack >= 7) and (Repack > Cosmic) then begin Result := 'repack'; Matching := Repack; end
  else if Stock >= 3 then Result := 'stock'
  else Result := 'other';
end;

function ChangedNote: String;
begin
  Result := '';
  if Matching < WzCount then
    Result := ' ' + IntToStr(WzCount - Matching) + ' of the 12 differ from that set, for example after the ' +
      'Residents step.';
end;

function GameFilesNote: String;
begin
  DetectedBase := DetectBase;
  if DetectedBase = 'cosmic' then
    Result := 'This folder has the Cosmic WZ files.' + ChangedNote
  else if DetectedBase = 'repack' then
    Result := 'This folder has the Community Repack WZ files. BotStory runs on them too.' + ChangedNote
  else if DetectedBase = 'stock' then
    Result := 'This folder has the plain v83 WZ files, which BotStory cannot use: the server shows NPCs, maps ' +
      'and items that only the Cosmic or Community Repack files have, and the game closes on them.' + #13#10#13#10 +
      'Put one of those sets in this folder (see "The game client" in docs\SETUP.md, in the server folder). You ' +
      'can install the launcher now and swap the WZ files before you play.'
  else
    Result := 'The WZ files here are neither the Cosmic nor the Community Repack set (patched, or another ' +
      'version). BotStory needs one of those two (see "The game client" in docs\SETUP.md, in the server ' +
      'folder). You can install the launcher now and swap the WZ files before you play.';
  Result := Result + #13#10#13#10 + 'The installer does not change the WZ files.';
end;

procedure InitializeWizard;
begin
  GameFilesPage := CreateOutputMsgPage(wpSelectDir, 'Game files', 'Which WZ files does this folder have?', '');

  ServerPage := CreateInputQueryPage(GameFilesPage.ID, 'Your server', 'Where does your BotStory server run?',
    'The client connects only to this address. Keep 127.0.0.1 when the server runs on this PC.');
  ServerPage.Add('Server address:', False);
  ServerPage.Add('Login port:', False);
  ServerPage.Values[0] := ExpandConstant('{param:HOST|127.0.0.1}');
  ServerPage.Values[1] := ExpandConstant('{param:PORT|8484}');

  ResidentsPage := CreateInputOptionPage(ServerPage.ID, 'Residents (optional)',
    'Make the Residents'' portraits and quest log at the end?',
    'BotStory''s Residents need client files made from your own server: their portraits and the quest log. ' +
    'The installer can offer that at the end: it runs tools\living-world-client.bat from your server folder, ' +
    'which needs Java and Python, and your server must have started once. You can also do it later (see ' +
    'docs\SETUP.md in the server folder).', False, False);
  ResidentsPage.Add('Yes, offer it at the end. I will pick my BotStory server folder next.');
  ResidentsPage.Values[0] := ExpandConstant('{param:SERVERDIR|}') <> '';

  ServerDirPage := CreateInputDirPage(ResidentsPage.ID, 'Your server folder', 'Where is your BotStory server?',
    'Pick the folder that has setup.bat and tools\living-world-client.bat.', False, '');
  ServerDirPage.Add('BotStory server folder:');
  ServerDirPage.Values[0] := ExpandConstant('{param:SERVERDIR|}');
end;

// A reinstall keeps the server address in the folder's config.ini, unless /HOST= or /PORT= say otherwise.
procedure PrefillServer;
var
  Ini: String;
begin
  if ServerPrefilledFor = ClientDir then
    exit;
  ServerPrefilledFor := ClientDir;
  Ini := ClientDir + 'config.ini';
  if not FileExists(Ini) then
    exit;
  if ExpandConstant('{param:HOST|}') = '' then
    ServerPage.Values[0] := GetIniString('config', 'host', ServerPage.Values[0], Ini);
  if ExpandConstant('{param:PORT|}') = '' then
    ServerPage.Values[1] := GetIniString('config', 'port', ServerPage.Values[1], Ini);
end;

procedure CurPageChanged(CurPageID: Integer);
begin
  if CurPageID = GameFilesPage.ID then begin
    GameFilesPage.MsgLabel.Caption := GameFilesNote;
    GameFilesPage.MsgLabel.AdjustHeight;
  end else if CurPageID = ServerPage.ID then
    PrefillServer
  else if CurPageID = wpFinished then begin
    WizardForm.FinishedLabel.Caption := TrimRight(WizardForm.FinishedLabel.Caption) + #13#10#13#10 +
      'Your server needs MOD_FUSION_ANVIL=on in its .env (setup.bat sets it; with Docker, set it yourself): ' +
      'this client always reads the Fusion Anvil field. Keep MOD_BOTSTORY_WZ off.' + #13#10#13#10 +
      'MapleStory.exe: ' + ExeNote;
    // The run checkboxes were placed below the original text: move them below the longer one.
    WizardForm.FinishedLabel.AdjustHeight;
    WizardForm.RunList.Top := WizardForm.FinishedLabel.Top + WizardForm.FinishedLabel.Height + ScaleY(12);
  end;
end;

function ValidPort(S: String): Boolean;
var
  N: Integer;
begin
  N := StrToIntDef(Trim(S), 0);
  Result := (N >= 1) and (N <= 65535);
end;

function GetServerDir(Param: String): String;
begin
  if ResidentsPage.Values[0] then
    Result := Trim(ServerDirPage.Values[0])
  else
    Result := '';
end;

// The server folder page only after a yes on the Residents page (an empty folder field never validates).
function ShouldSkipPage(PageID: Integer): Boolean;
begin
  Result := (PageID = ServerDirPage.ID) and not ResidentsPage.Values[0];
end;

function HasServerDir: Boolean;
begin
  Result := (GetServerDir('') <> '') and FileExists(AddBackslash(GetServerDir('')) + 'tools\living-world-client.bat');
end;

function ResidentsParams(Param: String): String;
begin
  Result := '/k ""' + AddBackslash(GetServerDir('')) + 'tools\living-world-client.bat" "' + ExpandConstant('{app}') + '""';
end;

function NextButtonClick(CurPageID: Integer): Boolean;
begin
  Result := True;
  // Silent installs check this in PrepareToInstall instead, which ends Setup with exit code 7 and the reason.
  if (CurPageID = wpSelectDir) and not HasClient and not WizardSilent then begin
    SuppressibleMsgBox(NoClientMsg, mbError, MB_OK, IDOK);
    Result := False;
  end else if CurPageID = ServerPage.ID then begin
    if Trim(ServerPage.Values[0]) = '' then begin
      MsgBox('Enter the server address (127.0.0.1 when the server runs on this PC).', mbError, MB_OK);
      Result := False;
    end else if not ValidPort(ServerPage.Values[1]) then begin
      MsgBox('The login port is a number from 1 to 65535 (8484 unless you changed it).', mbError, MB_OK);
      Result := False;
    end;
  end else if CurPageID = ServerDirPage.ID then begin
    if not HasServerDir then begin
      SuppressibleMsgBox('That folder has no tools\living-world-client.bat. Pick your BotStory server folder, ' +
        'or go back and untick the Residents step.', mbError, MB_OK, IDOK);
      Result := False;
    end;
  end;
end;

// A silent install checks the folder here, so a folder without the client ends Setup with exit code 7 and the
// reason in the log.
function PrepareToInstall(var NeedsRestart: Boolean): String;
begin
  Result := '';
  if not WizardSilent then
    exit;
  if not HasClient then begin
    Result := NoClientMsg;
    exit;
  end;
  PrefillServer;
  Log('Game files: ' + GameFilesNote);
end;

function UpdateReadyMemo(Space, NewLine, MemoUserInfoInfo, MemoDirInfo, MemoTypeInfo, MemoComponentsInfo,
  MemoGroupInfo, MemoTasksInfo: String): String;
begin
  Result := MemoDirInfo + NewLine + NewLine + 'Game files (not changed):' + NewLine + Space;
  if DetectedBase = 'cosmic' then
    Result := Result + 'the Cosmic WZ files'
  else if DetectedBase = 'repack' then
    Result := Result + 'the Community Repack WZ files'
  else if DetectedBase = 'stock' then
    Result := Result + 'the plain v83 WZ files: swap in a Cosmic or Community Repack set before you play'
  else
    Result := Result + 'neither the Cosmic nor the Community Repack set: swap one in before you play';
  Result := Result + NewLine + NewLine + 'Server:' + NewLine + Space + Trim(ServerPage.Values[0]) + ':' +
    Trim(ServerPage.Values[1]) + NewLine;
  if HasServerDir then
    Result := Result + NewLine + 'Residents:' + NewLine + Space + 'offer tools\living-world-client.bat at the end, from' +
      NewLine + Space + GetServerDir('') + NewLine;
  if MemoTasksInfo <> '' then
    Result := Result + NewLine + MemoTasksInfo;
end;

// The files P0nk's guide removes from a v83 install: Nexon's anti-cheat, launcher and patcher.
procedure RemoveNexonExtras;
begin
  DelTree(ClientDir + 'HShield', True, True, True);
  DeleteFile(ClientDir + 'ASPLnchr.exe');
  DeleteFile(ClientDir + 'Patcher.exe');
end;

procedure SetLargeAddressAware;
var
  Exe, Quoted, Backup, Script: String;
  Rc: Integer;
begin
  Exe := ClientDir + 'MapleStory.exe';
  if GetSHA256OfFile(Exe) = LaaExeSha256 then begin
    ExeNote := 'the large-address-aware flag was already set.';
    exit;
  end;
  if (FileSizeOf(Exe) <> StockExeSize) or (GetSHA256OfFile(Exe) <> StockExeSha256) then begin
    ExeNote := 'not the stock v83 file, so the large-address-aware flag was not set (a long session may run ' +
      'the client out of memory).';
    exit;
  end;
  Backup := ClientDir + 'botstory-backup';
  ForceDirectories(Backup);
  if not CopyFile(Exe, Backup + '\MapleStory.exe', False) then
    RaiseException('Could not back up ' + Exe);
  // Two bytes; PowerShell so they are written as bytes, whatever the system code page.
  Quoted := Exe;
  StringChangeEx(Quoted, '''', '''''', True);
  Script := '$p = ''' + Quoted + '''; $b = [IO.File]::ReadAllBytes($p); ' +
    'if ($b[318] -eq 15 -and $b[384] -eq 220) { $b[318] = 47; $b[384] = 252; [IO.File]::WriteAllBytes($p, $b) }';
  Exec('powershell.exe', '-NoProfile -ExecutionPolicy Bypass -Command "' + Script + '"', '', SW_HIDE,
    ewWaitUntilTerminated, Rc);
  if GetSHA256OfFile(Exe) = LaaExeSha256 then
    ExeNote := 'large-address-aware flag set (the original is in botstory-backup).'
  else begin
    CopyFile(Backup + '\MapleStory.exe', Exe, False);
    ExeNote := 'setting the large-address-aware flag failed, so the original was put back.';
  end;
end;

procedure WriteConfig;
var
  Ini: String;
begin
  Ini := ClientDir + 'config.ini';
  SetIniString('config', 'host', Trim(ServerPage.Values[0]), Ini);
  SetIniString('config', 'port', Trim(ServerPage.Values[1]), Ini);
  // No patching: a self-hosted client never syncs files from another server.
  DeleteIniEntry('config', 'patch_url', Ini);
  DeleteIniEntry('config', 'base', Ini);
end;

procedure CurStepChanged(CurStep: TSetupStep);
begin
  if CurStep = ssPostInstall then begin
    RemoveNexonExtras;
    SetLargeAddressAware;
    WriteConfig;
    Log('MapleStory.exe: ' + ExeNote);
  end;
end;
