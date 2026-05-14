# Claude Code Switcher — Self-Extracting Setup (Windows / PowerShell)
# Erzeugt:  .\<TARGET>\  (Switcher-Source-Code, baut Docker-Container)
# Und:      $HOME\.claude\hooks\switcher-banner.ps1 + Hook in settings.json
# Und:      $HOME\.claude\CLAUDE.md (Switcher-Anweisungen)
#
# Aufruf:   .\setup.ps1                          # entpackt nach .\claude-switcher\
#           .\setup.ps1 -Target my-switcher      # eigener Zielordner
#           .\setup.ps1 -NoUserConfig            # nur Source

[CmdletBinding()]
param(
    [string]$Target = 'claude-switcher',
    [switch]$NoUserConfig
)

$ErrorActionPreference = 'Stop'
$ScriptPath = $MyInvocation.MyCommand.Path

if (Test-Path $Target -PathType Leaf) {
    Write-Host "✗ $Target ist eine Datei, kein Verzeichnis." -ForegroundColor Red
    exit 1
}

New-Item -Path $Target -ItemType Directory -Force | Out-Null
Push-Location $Target

function Path-To-Marker { param([string]$P) ($P -replace '[/.\-\\]', '_') }

function Extract-Block {
    param([string]$Path)
    $marker = Path-To-Marker $Path
    $unixPath = $Path -replace '\\', '/'
    $dir = Split-Path $Path -Parent
    if ($dir -and -not (Test-Path $dir)) { New-Item -Path $dir -ItemType Directory -Force | Out-Null }
    $beg = "__BEGIN_${marker}__"
    $end = "__END_${marker}__"
    $capture = $false
    $b64 = New-Object System.Text.StringBuilder
    foreach ($line in [System.IO.File]::ReadLines($ScriptPath)) {
        if ($line -eq $beg) { $capture = $true; continue }
        if ($line -eq $end) { break }
        if ($capture) { [void]$b64.Append($line) }
    }
    [System.IO.File]::WriteAllBytes($Path, [Convert]::FromBase64String($b64.ToString()))
}

Write-Host "▸ Entpacke Switcher-Source nach $(Get-Location)\" -ForegroundColor Cyan
# Manifest aus dem Bundle ziehen, dann jeden Eintrag extrahieren.
$capture = $false
$manifest = New-Object System.Collections.ArrayList
foreach ($line in [System.IO.File]::ReadLines($ScriptPath)) {
    if ($line -eq '__BEGIN_manifest__') { $capture = $true; continue }
    if ($line -eq '__END_manifest__') { break }
    if ($capture -and $line.Trim().Length -gt 0) { [void]$manifest.Add($line) }
}
foreach ($p in $manifest) {
    $winPath = $p -replace '/', '\'
    Extract-Block $winPath
}
Write-Host "  ✓ Source entpackt ($($manifest.Count) Files)" -ForegroundColor Green

if (-not $NoUserConfig) {
    $ClaudeDir = Join-Path $HOME '.claude'
    $HooksDir  = Join-Path $ClaudeDir 'hooks'
    New-Item -Path $HooksDir -ItemType Directory -Force | Out-Null

    $HookDest = Join-Path $HooksDir 'switcher-banner.ps1'
    Copy-Item 'wrapper\switcher-banner.ps1' $HookDest -Force
    Write-Host "▸ Banner-Hook installiert: $HookDest" -ForegroundColor Cyan

    $ClaudeMd = Join-Path $ClaudeDir 'CLAUDE.md'
    $markBeg  = '<!-- BEGIN claude-switcher -->'
    $markEnd  = '<!-- END claude-switcher -->'
    $tmpBlock = New-TemporaryFile
    Extract-Block $tmpBlock.FullName 'claude_md'
    $blockContent = [System.IO.File]::ReadAllText($tmpBlock.FullName).TrimEnd() + "`n"

    Write-Host "▸ Schreibe Switcher-Anweisungen in $ClaudeMd" -ForegroundColor Cyan
    if ((Test-Path $ClaudeMd) -and ((Get-Content $ClaudeMd -Raw) -match [regex]::Escape($markBeg))) {
        $existing = Get-Content $ClaudeMd -Raw
        $newBlock = "$markBeg`n$blockContent$markEnd`n"
        $pattern  = [regex]::Escape($markBeg) + '.*?' + [regex]::Escape($markEnd) + "(`n)?"
        $updated  = [regex]::Replace($existing, $pattern, [System.Text.RegularExpressions.MatchEvaluator]{ param($m) $newBlock }, 'Singleline')
        Set-Content -Path $ClaudeMd -Value $updated -NoNewline
        Write-Host "  ✓ Switcher-Block aktualisiert" -ForegroundColor Green
    } else {
        $head = ''
        if (Test-Path $ClaudeMd) { $head = (Get-Content $ClaudeMd -Raw) + "`n" }
        $full = "$head$markBeg`n$blockContent$markEnd`n"
        Set-Content -Path $ClaudeMd -Value $full -NoNewline
        Write-Host "  ✓ CLAUDE.md erstellt" -ForegroundColor Green
    }
    Remove-Item $tmpBlock -Force

    $Settings = Join-Path $ClaudeDir 'settings.json'
    Write-Host "▸ Registriere UserPromptSubmit-Hook in $Settings" -ForegroundColor Cyan
    $data = @{}
    if (Test-Path $Settings) {
        try { $data = Get-Content $Settings -Raw | ConvertFrom-Json -AsHashtable } catch { $data = @{} }
    }
    if (-not $data.ContainsKey('hooks')) { $data['hooks'] = @{} }
    if (-not $data['hooks'].ContainsKey('UserPromptSubmit')) { $data['hooks']['UserPromptSubmit'] = @() }

    $already = $false
    foreach ($entry in $data['hooks']['UserPromptSubmit']) {
        foreach ($h in $entry.hooks) {
            if ($h.command -and $h.command -like '*switcher-banner.ps1') { $already = $true; break }
        }
    }
    if (-not $already) {
        $data['hooks']['UserPromptSubmit'] += @{
            matcher = '.*'
            hooks = @(@{
                type    = 'command'
                command = "powershell.exe -NoProfile -ExecutionPolicy Bypass -File `"$HookDest`""
                async   = $false
            })
        }
        ($data | ConvertTo-Json -Depth 10) | Set-Content -Path $Settings -NoNewline
        Write-Host "  ✓ Hook registriert" -ForegroundColor Green
    } else {
        Write-Host "  ✓ Hook war schon registriert" -ForegroundColor Green
    }
}

# Docker
$dockerOk = (Get-Command docker -ErrorAction SilentlyContinue) -ne $null
if ($dockerOk) {
    Write-Host "▸ Baue + starte Docker-Container" -ForegroundColor Cyan
    & docker compose up -d --build 2>&1 | Select-Object -Last 5
} else {
    Write-Host "  ⚠ docker nicht installiert (Docker Desktop für Windows benötigt)" -ForegroundColor Yellow
}

# Wrapper-Alias automatisch installieren (PowerShell-Profil)
# Damit ist NUR setup.ps1 nötig — kein zweiter Schritt mehr für den User.
Write-Host ""
Write-Host "▸ Installiere claude-Wrapper-Funktion ins PowerShell-Profil" -ForegroundColor Cyan
& "$(Get-Location)\wrapper\install.ps1"

Pop-Location
Write-Host ""
Write-Host "✓ Komplett fertig. Eine letzte Aktion:" -ForegroundColor Green
Write-Host "  → PowerShell neu öffnen  (oder:  . `$PROFILE )"
Write-Host ""
Write-Host "Dann:"
Write-Host "  claude                              # läuft jetzt durch den Wrapper"
Write-Host "  http://localhost:2000               # UI zum Provider/Modell wählen"
Write-Host "  $Target\wrapper\router-watch.ps1   # live anschauen welches Modell antwortet"
exit 0
