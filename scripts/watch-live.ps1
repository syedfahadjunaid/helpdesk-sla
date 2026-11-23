<#
.SYNOPSIS
    Prints the live ticket updates the browser receives, in a terminal.

.DESCRIPTION
    Opens the same WebSocket the web app uses (ws://localhost:5580/ws, through nginx, on
    whichever API instance nginx picks) and speaks STOMP by hand: CONNECT, SUBSCRIBE to
    /topic/tickets, then print every MESSAGE frame. The handshake is authenticated with a demo
    user's bearer token (browsers use their session cookie instead).

    Changes made through the *other* API instance show up too: RabbitMQ's STOMP relay fans
    them out to every instance.

.EXAMPLE
    .\scripts\watch-live.ps1 -Seconds 300
#>
[CmdletBinding()]
param([ValidateSet('ada', 'sam', 'ann', 'ben')][string]$User = 'sam', [int]$Seconds = 120)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib.ps1')

$socket = New-Object System.Net.WebSockets.ClientWebSocket
$socket.Options.SetRequestHeader('Authorization', "Bearer $(Get-HelpdeskToken $User)")
$none = [System.Threading.CancellationToken]::None
$socket.ConnectAsync([Uri]'ws://localhost:5580/ws', $none).Wait()

function Send-Frame([string]$frame) {
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($frame + [char]0)
    $segment = New-Object System.ArraySegment[byte] -ArgumentList @(, $bytes)
    $socket.SendAsync($segment, [System.Net.WebSockets.WebSocketMessageType]::Text, $true, $none).Wait()
}

# SUBSCRIBE only after the server's CONNECTED frame: until then the relay to RabbitMQ isn't
# ready and would drop it (real STOMP clients, like the web app's, wait the same way).
Send-Frame "CONNECT`naccept-version:1.2`nhost:localhost`nheart-beat:0,0`n`n"
Write-Host "Watching /topic/tickets as $User for $Seconds s (Ctrl+C to stop)..."

$buffer = New-Object byte[] 65536
$deadline = (Get-Date).AddSeconds($Seconds)
$text = ''
while ((Get-Date) -lt $deadline -and $socket.State -eq 'Open') {
    $segment = New-Object System.ArraySegment[byte] -ArgumentList @(, $buffer)
    $receive = $socket.ReceiveAsync($segment, $none)
    while (-not $receive.Wait(500)) {
        if ((Get-Date) -ge $deadline) { break }
    }
    if (-not $receive.IsCompleted) { break }
    $text += [System.Text.Encoding]::UTF8.GetString($buffer, 0, $receive.Result.Count)
    if (-not $receive.Result.EndOfMessage) { continue }
    $command = ($text -split "`n")[0]
    $body = ($text -split "`n`n", 2)[1]
    if ($body) { $body = $body.TrimEnd([char]0) }
    switch ($command) {
        'CONNECTED' {
            Write-Host "Connected."
            Send-Frame "SUBSCRIBE`nid:sub-0`ndestination:/topic/tickets`n`n"
        }
        'MESSAGE' {
            $update = $body | ConvertFrom-Json
            Write-Host ("{0}  {1,-8} {2}" -f (Get-Date -Format 'HH:mm:ss'), $update.ticketNumber, $update.type)
        }
        'ERROR' { Write-Warning "STOMP error: $text" }
    }
    $text = ''
}
if ($socket.State -eq 'Open') {
    $socket.CloseAsync([System.Net.WebSockets.WebSocketCloseStatus]::NormalClosure, 'bye', $none).Wait()
}
