# switcher-banner.ps1 — UserPromptSubmit-Hook (Windows-Pendant)
# Blendet Switcher-Events (90%/100%-Warnung) als zusätzlichen Kontext in
# claude's Chat ein, sodass der User direkt Bescheid weiß ohne extra fragen.
#
# Output-Spec (Claude Code Hooks): JSON auf stdout mit
#   { "hookSpecificOutput": { "hookEventName": "UserPromptSubmit",
#                              "additionalContext": "..." } }
# Bei leerem Banner: kein Output → kein Context-Eintrag.

$ErrorActionPreference = 'SilentlyContinue'
$switcherUrl = if ($env:CLAUDE_SWITCHER_URL) { $env:CLAUDE_SWITCHER_URL } else { 'http://localhost:2000' }

try {
    $banner = Invoke-RestMethod -Uri "$switcherUrl/api/banner" -TimeoutSec 1 -ErrorAction Stop
} catch {
    exit 0
}

if ([string]::IsNullOrWhiteSpace($banner)) { exit 0 }

$payload = @{
    hookSpecificOutput = @{
        hookEventName     = 'UserPromptSubmit'
        additionalContext = "$banner"
    }
}
$payload | ConvertTo-Json -Compress -Depth 5
exit 0
