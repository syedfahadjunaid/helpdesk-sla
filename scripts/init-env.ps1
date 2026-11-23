<#
.SYNOPSIS
    Creates .env with a fresh random value for every secret listed in .env.example.

.DESCRIPTION
    Run once before the first "docker compose up". An existing .env is never overwritten
    (Postgres and Keycloak keep the passwords they were first started with; to start over,
    "docker compose down -v" and delete .env).

    The values are not printed. The demo users' password is DEMO_USER_PASSWORD in .env.
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $root '.env'
$example = Join-Path $root '.env.example'

if (Test-Path $envFile) {
    Write-Host ".env already exists; leaving it alone."
    return
}

function New-Secret {
    # 24 random bytes from the OS's cryptographic generator, as URL-safe text (no quoting issues).
    $bytes = New-Object byte[] 24
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    return ([Convert]::ToBase64String($bytes)).Replace('+', '-').Replace('/', '_').TrimEnd('=')
}

$lines = foreach ($line in Get-Content $example) {
    if ($line -match '^([A-Z0-9_]+)=$') {
        "$($Matches[1])=$(New-Secret)"
    } else {
        $line
    }
}
# UTF-8 without a BOM: docker compose would read a BOM as part of the first variable name.
[System.IO.File]::WriteAllLines($envFile, [string[]]$lines, (New-Object System.Text.UTF8Encoding($false)))
Write-Host "Wrote $envFile with new random secrets."
