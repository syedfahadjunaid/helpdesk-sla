<#
.SYNOPSIS
    Sends an email as a customer, to a support address, through Mailpit's SMTP port.
    The helpdesk picks it up within a few seconds and turns it into a ticket (or adds it to
    the ticket it replies to).

.EXAMPLE
    .\scripts\send-email.ps1 -Subject "Printer on fire" -Body "Smoke everywhere"
.EXAMPLE
    .\scripts\send-email.ps1 -To billing@helpdesk.test -Subject "Invoice 42 is wrong" -Body "Charged twice"
#>
[CmdletBinding()]
param(
    [string]$From = 'jane@customer.example.test',
    [string]$FromName = 'Jane Doe',
    [string]$To = 'support@helpdesk.test',
    [Parameter(Mandatory)][string]$Subject,
    [Parameter(Mandatory)][string]$Body,
    [string]$InReplyTo
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib.ps1')

$messageId = Send-CustomerEmail -From $From -FromName $FromName -To $To -Subject $Subject -Body $Body -InReplyTo $InReplyTo
Write-Host "Sent $messageId from $From to $To. Watch it arrive at http://localhost:5580 (and in Mailpit: http://localhost:48025)."
