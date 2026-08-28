param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('redis-latency', 'redis-cut', 'restore')]
    [string]$Scenario,
    [int]$LatencyMs = 1500
)

$ErrorActionPreference = 'Stop'
$proxyApi = if ($env:TOXIPROXY_URL) { $env:TOXIPROXY_URL } else { 'http://localhost:8474' }

if ($Scenario -eq 'redis-latency') {
    $body = @{
        name = 'latency_downstream'
        type = 'latency'
        stream = 'downstream'
        toxicity = 1.0
        attributes = @{ latency = $LatencyMs; jitter = 100 }
    } | ConvertTo-Json -Depth 4
    Invoke-RestMethod -Method Post -Uri "$proxyApi/proxies/redis/toxics" -ContentType 'application/json' -Body $body
    Write-Host "Applied ${LatencyMs}ms real Redis network latency."
}
elseif ($Scenario -eq 'redis-cut') {
    $body = @{
        name = 'redis'
        listen = '0.0.0.0:16379'
        upstream = 'redis:6379'
        enabled = $false
    } | ConvertTo-Json
    Invoke-RestMethod -Method Post -Uri "$proxyApi/proxies/redis" -ContentType 'application/json' -Body $body
    Write-Host 'Disabled the Redis proxy. MerchantFlow will receive real connection failures.'
}
else {
    try {
        Invoke-RestMethod -Method Delete -Uri "$proxyApi/proxies/redis/toxics/latency_downstream"
    } catch {
        Write-Host 'No Redis latency toxic was active.'
    }
    $body = @{
        name = 'redis'
        listen = '0.0.0.0:16379'
        upstream = 'redis:6379'
        enabled = $true
    } | ConvertTo-Json
    Invoke-RestMethod -Method Post -Uri "$proxyApi/proxies/redis" -ContentType 'application/json' -Body $body
    Write-Host 'Restored the Redis proxy and removed lab latency.'
}
