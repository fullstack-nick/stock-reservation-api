#Requires -Version 7.0
[CmdletBinding()]
param([string]$BaseUrl = 'http://127.0.0.1:8080')

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$BaseUrl = $BaseUrl.TrimEnd('/')
$client = [System.Net.Http.HttpClient]::new()
$client.Timeout = [TimeSpan]::FromSeconds(30)
$ownedIds = [System.Collections.Generic.HashSet[string]]::new()
$prefix = 'DEMO-' + [guid]::NewGuid().ToString('N')

function New-DemoRequest([string]$Method, [string]$Path, $Body = $null) {
    $message = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::new($Method), "$BaseUrl$Path")
    $message.Headers.Add('Accept', 'application/json')
    $message.Headers.Add('X-Correlation-ID', [guid]::NewGuid().ToString())
    if ($null -ne $Body) {
        $message.Content = [System.Net.Http.StringContent]::new(
            ($Body | ConvertTo-Json -Compress), [System.Text.Encoding]::UTF8, 'application/json')
    }
    return $message
}

function Read-DemoResponse($Response) {
    try {
        $text = $Response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        return [pscustomobject]@{ Status = [int]$Response.StatusCode; Body = ($text | ConvertFrom-Json) }
    } finally { $Response.Dispose() }
}

function Send-DemoRequest([string]$Method, [string]$Path, $Body = $null) {
    $message = New-DemoRequest $Method $Path $Body
    try { return Read-DemoResponse ($client.SendAsync($message).GetAwaiter().GetResult()) }
    finally { $message.Dispose() }
}

function Assert-Demo([bool]$Condition, [string]$Description) {
    if (-not $Condition) { throw "Demo assertion failed: $Description" }
}

try {
    $health = Send-DemoRequest GET '/actuator/health'
    Assert-Demo ($health.Status -eq 200 -and $health.Body.status -eq 'UP') 'database health is UP'
    $inputBody = @{ externalRequestId = "$prefix-YOGURT"; sku = 'YOGURT-CASE'; quantity = 9; requiredOn = '2026-11-02' }
    $created = Send-DemoRequest POST '/reservations' $inputBody
    if ($created.Status -eq 201) { [void]$ownedIds.Add($created.Body.id) }
    Assert-Demo ($created.Status -eq 201) "create returns 201 (received $($created.Status)); use the demo profile and available seed stock"
    $path = '/reservations/' + $created.Body.id
    Assert-Demo ($created.Body.allocations.Count -eq 2) 'two yogurt batches'
    Assert-Demo ($created.Body.allocations[0].batchId -eq 101 -and $created.Body.allocations[0].quantity -eq 6) 'first batch contributes six'
    Assert-Demo ($created.Body.allocations[1].batchId -eq 102 -and $created.Body.allocations[1].quantity -eq 3) 'second batch contributes three'
    Write-Host "Created $($created.Body.id): batch 101 = 6, batch 102 = 3."
    $read = Send-DemoRequest GET $path
    Assert-Demo ($read.Status -eq 200 -and $read.Body.id -eq $created.Body.id) 'GET returns the persisted reservation'
    $replay = Send-DemoRequest POST '/reservations' $inputBody
    Assert-Demo ($replay.Status -eq 200 -and $replay.Body.id -eq $created.Body.id) 'retry has one identity'
    $released = Send-DemoRequest POST "$path/release"
    Assert-Demo ($released.Status -eq 200 -and $released.Body.status -eq 'RELEASED') 'release succeeds'
    $again = Send-DemoRequest POST "$path/release"
    Assert-Demo ($again.Status -eq 200 -and $again.Body.releasedAt -eq $released.Body.releasedAt) 'second release preserves release time'
    $afterRelease = Send-DemoRequest POST '/reservations' $inputBody
    Assert-Demo ($afterRelease.Status -eq 200 -and $afterRelease.Body.status -eq 'RELEASED') 'creation replay does not reactivate stock'
    Write-Host 'GET, identical retry, two releases, and replay after release passed.'

    # Launch both HTTP operations before awaiting either. Deterministic overlap is proved separately in the integration tests.
    $messages = @('A', 'B') | ForEach-Object {
        New-DemoRequest POST '/reservations' @{ externalRequestId = "$prefix-APPLE-$_"; sku = 'APPLE-CASE'; quantity = 4; requiredOn = '2026-11-02' }
    }
    try {
        $tasks = @($messages | ForEach-Object { $client.SendAsync($_) })
        $outcomes = @($tasks | ForEach-Object {
            $result = Read-DemoResponse ($_.GetAwaiter().GetResult())
            if ($result.Status -eq 201) { [void]$ownedIds.Add($result.Body.id) }
            $result
        })
        Assert-Demo ((($outcomes.Status | Sort-Object) -join ',') -eq '201,409') 'competing four-of-five requests return 201 and 409'
        $conflict = $outcomes | Where-Object Status -eq 409
        Assert-Demo ($conflict.Body.code -eq 'INSUFFICIENT_STOCK') 'competition fails with the stock conflict code'
        Write-Host 'Competing apple requests: one 201, one 409; four of five cases reserved.'
    } finally { $messages | ForEach-Object { $_.Dispose() } }
} finally {
    $cleanupFailures = @()
    foreach ($id in $ownedIds) {
        try {
            $cleanup = Send-DemoRequest POST "/reservations/$id/release"
            if ($cleanup.Status -ne 200) { $cleanupFailures += $id }
        } catch { $cleanupFailures += $id }
    }
    $client.Dispose()
    if ($cleanupFailures.Count -gt 0) {
        throw "Could not release demo reservations: $($cleanupFailures -join ', '). Restart the app and release these IDs."
    }
}
Write-Host 'Demo passed. Every reservation created by this run is released; history is retained.'
