# router-watch.ps1 — Windows-Pendant zu router-watch.sh
# Live-Anzeige aller Anfragen die durch den Router gehen.
# Beendet: Ctrl+C

$ErrorActionPreference = 'Continue'

$SwitcherUrl     = if ($env:CLAUDE_SWITCHER_URL) { $env:CLAUDE_SWITCHER_URL } else { 'http://localhost:2000' }
$RouterContainer = if ($env:ROUTER_CONTAINER)   { $env:ROUTER_CONTAINER }   else { 'claude-switcher-router-1' }

Write-Host "╔════════════════════════════════════════════════════════════════╗" -ForegroundColor White
Write-Host "║  Router Watch — live anzeigen welches Modell wirklich antwortet ║" -ForegroundColor White
Write-Host "╚════════════════════════════════════════════════════════════════╝" -ForegroundColor White

# Aktueller Status
try {
    $whoami = Invoke-RestMethod -Uri "$SwitcherUrl/api/whoami" -TimeoutSec 3 -ErrorAction Stop
    Write-Host "Aktives Backend: " -NoNewline -ForegroundColor DarkGray
    Write-Host $whoami -ForegroundColor White
} catch {
    Write-Host "(Switcher unter $SwitcherUrl nicht erreichbar)" -ForegroundColor Yellow
}
Write-Host ""

# ─── SSE-Stream für Switch-Events (PowerShell-Variante via .NET HttpClient) ─
$sseJob = Start-Job -ScriptBlock {
    param($url)
    Add-Type -AssemblyName System.Net.Http
    $client = [System.Net.Http.HttpClient]::new()
    $client.Timeout = [System.TimeSpan]::FromHours(24)
    try {
        $stream  = $client.GetStreamAsync("$url/api/events").Result
        $reader  = [System.IO.StreamReader]::new($stream)
        $event   = ''
        while (-not $reader.EndOfStream) {
            $line = $reader.ReadLine()
            if ($line -match '^event:\s*(.+)$') { $event = $matches[1].Trim() }
            elseif ($line -match '^data:\s*(.+)$' -and $event) {
                $data = $matches[1]
                Write-Output "EVENT|$event|$data"
                $event = ''
            }
        }
    } catch {
        Write-Output "EVENT|error|$($_.Exception.Message)"
    }
} -ArgumentList $SwitcherUrl

# ─── Live-Log vom ccr-Container ────────────────────────────────────────────
$logJob = Start-Job -ScriptBlock {
    param($container)
    $cmd = "while true; do LATEST=`$(ls -t /root/.claude-code-router/logs/*.log 2>/dev/null | head -1); [ -n `"`$LATEST`" ] && tail -F `"`$LATEST`" 2>/dev/null; sleep 2; done"
    & docker exec $container sh -c $cmd 2>$null
} -ArgumentList $RouterContainer

Write-Host "── Watching… (Ctrl+C beendet)" -ForegroundColor DarkGray
Write-Host ""

# Pending-Map für Anfrage→Antwort-Paare
$pending = @{}

try {
    while ($true) {
        # SSE-Events drainen
        Receive-Job -Job $sseJob -ErrorAction SilentlyContinue | ForEach-Object {
            if ($_ -match '^EVENT\|(.+?)\|(.+)$') {
                $ev   = $matches[1]
                $data = $matches[2]
                try { $j = $data | ConvertFrom-Json } catch { $j = $null }
                switch ($ev) {
                    'switch' {
                        if ($j) {
                            $p   = $j.provider
                            $m   = $j.model
                            $ar  = $j.activeRoute
                            $rp  = if ($ar) { $ar.provider } else { $p }
                            $rm  = if ($ar) { $ar.model }    else { $m }
                            if ($p -eq 'anthropic') {
                                Write-Host "`n▼▼▼ SWITCH ▼▼▼  → " -NoNewline -ForegroundColor Yellow
                                Write-Host "ANTHROPIC direkt" -NoNewline -ForegroundColor Yellow
                                Write-Host " / $m  (kein Router)" -ForegroundColor Cyan
                            } else {
                                Write-Host "`n▼▼▼ SWITCH ▼▼▼  → echtes Backend: " -NoNewline -ForegroundColor Yellow
                                Write-Host $rp -NoNewline -ForegroundColor Magenta
                                Write-Host " / " -NoNewline
                                Write-Host $rm -ForegroundColor Cyan
                            }
                        }
                    }
                    'auto-switched' {
                        if ($j -and $j.to) {
                            Write-Host "`n▼▼▼ AUTO-SWITCH ▼▼▼  → " -NoNewline -ForegroundColor Yellow
                            Write-Host "$($j.to.provider) / $($j.to.model)" -ForegroundColor Cyan
                        }
                    }
                    'warn'           { Write-Host "⚠ 90%-Warnung" -ForegroundColor Yellow }
                    'chain-promoted' { Write-Host "↺ Zurück auf Anthropic" -ForegroundColor Green }
                }
            }
        }

        # Log-Lines drainen + parsen
        Receive-Job -Job $logJob -ErrorAction SilentlyContinue | ForEach-Object {
            try { $d = $_ | ConvertFrom-Json } catch { return }
            if (-not $d) { return }
            $msg = $d.msg
            $rid = $d.reqId

            if ($msg -eq 'incoming request' -and $d.req -and $d.req.method -eq 'POST' -and $d.req.url -notmatch '/health') {
                $pending[$rid] = @{ started = (Get-Date).ToString('HH:mm:ss') }
            }
            elseif ($d.type -eq 'request body' -and $pending.ContainsKey($rid)) {
                $body = $d.data
                if ($body) {
                    $pending[$rid].claude_model = $body.model
                    if ($body.messages -and $body.messages.Count -gt 0) {
                        $last = $body.messages[-1].content
                        if ($last -is [array] -and $last.Count -gt 0) { $last = $last[0].text }
                        $s = "$last"
                        $pending[$rid].user_text = if ($s.Length -gt 60) { $s.Substring(0,60) + '…' } else { $s }
                    }
                }
            }
            elseif ($msg -eq 'final request' -and $pending.ContainsKey($rid)) {
                $pending[$rid].upstream_url = $d.requestUrl
            }
            elseif ($msg -eq 'request completed' -and $pending.ContainsKey($rid)) {
                $p = $pending[$rid]
                $pending.Remove($rid)
                $rt = [int]($d.responseTime)
                $status = if ($d.res) { $d.res.statusCode } else { '?' }
                $url = $p.upstream_url

                if ($url -match 'generativelanguage\.googleapis\.com') { $real = 'GOOGLE GEMINI'; $col = 'Green' }
                elseif ($url -match 'openrouter\.ai')                  { $real = 'OPENROUTER';    $col = 'Magenta' }
                elseif ($url -match 'anthropic\.com')                  { $real = 'ANTHROPIC';     $col = 'Yellow' }
                else                                                   { $real = "$url"; $col = 'White' }

                $realModel = '?'
                if ($url -match '/models/([^:?/]+)') { $realModel = $matches[1] }

                $ok = if ($status -eq 200) { '✓' } else { '✗' }
                $okCol = if ($status -eq 200) { 'Green' } else { 'Red' }

                Write-Host ("{0} " -f $p.started) -NoNewline -ForegroundColor DarkGray
                Write-Host $ok -NoNewline -ForegroundColor $okCol
                Write-Host " " -NoNewline
                Write-Host $real -NoNewline -ForegroundColor $col
                Write-Host " " -NoNewline
                Write-Host $realModel -NoNewline -ForegroundColor Cyan
                Write-Host (" ({0}ms)" -f $rt) -ForegroundColor DarkGray
                if ($p.user_text) {
                    Write-Host "     → " -NoNewline -ForegroundColor DarkGray
                    Write-Host $p.user_text -ForegroundColor Gray
                }
            }
        }

        Start-Sleep -Milliseconds 200
    }
} finally {
    Stop-Job -Job $sseJob -ErrorAction SilentlyContinue
    Stop-Job -Job $logJob -ErrorAction SilentlyContinue
    Remove-Job -Job $sseJob -Force -ErrorAction SilentlyContinue
    Remove-Job -Job $logJob -Force -ErrorAction SilentlyContinue
}
