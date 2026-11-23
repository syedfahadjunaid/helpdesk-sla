<#
.SYNOPSIS
    The end-to-end demo against the running stack: an email becomes a ticket, an agent picks
    it up, the SLA clock pauses and resumes, a breach escalates, and everyone is told.

.DESCRIPTION
    Uses the demo users (password from .env) and the API through nginx, so calls are spread
    over both API instances. Takes about four minutes, most of it waiting for the SLA breach.

    To watch it live, sign in at http://localhost:5580 as sam (a supervisor) before running it,
    or run .\scripts\watch-live.ps1 in a second terminal. Mailpit (http://localhost:48025) shows
    every email: the customer's, the acknowledgement, the agent's reply, the breach alert.

    The demo shortens the URGENT SLA policy to minutes (first response 2, resolution 3) so the
    breach happens while you watch. Put the real targets back with:
        .\scripts\seed-demo.ps1 -RestoreSla
#>
[CmdletBinding()]
param([string]$Customer = "jane.$((Get-Random -Maximum 99999))@customer.example.test")

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib.ps1')

function Step([string]$text) { Write-Host "`n== $text" -ForegroundColor Cyan }
function Show-Sla($detail) {
    foreach ($timer in $detail.ticket.sla) {
        $due = if ($timer.dueAt) { " due $($timer.dueAt)" } else { '' }
        Write-Host ("   {0,-15} {1,-9} clock {2,-8} {3,5} s left{4}" -f $timer.metric, $timer.health, $timer.clockState,
            $timer.remainingSeconds, $due)
    }
}

Step 'Seeding: agents into queues, URGENT SLA shortened to minutes'
& (Join-Path $PSScriptRoot 'seed-demo.ps1') -FastSla

Step "A customer ($Customer) emails support@helpdesk.test"
$subject = "Checkout page crashes ($(Get-Date -Format 'HH:mm:ss'))"
$firstEmail = Send-CustomerEmail -From $Customer -FromName 'Jane Customer' -To 'support@helpdesk.test' `
    -Subject $subject -Body "Every time I press Pay, the page goes blank. Order 1234."
Write-Host "   sent $firstEmail"

$summary = Wait-Until -What 'the email to become a ticket' -TimeoutSeconds 60 {
    $page = Invoke-Helpdesk -User ann -Path "/api/tickets?q=$([uri]::EscapeDataString($subject))"
    @($page.items) | Select-Object -First 1
}
$id = $summary.id
Write-Host "   became $($summary.number) in '$($summary.queue.name)', status $($summary.status), priority $($summary.priority)"

Step 'The helpdesk acknowledged it by email'
$ack = Wait-Until -What 'the acknowledgement email' -TimeoutSeconds 30 {
    Find-MailpitMessage "to:`"$Customer`"" | Where-Object { $_.Subject -like '*We received your request*' } | Select-Object -First 1
}
Write-Host "   '$($ack.Subject)'"

Step 'Ann triages it as URGENT and takes it (first response now due in 2 minutes)'
$detail = Invoke-Helpdesk -User ann -Method PUT -Path "/api/tickets/$id/priority" -Body @{ priority = 'URGENT' }
$detail = Invoke-Helpdesk -User ann -Method POST -Path "/api/tickets/$id/actions/ASSIGN"
Write-Host "   status $($detail.ticket.status), assignee $($detail.ticket.assignee.name)"
Show-Sla $detail

Step 'Ann replies to the customer: first response met; the reply is emailed'
Invoke-Helpdesk -User ann -Method POST -Path "/api/tickets/$id/messages" `
    -Body @{ body = "Sorry about that! Which browser are you using?"; visibility = 'PUBLIC' } | Out-Null
$detail = Invoke-Helpdesk -User ann -Method POST -Path "/api/tickets/$id/actions/WAIT_ON_CUSTOMER"
Write-Host "   status $($detail.ticket.status): the SLA clock is paused while we wait for the customer"
Show-Sla $detail

$reply = Wait-Until -What "the agent's reply email" -TimeoutSeconds 30 {
    Find-MailpitMessage "to:`"$Customer`"" | Where-Object { $_.Subject -like '*Re:*' } | Select-Object -First 1
}
$replyId = if ($reply.MessageID.StartsWith('<')) { $reply.MessageID } else { "<$($reply.MessageID)>" }
Write-Host "   customer received '$($reply.Subject)' ($replyId)"

Step 'Twenty seconds pass while waiting on the customer: no SLA time is used'
Start-Sleep -Seconds 20
Show-Sla (Invoke-Helpdesk -User ann -Path "/api/tickets/$id")

Step 'The customer replies to that email: threaded onto the ticket, clock resumes'
Send-CustomerEmail -From $Customer -FromName 'Jane Customer' -To 'support@helpdesk.test' `
    -Subject "Re: $($reply.Subject)" -InReplyTo $replyId `
    -Body "Firefox 140.`r`n`r`nOn $(Get-Date), Support wrote:`r`n> ##- Please type your reply above this line -##`r`n> Which browser?" | Out-Null
$detail = Wait-Until -What 'the reply to be threaded' -TimeoutSeconds 60 {
    $d = Invoke-Helpdesk -User ann -Path "/api/tickets/$id"
    if ($d.ticket.status -eq 'OPEN') { $d }
}
Write-Host "   status $($detail.ticket.status); last message: '$($detail.messages[-1].body)'"
Show-Sla $detail

Step 'Nobody resolves it... waiting for the SLA worker to warn, then escalate (up to ~3 minutes)'
$detail = Wait-Until -What 'the SLA breach' -TimeoutSeconds 240 {
    $d = Invoke-Helpdesk -User ann -Path "/api/tickets/$id"
    if ($d.ticket.escalationLevel -gt 0) { $d }
}
Write-Host "   $($detail.ticket.number) escalated to level $($detail.ticket.escalationLevel)" -ForegroundColor Yellow
Show-Sla $detail

Step 'Who was told'
$samNotes = Wait-Until -What "sam's notification" -TimeoutSeconds 30 {
    @((Invoke-Helpdesk -User sam -Path '/api/notifications').items) | Where-Object { $_.ticketId -eq $id }
}
$samNotes | ForEach-Object { Write-Host "   sam (in the app): $($_.message)" }
@((Invoke-Helpdesk -User ann -Path '/api/notifications').items) | Where-Object { $_.ticketId -eq $id } |
    ForEach-Object { Write-Host "   ann (in the app): $($_.message)" }
$alert = Wait-Until -What 'the breach alert email' -TimeoutSeconds 30 {
    Find-MailpitMessage 'to:"sam@helpdesk.test"' | Where-Object { $_.Subject -like "*HD-$id*SLA breached*" } | Select-Object -First 1
}
Write-Host "   sam (by email): '$($alert.Subject)'"

Step 'The audit history'
Invoke-Helpdesk -User ann -Path "/api/tickets/$id/history" | ForEach-Object {
    Write-Host ("   {0}  {1,-16} by {2}" -f $_.occurredAt, $_.action, $_.actorName)
}

Write-Host "`nDone. Open http://localhost:5580/tickets/$id to see it, and http://localhost:48025 for the emails."
Write-Host "Restore the real URGENT targets with: .\scripts\seed-demo.ps1 -RestoreSla"
