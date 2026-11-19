# Creates %USERPROFILE%\.van-gogh\llm.conf for Windows.
#
#   powershell -ExecutionPolicy Bypass -File scripts\setup-llm.ps1
#   powershell -ExecutionPolicy Bypass -File scripts\setup-llm.ps1 -Key sk-or-v1-...
#   $env:VANGOGH_LLM_KEY = "sk-or-v1-..."; .\scripts\setup-llm.ps1
#
# Re-running replaces the file. The key never enters the repo: the script
# writes outside the working tree, in the current user's profile.
[CmdletBinding()]
param(
    [string]$Key = $env:VANGOGH_LLM_KEY,
    [string]$BaseUrl,
    [string]$Model
)

$ErrorActionPreference = "Stop"

$dir = Join-Path $env:USERPROFILE ".van-gogh"
$file = Join-Path $dir "llm.conf"

if (-not $Key) {
    $secure = Read-Host -AsSecureString "OpenRouter API key (input hidden)"
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try {
        $Key = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
    }
}

$Key = ($Key -replace '\s', '')
if (-not $Key) {
    Write-Error "No API key given - nothing written."
    exit 1
}

New-Item -ItemType Directory -Force $dir | Out-Null

$lines = @(
    "# VanGogh LLM settings - created $(Get-Date -Format 'yyyy-MM-dd'). The Settings page writes this file too.",
    "apiKey = $Key"
)
if ($BaseUrl) { $lines += "baseUrl = $BaseUrl" }
else { $lines += "# baseUrl = https://openrouter.ai/api/v1" }
if ($Model) { $lines += "model = $Model" }
else { $lines += "# model   = baidu/ernie-5.5-flash-preview:free" }
$lines += "# timeoutSeconds = 60", "# titles = 10"

# UTF8 without BOM: the reader tolerates one, but there is no reason to add it.
[System.IO.File]::WriteAllLines($file, $lines, (New-Object System.Text.UTF8Encoding($false)))

Write-Host "Wrote $file"
Write-Host "Restart VanGogh, press AI in the search bar, and search a title or a vibe."
Write-Host "Or edit these values later in Settings - a saved change needs no restart."
