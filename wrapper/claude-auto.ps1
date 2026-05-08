# claude-auto.ps1 — Windows-PowerShell-Wrapper für Claude Code mit Auto-Failover
# Pendant zu wrapper/claude-auto (Bash, macOS).
#
# Verwendung:
#   claude-auto.ps1 [<claude-args>...]
#
# Setup:
#   1. wrapper/install.ps1 ausführen
#   2. PowerShell neu öffnen
#   3. claude-auto statt claude verwenden (oder Alias `claude` setzen, siehe install.ps1)

param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$ClaudeArgs
)

$ErrorActionPreference = 'Continue'
$SwitcherUrl = if ($env:CLAUDE_SWITCHER_URL) { $env:CLAUDE_SWITCHER_URL } else { "http://localhost:2000" }
$ClaudeBin   = if ($env:CLAUDE_BIN)          { $env:CLAUDE_BIN }          else { "claude" }

$WarnRe  = 'approach.*usage|messages.*remaining|90% of|usage.*nearly|quota.*near'
$ErrorRe = 'usage_limit_exceeded|rate_limit_exceeded|quota.*exceeded|HTTP 429|usage limit reached'

function Show-Notification {
    param([string]$Message)
    # Versuche BurntToast (rich notifications). Fallback: Console + Beep.
    try {
        if (Get-Module -ListAvailable -Name BurntToast) {
            Import-Module BurntToast -ErrorAction Stop
            New-BurntToastNotification -Text "Claude Switcher", $Message -ErrorAction Stop | Out-Null
            return
        }
    } catch {}
    Write-Host ""
    Write-Host "▸ Switcher: $Message" -ForegroundColor Yellow
    [console]::Beep(800, 200)
}

function Post-Json {
    param([string]$Path, [hashtable]$Data)
    try {
        $body = $Data | ConvertTo-Json -Compress
        Invoke-RestMethod -Uri "${SwitcherUrl}${Path}" -Method POST -ContentType 'application/json' -Body $body -TimeoutSec 5 -ErrorAction SilentlyContinue
    } catch { $null }
}

function Get-LatestSessionId {
    $projectsDir = Join-Path $env:USERPROFILE ".claude\projects"
    if (-not (Test-Path $projectsDir)) { return $null }
    $latest = Get-ChildItem -Path $projectsDir -Filter "*.jsonl" -Recurse -ErrorAction SilentlyContinue |
              Sort-Object LastWriteTime -Descending |
              Select-Object -First 1
    if ($latest) { return $latest.BaseName }
    return $null
}

$ResumeId = $null
$MarkerFile = Join-Path $env:USERPROFILE ".claude\.switcher-restart"

while ($true) {
    $LogFile     = [System.IO.Path]::GetTempFileName()
    $RestartFile = "${LogFile}.restart"

    # ─── Background-Watcher als ThreadJob (PS 6+) bzw. Job (PS 5) ───────
    $useThreadJob = $null -ne (Get-Command Start-ThreadJob -ErrorAction SilentlyContinue)
    $startJob = if ($useThreadJob) { 'Start-ThreadJob' } else { 'Start-Job' }

    # Watcher 1: stderr-Parsing aus Log-Datei
    $WatcherJob = & $startJob -ScriptBlock {
        param($LogFile, $RestartFile, $SwitcherUrl, $WarnRe, $ErrorRe, $Cwd)
        Start-Sleep -Milliseconds 200
        Get-Content -Path $LogFile -Wait -Tail 0 -ErrorAction SilentlyContinue | ForEach-Object {
            $line = $_
            if ($line -match $WarnRe) {
                try {
                    Invoke-RestMethod -Uri "$SwitcherUrl/api/warn" -Method POST -ContentType 'application/json' `
                        -Body (@{percent=90;project=$Cwd;source="wrapper-stderr"} | ConvertTo-Json -Compress) `
                        -TimeoutSec 5 -ErrorAction SilentlyContinue | Out-Null
                } catch {}
                Write-Host "▸ Switcher: Quota bei ~90 % — im Switcher entscheiden" -ForegroundColor Yellow
            }
            if ($line -match $ErrorRe) {
                try {
                    $resp = Invoke-RestMethod -Uri "$SwitcherUrl/api/quota-error" -Method POST -ContentType 'application/json' `
                        -Body (@{project=$Cwd} | ConvertTo-Json -Compress) -TimeoutSec 5
                    if ($resp.action -eq 'switch') {
                        Write-Host "▸ Switcher: Quota erreicht — switche auf Fallback und starte neu" -ForegroundColor Yellow
                        New-Item -Path $RestartFile -ItemType File -Force | Out-Null
                        Get-Process claude -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
                        return
                    } elseif ($resp.action -eq 'exhausted') {
                        Write-Host "▸ Switcher: Alle Provider voll — manuell entscheiden" -ForegroundColor Red
                    } else {
                        Write-Host "▸ Switcher: Quota erreicht — Auto-Modus aus" -ForegroundColor Yellow
                    }
                } catch {}
            }
        }
    } -ArgumentList $LogFile, $RestartFile, $SwitcherUrl, $WarnRe, $ErrorRe, $PWD.Path

    # Watcher 2: Marker-Datei (Switcher-Restart von extern, z.B. Cooldown-Promote)
    $MarkerJob = & $startJob -ScriptBlock {
        param($MarkerFile, $RestartFile)
        while ($true) {
            if (Test-Path $MarkerFile) {
                Remove-Item $MarkerFile -Force -ErrorAction SilentlyContinue
                Write-Host "▸ Switcher: fordert Restart — Kontext bleibt erhalten" -ForegroundColor Cyan
                New-Item -Path $RestartFile -ItemType File -Force | Out-Null
                Get-Process claude -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
                return
            }
            Start-Sleep -Seconds 5
        }
    } -ArgumentList $MarkerFile, $RestartFile

    # ─── Claude im Foreground; stderr+stdout in Log-Datei ────────────────
    if ($ResumeId) {
        Write-Host "↪ claude --resume $ResumeId" -ForegroundColor Cyan
        & $ClaudeBin --resume $ResumeId 2>&1 | Tee-Object -FilePath $LogFile -Append
    } else {
        & $ClaudeBin @ClaudeArgs 2>&1 | Tee-Object -FilePath $LogFile -Append
    }
    $ExitCode = $LASTEXITCODE

    # Watcher beenden
    foreach ($job in @($WatcherJob, $MarkerJob)) {
        if ($job) {
            Stop-Job -Job $job -ErrorAction SilentlyContinue
            Remove-Job -Job $job -Force -ErrorAction SilentlyContinue
        }
    }

    # Restart angefordert?
    if (Test-Path $RestartFile) {
        Remove-Item $RestartFile, $LogFile -Force -ErrorAction SilentlyContinue
        $sid = Get-LatestSessionId
        if ($sid) {
            $ResumeId = $sid
            Start-Sleep -Seconds 2
            continue
        } else {
            Write-Host "⚠ Konnte keine Session zum Resumen finden — beende." -ForegroundColor Red
            exit 1
        }
    }

    # Normales Ende
    Remove-Item $LogFile -Force -ErrorAction SilentlyContinue
    exit $ExitCode
}
