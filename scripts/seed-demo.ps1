<#
.SYNOPSIS
    Prepares the demo: signs each demo user in once (creating their agent record) and puts
    the agents into queues. With -FastSla, makes the URGENT SLA policy minutes long instead of
    hours, so a breach happens during a demo; -RestoreSla puts the normal targets back.

.EXAMPLE
    .\scripts\seed-demo.ps1 -FastSla
#>
[CmdletBinding()]
param([switch]$FastSla, [switch]$RestoreSla)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib.ps1')

# Agents are created on first sign-in ("just in time"), so sign everyone in once.
$ids = @{}
foreach ($user in 'ada', 'sam', 'ann', 'ben') {
    $me = Invoke-Helpdesk -User $user -Path '/api/me'
    $ids[$user] = $me.id
    Write-Host ("{0,-4} agent id {1,-3} roles: {2}" -f $user, $me.id, ($me.roles -join ', '))
}

$queues = @(Invoke-Helpdesk -User ada -Path '/api/queues')
$general = $queues | Where-Object { $_.key -eq 'general' }
$billing = $queues | Where-Object { $_.key -eq 'billing' }
Invoke-Helpdesk -User ada -Method PUT -Path "/api/queues/$($general.id)/members" `
    -Body @{ agentIds = @($ids.ann, $ids.ben, $ids.sam) } | Out-Null
Invoke-Helpdesk -User ada -Method PUT -Path "/api/queues/$($billing.id)/members" `
    -Body @{ agentIds = @($ids.ben, $ids.sam) } | Out-Null
Write-Host "Queues: General support = ann, ben, sam; Billing = ben, sam"

function Set-UrgentPolicy([int]$firstResponse, [int]$resolution, [int]$warning) {
    $policy = @(Invoke-Helpdesk -User ada -Path '/api/sla-policies') | Where-Object { $_.priority -eq 'URGENT' }
    Invoke-Helpdesk -User ada -Method PUT -Path '/api/sla-policies/URGENT' -Body @{
        firstResponseMinutes = $firstResponse
        resolutionMinutes    = $resolution
        warningPercent       = $warning
        calendarId           = $policy.calendarId
        version              = $policy.version
    } | Out-Null
    Write-Host "URGENT policy: first response $firstResponse min, resolution $resolution min, warning at $warning%"
}

if ($FastSla) { Set-UrgentPolicy 2 3 50 }
if ($RestoreSla) { Set-UrgentPolicy 30 240 75 }
