<#
.SYNOPSIS
    Checks the browser sign-in path end to end, without a browser.

.DESCRIPTION
    Does what a browser does, with a cookie jar:
      1. starts sign-in at /oauth2/authorization/keycloak and follows the redirect to Keycloak;
      2. submits Keycloak's real login form (demo user, password from .env, never printed);
      3. lands back on the app with an HttpOnly session cookie and no token anywhere;
    then checks: the session works on both API instances (nginx round-robins), state-changing
    calls need the CSRF header, the WebSocket accepts the session cookie and delivers a live
    update, a foreign Origin is refused, and sign-out ends the session.

.EXAMPLE
    .\scripts\smoke-test.ps1
#>
[CmdletBinding()]
param([ValidateSet('ada', 'sam', 'ann', 'ben')][string]$User = 'sam')

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib.ps1')
Add-Type -AssemblyName System.Web
# Windows PowerShell's web cmdlets wait forever by default, so one stalled connection on a busy
# machine would hang the script. Assigning (not indexing) keeps these defaults inside this script.
$PSDefaultParameterValues = @{ 'Invoke-WebRequest:TimeoutSec' = 60; 'Invoke-RestMethod:TimeoutSec' = 60 }

$base = 'http://localhost:5580'
$failures = 0
function Check([string]$what, [bool]$ok) {
    if ($ok) { Write-Host "  ok    $what" -ForegroundColor Green }
    else { Write-Host "  FAIL  $what" -ForegroundColor Red; $script:failures++ }
}
function Get-Status([scriptblock]$request) {
    try { return [int](& $request).StatusCode } catch { return [int]$_.Exception.Response.StatusCode }
}

Write-Host "Signing in as $User through Keycloak's login form..."
$loginPage = Invoke-WebRequest "$base/oauth2/authorization/keycloak" -SessionVariable browser -UseBasicParsing
# Keycloak marks its login cookies "Secure" even on http://localhost. Browsers accept that
# (localhost counts as a secure context), but .NET's cookie jar won't send Secure cookies over
# plain http, so copy them into the jar without the flag, as a browser would effectively do.
foreach ($setCookie in ([string]$loginPage.Headers['Set-Cookie'] -split ',(?=\s*[A-Za-z_]+=)')) {
    $name, $value = (($setCookie -split ';')[0]).Trim() -split '=', 2
    $browser.Cookies.Add((New-Object System.Net.Cookie($name, $value, '/', 'localhost')))
}
if ($loginPage.Content -notmatch 'id="kc-form-login"[^>]*action="([^"]+)"') { throw 'Keycloak login form not found' }
$action = [System.Web.HttpUtility]::HtmlDecode($Matches[1])
$landing = Invoke-WebRequest $action -Method Post -WebSession $browser -UseBasicParsing `
    -Body @{ username = $User; password = (Get-DemoPassword); credentialId = '' }
Check 'lands back on the app after sign-in' ($landing.BaseResponse.ResponseUri.AbsoluteUri -eq "$base/")

$cookies = $browser.Cookies.GetCookies($base)
$session = $cookies['HELPDESK_SESSION']
Check 'has the HttpOnly session cookie' ($null -ne $session -and $session.HttpOnly)
# (Only the app's own cookies: .NET's jar ignores ports, so Keycloak's SSO cookies, which are
# Keycloak's business, would show up here too.)
$appCookies = @($cookies['HELPDESK_SESSION'], $cookies['HELPDESK-XSRF-TOKEN']) | Where-Object { $_ }
Check "the app's cookies are opaque ids, not tokens" ($appCookies.Count -eq 2 -and -not ($appCookies | Where-Object { $_.Value -match '^eyJ' }))

$ids = @()
for ($i = 0; $i -lt 6; $i++) {
    $me = Invoke-RestMethod "$base/api/me" -WebSession $browser
    $ids += $me.id
}
Check "the session works on every request, across both API instances (6/6 as $($me.username))" (@($ids | Select-Object -Unique).Count -eq 1)

$csrf = $browser.Cookies.GetCookies($base)['HELPDESK-XSRF-TOKEN'].Value
Check 'a POST without the CSRF header is refused (403)' (403 -eq (Get-Status {
    Invoke-WebRequest "$base/api/notifications/read-all" -Method Post -WebSession $browser -UseBasicParsing -ContentType 'application/json' -Body '{}' }))
Check 'a POST with the CSRF header works (200)' (200 -eq (Get-Status {
    Invoke-WebRequest "$base/api/notifications/read-all" -Method Post -WebSession $browser -UseBasicParsing -ContentType 'application/json' -Body '{}' -Headers @{ 'X-XSRF-TOKEN' = $csrf } }))

# ---- WebSocket, authenticated by the session cookie, like the web app ----------------------
$none = [System.Threading.CancellationToken]::None
$waitMs = 30000   # generous: on a busy machine a frame can take several seconds
function Open-Socket([string]$origin) {
    $socket = New-Object System.Net.WebSockets.ClientWebSocket
    $socket.Options.Cookies = $browser.Cookies
    if ($origin) { $socket.Options.SetRequestHeader('Origin', $origin) }
    if (-not $socket.ConnectAsync([Uri]'ws://localhost:5580/ws', $none).Wait($waitMs)) { throw 'WebSocket connect timed out' }
    return $socket
}
function Send-Frame($socket, [string]$frame) {
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($frame + [char]0)
    $segment = New-Object System.ArraySegment[byte] -ArgumentList @(, $bytes)
    $socket.SendAsync($segment, [System.Net.WebSockets.WebSocketMessageType]::Text, $true, $none).Wait()
}
# A WebSocket allows one outstanding receive at a time, so a receive that hasn't finished yet is
# kept and picked up by the next call instead of being abandoned.
$script:pendingReceive = $null
$script:receiveBuffer = New-Object byte[] 65536
function Receive-Frame($socket, [int]$timeoutMs) {
    if (-not $script:pendingReceive) {
        $segment = New-Object System.ArraySegment[byte] -ArgumentList @(, $script:receiveBuffer)
        $script:pendingReceive = $socket.ReceiveAsync($segment, $none)
    }
    if (-not $script:pendingReceive.Wait($timeoutMs)) { return $null }
    $count = $script:pendingReceive.Result.Count
    $script:pendingReceive = $null
    return [System.Text.Encoding]::UTF8.GetString($script:receiveBuffer, 0, $count)
}
# Waits for a frame of one kind (skipping others, e.g. updates about other tickets).
function Wait-Frame($socket, [string]$command, [string]$containing = '') {
    $deadline = (Get-Date).AddMilliseconds($waitMs)
    while ((Get-Date) -lt $deadline) {
        $frame = Receive-Frame $socket 1000
        if ($frame -and $frame.StartsWith($command) -and $frame.Contains($containing)) { return $true }
    }
    return $false
}

$refused = $false
try { Open-Socket 'http://evil.example' | Out-Null } catch { $refused = "$_" -notlike '*timed out*' }
Check 'a WebSocket from another site (foreign Origin) is refused' $refused

$socket = Open-Socket 'http://localhost:5580'
Send-Frame $socket "CONNECT`naccept-version:1.2`nhost:localhost`nheart-beat:0,0`n`n"
Check 'STOMP CONNECT over the cookie-authenticated WebSocket' (Wait-Frame $socket 'CONNECTED')
Send-Frame $socket "SUBSCRIBE`nid:sub-0`ndestination:/topic/tickets`nreceipt:r-0`n`n"
Check 'subscribed to /topic/tickets' (Wait-Frame $socket 'RECEIPT' 'receipt-id:r-0')

$queue = @(Invoke-RestMethod "$base/api/queues" -WebSession $browser | ForEach-Object { $_ }) | Select-Object -First 1
$created = Invoke-RestMethod "$base/api/tickets" -Method Post -WebSession $browser -ContentType 'application/json' `
    -Headers @{ 'X-XSRF-TOKEN' = $csrf } -Body (@{
        customerEmail = 'smoke@customer.example.test'; customerName = 'Smoke Test'; subject = "Smoke test $(Get-Date -Format s)"
        description = 'Created by smoke-test.ps1'; queueId = $queue.id; priority = 'LOW' } | ConvertTo-Json)
$number = $created.ticket.number
Check "the browser's WebSocket hears about $number" (Wait-Frame $socket 'MESSAGE' $number)
$socket.Dispose()

$logout = Invoke-RestMethod "$base/logout" -Method Post -WebSession $browser -Headers @{ 'X-XSRF-TOKEN' = $csrf }
Check 'sign-out answers with the Keycloak logout URL' ($logout.logoutUrl -like 'http://localhost:8680/realms/helpdesk/protocol/openid-connect/logout*')
Check 'after sign-out the API answers 401' (401 -eq (Get-Status { Invoke-WebRequest "$base/api/me" -WebSession $browser -UseBasicParsing }))

if ($failures -gt 0) { Write-Host "$failures check(s) failed." -ForegroundColor Red; exit 1 }
Write-Host 'All browser-path checks passed.' -ForegroundColor Green
