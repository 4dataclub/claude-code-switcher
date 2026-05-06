# install.ps1 — Installiert claude-auto-Wrapper für PowerShell (Windows)
# Pendant zu wrapper/install.sh (macOS/Linux)
#
# Was passiert:
#   1. Kopiert claude-auto.ps1 nach $env:LOCALAPPDATA\claude-switcher\
#   2. Setzt eine Funktion `claude` im PowerShell-Profil → ruft den Wrapper auf
#      (Damit du wie gewohnt nur `claude` tippen musst)
#   3. Empfiehlt Install des BurntToast-Moduls für rich Notifications

$ErrorActionPreference = 'Stop'

$SrcDir   = $PSScriptRoot
$Src      = Join-Path $SrcDir "claude-auto.ps1"
$DestDir  = Join-Path $env:LOCALAPPDATA "claude-switcher"
$Dest     = Join-Path $DestDir "claude-auto.ps1"

if (-not (Test-Path $Src)) {
    Write-Host "✗ Source nicht gefunden: $Src" -ForegroundColor Red
    exit 1
}

# 1. Wrapper kopieren
New-Item -Path $DestDir -ItemType Directory -Force | Out-Null
Copy-Item -Path $Src -Destination $Dest -Force
Write-Host "✓ claude-auto.ps1 installiert nach $Dest" -ForegroundColor Green

# 2. PowerShell-Profil-Funktion einrichten
$ProfileFile = $PROFILE.CurrentUserAllHosts
$ProfileDir = Split-Path $ProfileFile -Parent
if (-not (Test-Path $ProfileDir)) {
    New-Item -Path $ProfileDir -ItemType Directory -Force | Out-Null
}
if (-not (Test-Path $ProfileFile)) {
    New-Item -Path $ProfileFile -ItemType File -Force | Out-Null
}

$marker = "# === claude-switcher ==="
$existing = Get-Content $ProfileFile -ErrorAction SilentlyContinue
if ($existing -and ($existing -join "`n").Contains($marker)) {
    Write-Host "ℹ claude-Funktion schon im Profil eingetragen ($ProfileFile)" -ForegroundColor Cyan
} else {
    $block = @"

$marker
function claude {
    & "$Dest" @args
}
function claude-real {
    & "$env:LOCALAPPDATA\Programs\claude\claude.exe" @args  # echter claude, falls direkt gewünscht
}
# === /claude-switcher ===
"@
    Add-Content -Path $ProfileFile -Value $block
    Write-Host "✓ claude-Funktion in $ProfileFile eingetragen" -ForegroundColor Green
    Write-Host "  → ab jetzt ruft 'claude' den Wrapper auf, 'claude-real' das original Binary" -ForegroundColor Cyan
}

# 3. BurntToast-Modul für Notifications (optional)
if (-not (Get-Module -ListAvailable -Name BurntToast)) {
    Write-Host ""
    Write-Host "ℹ Optional: für hübsche Windows-Notifications:" -ForegroundColor Cyan
    Write-Host "    Install-Module -Name BurntToast -Scope CurrentUser -Force" -ForegroundColor White
    Write-Host "  (Ohne BurntToast wird einfach in der Konsole + Beep informiert)" -ForegroundColor DarkGray
}

# 4. PATH-Check für 'claude.exe' (echter Binary)
$realClaude = "$env:LOCALAPPDATA\Programs\claude\claude.exe"
if (-not (Test-Path $realClaude)) {
    Write-Host ""
    Write-Host "⚠ Echter claude.exe nicht unter $realClaude gefunden." -ForegroundColor Yellow
    Write-Host "  Wrapper funktioniert trotzdem solange 'claude' im PATH ist." -ForegroundColor Yellow
    Write-Host "  Falls Pfad anders: passe ihn in $ProfileFile an oder setze CLAUDE_BIN env var." -ForegroundColor Yellow
}

Write-Host ""
Write-Host "Setup fertig. Bitte PowerShell neu öffnen (oder: . `$PROFILE)." -ForegroundColor Green
Write-Host ""
Write-Host "Test:" -ForegroundColor Cyan
Write-Host "  curl http://localhost:3000/api/status   # → Switcher muss laufen" -ForegroundColor White
Write-Host "  claude                                  # → ruft jetzt claude-auto.ps1" -ForegroundColor White
