<#
.SYNOPSIS
    Helpers for the demo scripts. Dot-source it:  . .\scripts\lib.ps1

.DESCRIPTION
    Get-HelpdeskToken   a demo user's access token (dev-only "helpdesk-cli" client, password grant)
    Invoke-Helpdesk     calls the API through nginx (http://localhost:5580) as a demo user
    Send-CustomerEmail  sends an email to a support address through Mailpit's SMTP port
    Find-MailpitMessage searches Mailpit (the mailbox everything lands in)

    Written for Windows PowerShell 5.1. The demo password is read from .env and never printed.
#>

$script:HelpdeskRoot = Split-Path -Parent $PSScriptRoot
$script:ApiBase = 'http://localhost:5580'
$script:TokenUrl = 'http://localhost:8680/realms/helpdesk/protocol/openid-connect/token'
$script:MailpitApi = 'http://localhost:48025/api/v1'
$script:TokenCache = @{}

function Get-DemoPassword {
    $envFile = Join-Path $script:HelpdeskRoot '.env'
    if (-not (Test-Path $envFile)) { throw ".env not found: run .\scripts\init-env.ps1 first" }
    $line = Get-Content $envFile | Where-Object { $_ -match '^DEMO_USER_PASSWORD=' } | Select-Object -First 1
    if (-not $line) { throw "DEMO_USER_PASSWORD missing from .env" }
    return $line.Substring('DEMO_USER_PASSWORD='.Length)
}

function Get-HelpdeskToken {
    param([Parameter(Mandatory)][ValidateSet('ada', 'sam', 'ann', 'ben')][string]$User)
    $cached = $script:TokenCache[$User]
    if ($cached -and $cached.Expires -gt (Get-Date)) { return $cached.Token }
    $response = Invoke-RestMethod -Method Post -Uri $script:TokenUrl -Body @{
        grant_type = 'password'
        client_id  = 'helpdesk-cli'
        username   = $User
        password   = (Get-DemoPassword)
        scope      = 'openid'
    }
    # Tokens live 5 minutes; refresh a little early.
    $script:TokenCache[$User] = @{ Token = $response.access_token; Expires = (Get-Date).AddSeconds(240) }
    return $response.access_token
}

function Invoke-Helpdesk {
    param(
        [Parameter(Mandatory)][string]$User,
        [Parameter(Mandatory)][string]$Path,
        [ValidateSet('GET', 'POST', 'PUT')][string]$Method = 'GET',
        $Body
    )
    $params = @{
        Method  = $Method
        Uri     = "$script:ApiBase$Path"
        Headers = @{ Authorization = "Bearer $(Get-HelpdeskToken $User)" }
    }
    if ($Method -ne 'GET') {
        # PowerShell 5.1 sends a form content type when a POST has no body, so always send JSON.
        if ($null -eq $Body) { $Body = @{} }
        $params.Body = [System.Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 10))
        $params.ContentType = 'application/json; charset=utf-8'
    }
    # PowerShell 5.1 passes a JSON array down the pipeline as ONE object; ForEach-Object
    # unrolls it, so callers can filter the items (and wrap the result in @() for a list).
    return Invoke-RestMethod @params | ForEach-Object { $_ }
}

function Send-CustomerEmail {
    param(
        [Parameter(Mandatory)][string]$From,
        [string]$FromName = '',
        [Parameter(Mandatory)][string]$To,
        [Parameter(Mandatory)][string]$Subject,
        [Parameter(Mandatory)][string]$Body,
        [string]$InReplyTo
    )
    $message = New-Object System.Net.Mail.MailMessage
    $message.From = New-Object System.Net.Mail.MailAddress($From, $FromName)
    $message.To.Add($To)
    $message.Subject = $Subject
    $message.Body = $Body
    $messageId = "<$([guid]::NewGuid())@customer.example.test>"
    $message.Headers.Add('Message-ID', $messageId)
    if ($InReplyTo) {
        $message.Headers.Add('In-Reply-To', $InReplyTo)
        $message.Headers.Add('References', $InReplyTo)
    }
    $client = New-Object System.Net.Mail.SmtpClient('localhost', 41025)
    try {
        $client.Send($message)
    } finally {
        $message.Dispose()
        $client.Dispose()
    }
    return $messageId
}

function Find-MailpitMessage {
    param([Parameter(Mandatory)][string]$Query)
    $encoded = [System.Uri]::EscapeDataString($Query)
    $result = Invoke-RestMethod -Uri "$script:MailpitApi/search?query=$encoded"
    return @($result.messages)
}

function Wait-Until {
    param([Parameter(Mandatory)][scriptblock]$Condition, [int]$TimeoutSeconds = 60, [string]$What = 'condition')
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $value = & $Condition
        if ($value) { return $value }
        Start-Sleep -Seconds 1
    }
    throw "Timed out after $TimeoutSeconds s waiting for $What"
}
