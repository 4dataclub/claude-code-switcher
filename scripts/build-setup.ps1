# build-setup.ps1 — Regeneriert setup.sh + setup.ps1 aus aktuellem Source-Stand.
# PowerShell-Pendant zu build-setup.sh — identisches Verhalten.
#
# Aufruf:  pwsh scripts/build-setup.ps1   (oder powershell.exe unter Windows)
# Output:  setup.sh + setup.ps1 im Repo-Root, ersetzt vorhandene.

$ErrorActionPreference = 'Stop'

$Root        = Resolve-Path (Join-Path $PSScriptRoot '..')
$HeaderSh    = Join-Path $PSScriptRoot 'setup-header.sh.tpl'
$HeaderPs1   = Join-Path $PSScriptRoot 'setup-header.ps1.tpl'
$ClaudeTpl   = Join-Path $PSScriptRoot 'templates/CLAUDE.md.tpl'

foreach ($f in @($HeaderSh, $HeaderPs1, $ClaudeTpl)) {
    if (-not (Test-Path $f)) { Write-Host "✗ Fehlt: $f" -ForegroundColor Red; exit 1 }
}

# (relativer Pfad ab Root, Marker-Name) — identisch zu build-setup.sh
$Manifest = @(
    @{ Path = 'Dockerfile';                                Marker = 'Dockerfile' }
    @{ Path = 'package.json';                              Marker = 'package_json' }
    @{ Path = 'server.js';                                 Marker = 'server_js' }
    @{ Path = 'docker-compose.yml';                        Marker = 'docker_compose_yml' }
    @{ Path = 'public/index.html';                         Marker = 'public_index_html' }
    @{ Path = 'router/Dockerfile';                         Marker = 'router_Dockerfile' }
    @{ Path = 'router/config.json';                        Marker = 'router_config_json' }
    @{ Path = 'wrapper/claude-auto';                       Marker = 'wrapper_claude_auto' }
    @{ Path = 'wrapper/claude-auto.ps1';                   Marker = 'wrapper_claude_auto_ps1' }
    @{ Path = 'wrapper/install.sh';                        Marker = 'wrapper_install_sh' }
    @{ Path = 'wrapper/install.ps1';                       Marker = 'wrapper_install_ps1' }
    @{ Path = 'wrapper/router-watch.sh';                   Marker = 'wrapper_router_watch_sh' }
    @{ Path = 'wrapper/router-watch.ps1';                  Marker = 'wrapper_router_watch_ps1' }
    @{ Path = 'wrapper/switcher-banner.sh';                Marker = 'wrapper_switcher_banner_sh' }
    @{ Path = 'wrapper/switcher-banner.ps1';               Marker = 'wrapper_switcher_banner_ps1' }
    @{ Path = 'docs/screenshots/01-overview.png';          Marker = 'docs_screenshots_01_overview' }
    @{ Path = 'docs/screenshots/02-auto-mode.png';         Marker = 'docs_screenshots_02_auto_mode' }
    @{ Path = 'docs/screenshots/03-provider-anthropic.png';   Marker = 'docs_screenshots_03_provider_anthropic' }
    @{ Path = 'docs/screenshots/04-provider-google.png';      Marker = 'docs_screenshots_04_provider_google' }
    @{ Path = 'docs/screenshots/05-provider-openrouter.png';  Marker = 'docs_screenshots_05_provider_openrouter' }
    @{ Path = 'docs/screenshots/06-90-percent-banner.png';    Marker = 'docs_screenshots_06_90_percent_banner' }
    @{ Path = 'docs/screenshots/07-status-gemini-active.png'; Marker = 'docs_screenshots_07_status_gemini_active' }
    @{ Path = 'docs/screenshots/08-banner-and-keys.png';      Marker = 'docs_screenshots_08_banner_and_keys' }
)

function Encode-File {
    param([string]$Path)
    $bytes = [System.IO.File]::ReadAllBytes($Path)
    return [Convert]::ToBase64String($bytes)
}

function Build-One {
    param([string]$OutFile, [string]$HeaderFile)
    Write-Host "▸ Baue $OutFile aus $HeaderFile + $($Manifest.Count) Dateien + CLAUDE.md.tpl"

    $sb = [System.Text.StringBuilder]::new()
    [void]$sb.Append([System.IO.File]::ReadAllText($HeaderFile))

    # claude_md-Block
    [void]$sb.AppendLine()
    [void]$sb.AppendLine('__BEGIN_claude_md__')
    [void]$sb.AppendLine((Encode-File $ClaudeTpl))
    [void]$sb.AppendLine('__END_claude_md__')

    foreach ($entry in $Manifest) {
        $full = Join-Path $Root $entry.Path
        if (-not (Test-Path $full)) {
            Write-Host "  ⚠ Fehlt: $full — überspringe Marker $($entry.Marker)" -ForegroundColor Yellow
            continue
        }
        [void]$sb.AppendLine()
        [void]$sb.AppendLine("__BEGIN_$($entry.Marker)__")
        [void]$sb.AppendLine((Encode-File $full))
        [void]$sb.AppendLine("__END_$($entry.Marker)__")
    }

    # LF-Line-Endings sicherstellen (für Bash-Kompatibilität)
    $content = $sb.ToString() -replace "`r`n", "`n"
    [System.IO.File]::WriteAllText($OutFile, $content, (New-Object System.Text.UTF8Encoding $false))

    $size = [Math]::Round((Get-Item $OutFile).Length / 1024, 1)
    $lines = (Get-Content $OutFile).Count
    Write-Host "  ✓ $OutFile ($lines Zeilen, $size KB)"
}

Build-One -OutFile (Join-Path $Root 'setup.sh')  -HeaderFile $HeaderSh
Build-One -OutFile (Join-Path $Root 'setup.ps1') -HeaderFile $HeaderPs1

# setup.sh ausführbar machen (POSIX-Permissions, no-op auf Windows)
if ($IsLinux -or $IsMacOS) {
    chmod +x (Join-Path $Root 'setup.sh')
}

Write-Host ''
Write-Host '✓ Fertig. Diff zum letzten Commit:'
Push-Location $Root
git diff --stat setup.sh setup.ps1 2>$null
Pop-Location
