param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('cpu-saturation', 'redis-latency', 'redis-cut', 'mysql-latency', 'mysql-cut', 'rocketmq-cut', 'http-health-down', 'restore')]
    [string]$Scenario,
    [ValidateRange(100, 60000)]
    [int]$LatencyMs = 1500,
    [ValidateRange(1, 3600)]
    [int]$DurationSeconds = 60
)

$ErrorActionPreference = 'Stop'
$proxyApi = if ($env:TOXIPROXY_URL) { $env:TOXIPROXY_URL.TrimEnd('/') } else { 'http://localhost:8474' }
$prometheusApi = if ($env:PROMETHEUS_URL) { $env:PROMETHEUS_URL.TrimEnd('/') } else { 'http://localhost:9090' }
$aiopsApi = if ($env:AIOPS_URL) { $env:AIOPS_URL.TrimEnd('/') } else { 'http://localhost:9900' }

function Assert-LabMode {
    <#
    只有显式开启 LAB_MODE=true 才允许停止容器或修改 Toxiproxy。
    这样普通开发环境即使误执行脚本，也只会安全失败，不会制造真实中断。
    下一步：实验结束后仍需执行 restore，确认依赖代理和业务容器恢复健康。
    #>
    if (-not [string]::Equals($env:LAB_MODE, 'true', [StringComparison]::OrdinalIgnoreCase)) {
        throw '当前 LAB_MODE 不是 true，已拒绝故障注入或恢复操作。请在隔离实验终端显式设置 $env:LAB_MODE=true。'
    }
}

function Get-AIOpsAccessToken {
    <# 使用本机管理员账号读取实验产生的 Incident/Run；密码只在请求体内使用，不写日志。 #>
    if ([string]::IsNullOrWhiteSpace($env:AIOPS_ADMIN_USERNAME) -or
        [string]::IsNullOrWhiteSpace($env:AIOPS_ADMIN_PASSWORD)) {
        throw '故障闭环验收需要 AIOPS_ADMIN_USERNAME 和 AIOPS_ADMIN_PASSWORD 环境变量。'
    }
    $body = @{
        username = $env:AIOPS_ADMIN_USERNAME
        password = $env:AIOPS_ADMIN_PASSWORD
    } | ConvertTo-Json
    $response = Invoke-RestMethod -Method Post -Uri "$aiopsApi/api/v1/auth/login" `
        -ContentType 'application/json' -Body $body
    return [string]$response.access_token
}

function Get-DiagnosisRunIds {
    <# 记录实验前指定告警已有的 Run ID，避免把历史诊断误当作本轮结果。 #>
    param(
        [Parameter(Mandatory = $true)][string]$AccessToken,
        [Parameter(Mandatory = $true)][string]$AlertName
    )
    $headers = @{ Authorization = "Bearer $AccessToken" }
    $response = Invoke-RestMethod -Method Get `
        -Uri "$aiopsApi/api/v1/incidents?service_name=merchantflow&limit=200" -Headers $headers
    $ids = @()
    foreach ($incident in $response.items) {
        if ($incident.alert_name -ne $AlertName) { continue }
        foreach ($run in $incident.diagnoses) {
            $ids += [string]$run.id
        }
    }
    return @($ids)
}

function Get-PrometheusAlertState {
    <# 从真实 Prometheus 规则状态读取 pending/firing/inactive，不以瞬时指标代替告警。 #>
    param([Parameter(Mandatory = $true)][string]$AlertName)
    $response = Invoke-RestMethod -Method Get -Uri "$prometheusApi/api/v1/rules?type=alert"
    foreach ($group in $response.data.groups) {
        foreach ($rule in $group.rules) {
            if ($rule.name -eq $AlertName) { return [string]$rule.state }
        }
    }
    throw "Prometheus 中不存在告警规则 '$AlertName'。"
}

function Wait-PrometheusAlertState {
    <# 在有界时间内等待规则进入或离开 firing，超时即判定实验失败。 #>
    param(
        [Parameter(Mandatory = $true)][string]$AlertName,
        [Parameter(Mandatory = $true)][bool]$ShouldFire,
        [ValidateRange(10, 600)][int]$TimeoutSeconds = 180
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $state = Get-PrometheusAlertState -AlertName $AlertName
        if (($ShouldFire -and $state -eq 'firing') -or (-not $ShouldFire -and $state -ne 'firing')) {
            return $state
        }
        Start-Sleep -Seconds 5
    } while ((Get-Date) -lt $deadline)
    throw "等待告警 '$AlertName' 状态失败，当前 state=$state，期望 firing=$ShouldFire。"
}

function Wait-MqCounterBaseline {
    <# 等待 Prometheus 至少抓取两个 Counter 样本，保证第一次 0→1 能被 increase() 识别。 #>
    param([ValidateRange(15, 180)][int]$TimeoutSeconds = 90)

    $query = 'count_over_time(merchantflow_mq_publish_failures_total{job="merchantflow",flow="seckill-order"}[2m])'
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $uri = "$prometheusApi/api/v1/query?query=$([uri]::EscapeDataString($query))"
        $response = Invoke-RestMethod -Method Get -Uri $uri
        $rows = @($response.data.result)
        if ($rows.Count -gt 0 -and [double]$rows[0].value[1] -ge 2) { return }
        Start-Sleep -Seconds 5
    } while ((Get-Date) -lt $deadline)
    throw 'Prometheus 没有取得 RocketMQ Counter 的两个基线样本，已拒绝开始故障实验。'
}

function Wait-NewDiagnosis {
    <# 等待指定告警的新 Run 完成，并严格校验预期根因，防止用历史或错误结论冒充闭环。 #>
    param(
        [Parameter(Mandatory = $true)][string]$AccessToken,
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][string[]]$BaselineRunIds,
        [Parameter(Mandatory = $true)][string]$AlertName,
        [Parameter(Mandatory = $true)][string]$ExpectedCategory,
        [ValidateRange(10, 600)][int]$TimeoutSeconds = 240
    )
    $headers = @{ Authorization = "Bearer $AccessToken" }
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $response = Invoke-RestMethod -Method Get `
            -Uri "$aiopsApi/api/v1/incidents?service_name=merchantflow&limit=200" -Headers $headers
        foreach ($incident in $response.items) {
            if ($incident.alert_name -ne $AlertName) { continue }
            foreach ($run in $incident.diagnoses) {
                if ($BaselineRunIds -contains [string]$run.id) { continue }
                if ($run.status -in @('DIAGNOSED', 'INCONCLUSIVE', 'FAILED')) {
                    if ($run.status -ne 'DIAGNOSED' -or $run.conclusion.category -ne $ExpectedCategory) {
                        throw "本轮诊断 Run 已结束但未闭环：alert=$AlertName, run=$($run.id), status=$($run.status), category=$($run.conclusion.category)"
                    }
                    return $run
                }
            }
        }
        Start-Sleep -Seconds 3
    } while ((Get-Date) -lt $deadline)
    throw "在限定时间内没有发现告警 '$AlertName' 对应的新诊断 Run。"
}

# 这些名字与 docker-compose.yml 中的固定容器名一一对应，避免误操作到其他项目。
$backendContainer = 'merchantflow-backend'
$rocketMqBrokerContainer = 'merchantflow-rocketmq-broker'

function Invoke-ToxiproxyJson {
    <#
    .SYNOPSIS
    调用本地 Toxiproxy HTTP API，并统一处理 JSON 请求体。

    .DESCRIPTION
    所有网络故障实验都必须经过 Toxiproxy API，不能伪造指标或直接修改业务数据。
    下一步：业务服务通过代理端口访问依赖，Prometheus、Loki 和 Trace 会记录真实结果。
    #>
    param(
        [Parameter(Mandatory = $true)]
        [ValidateSet('Get', 'Post', 'Delete')]
        [string]$Method,
        [Parameter(Mandatory = $true)]
        [string]$Uri,
        [hashtable]$Body
    )

    # Toxiproxy 2.12 会拒绝部分 PowerShell 默认 User-Agent，显式设置固定客户端标识。
    # 下一步：只有 API 请求成功后，脚本才会进入故障持续计时和自动恢复阶段。
    if ($null -eq $Body) {
        return Invoke-RestMethod -Method $Method -Uri $Uri -UserAgent 'aiops-lab-runner/1.0'
    }

    $json = $Body | ConvertTo-Json -Depth 6
    return Invoke-RestMethod -Method $Method -Uri $Uri -UserAgent 'aiops-lab-runner/1.0' -ContentType 'application/json' -Body $json
}

function Assert-ToxiproxyAvailable {
    <# 确认实验依赖的 Toxiproxy API 可用；失败时不执行任何故障注入。 #>
    try {
        $null = Invoke-ToxiproxyJson -Method Get -Uri "$proxyApi/version"
    }
    catch {
        throw "Toxiproxy 不可用：$proxyApi。请先启动 MerchantFlow 的 lab Compose 覆盖层。原始错误：$($_.Exception.Message)"
    }
}

function Assert-ProxyExists {
    <# 确认代理已经由 proxy-init 创建，避免向不存在的代理注入故障。 #>
    param([Parameter(Mandatory = $true)][string]$ProxyName)

    try {
        $null = Invoke-ToxiproxyJson -Method Get -Uri "$proxyApi/proxies/$ProxyName"
    }
    catch {
        throw "Toxiproxy 代理 '$ProxyName' 不存在。请确认 lab 覆盖层已完成 proxy-init。"
    }
}

function Set-ProxyEnabled {
    <# 启用或禁用指定依赖代理，禁用后业务会收到真实连接失败。 #>
    param(
        [Parameter(Mandatory = $true)][string]$ProxyName,
        [Parameter(Mandatory = $true)][bool]$Enabled
    )

    # Toxiproxy 更新代理时要求保留 listen/upstream；只提交 name/enabled
    # 在部分版本中会被当作不完整的代理定义，导致“看似成功但状态未更新”。
    $proxy = Invoke-ToxiproxyJson -Method Get -Uri "$proxyApi/proxies/$ProxyName"
    $body = @{
        name = $proxy.name
        listen = $proxy.listen
        upstream = $proxy.upstream
        enabled = $Enabled
    }
    $null = Invoke-ToxiproxyJson -Method Post -Uri "$proxyApi/proxies/$ProxyName" -Body $body
}

function Remove-ToxicIfPresent {
    <# 幂等地删除实验毒化规则，保证重复执行不会因为 409/已存在而失败。 #>
    param(
        [Parameter(Mandatory = $true)][string]$ProxyName,
        [Parameter(Mandatory = $true)][string]$ToxicName
    )

    try {
        $null = Invoke-ToxiproxyJson -Method Delete -Uri "$proxyApi/proxies/$ProxyName/toxics/$ToxicName"
    }
    catch {
        $statusCode = $null
        if ($null -ne $_.Exception.Response) {
            $statusCode = $_.Exception.Response.StatusCode.value__
        }
        if ($statusCode -ne 404) {
            throw
        }
    }
}

function Add-LatencyToxic {
    <# 给指定代理增加真实下游延迟；延迟会在 finally 中自动删除。 #>
    param(
        [Parameter(Mandatory = $true)][string]$ProxyName,
        [Parameter(Mandatory = $true)][string]$ToxicName
    )

    Remove-ToxicIfPresent -ProxyName $ProxyName -ToxicName $ToxicName
    $body = @{
        name = $ToxicName
        type = 'latency'
        stream = 'downstream'
        toxicity = 1.0
        attributes = @{ latency = $LatencyMs; jitter = 100 }
    }
    $null = Invoke-ToxiproxyJson -Method Post -Uri "$proxyApi/proxies/$ProxyName/toxics" -Body $body
}

function Get-ContainerStatus {
    <# 读取固定容器的状态；不使用模糊匹配，避免误操作其他容器。 #>
    param([Parameter(Mandatory = $true)][string]$ContainerName)

    $status = docker inspect --format '{{.State.Status}}' $ContainerName 2>$null
    if ($LASTEXITCODE -ne 0) {
        throw "容器 '$ContainerName' 不存在。请先启动 MerchantFlow Compose。"
    }
    return $status.Trim()
}

function Stop-ContainerForScenario {
    <# 停止指定业务容器以制造真实健康检查异常，并记录后续需要恢复的对象。 #>
    param([Parameter(Mandatory = $true)][string]$ContainerName)

    $status = Get-ContainerStatus -ContainerName $ContainerName
    if ($status -ne 'running') {
        throw "容器 '$ContainerName' 当前状态为 '$status'，不能作为本次实验的运行基线。"
    }
    docker stop $ContainerName | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "停止容器 '$ContainerName' 失败。"
    }
}

function Start-ContainerIfNeeded {
    <# 恢复固定容器；已运行时保持不变，避免产生无意义重启。 #>
    param([Parameter(Mandatory = $true)][string]$ContainerName)

    $status = Get-ContainerStatus -ContainerName $ContainerName
    if ($status -eq 'running') {
        return
    }
    docker start $ContainerName | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "恢复容器 '$ContainerName' 失败。"
    }
}

function Wait-ContainerHealthy {
    <# 等待带 Docker healthcheck 的固定容器恢复，避免 Running 但尚未可服务。 #>
    param(
        [Parameter(Mandatory = $true)][string]$ContainerName,
        [ValidateRange(10, 300)][int]$TimeoutSeconds = 180
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $health = docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' $ContainerName 2>$null
        if ($LASTEXITCODE -eq 0 -and $health.Trim() -eq 'healthy') { return }
        Start-Sleep -Seconds 3
    } while ((Get-Date) -lt $deadline)
    throw "容器 '$ContainerName' 在 ${TimeoutSeconds}s 内没有恢复 healthy。"
}

function Wait-BackendHealthy {
    <# 等待固定 backend 容器真正 healthy，不能把 Started 当成业务可用。 #>
    for ($attempt = 0; $attempt -lt 90; $attempt++) {
        $health = docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' $backendContainer 2>$null
        if ($LASTEXITCODE -eq 0 -and $health.Trim() -eq 'healthy') { return }
        if ($LASTEXITCODE -eq 0 -and ($health.Trim() -eq 'exited' -or $health.Trim() -eq 'unhealthy')) {
            throw "MerchantFlow 后端健康检查失败，当前状态为 '$($health.Trim())'。"
        }
        Start-Sleep -Seconds 2
    }
    throw 'MerchantFlow 后端在 180 秒内没有进入 healthy 状态。'
}

function Start-LabBackend {
    <#
    让 MerchantFlow 通过 Toxiproxy 的实验覆盖层访问 MySQL/Redis。
    如果仍使用基础 Compose 的直连配置，修改 Toxiproxy 不会影响业务，实验会失去真实性。
    下一步：注入延迟或断网后，必须同时启动对应的 k6 真实请求探针。
    #>
    docker compose -f docker-compose.yml -f docker-compose.lab.yml --profile lab up -d backend
    if ($LASTEXITCODE -ne 0) {
        throw 'MerchantFlow Lab 覆盖层启动失败，已停止本次故障实验。'
    }
    # docker compose 的 Started 只代表进程容器已创建，Spring Boot 仍可能需要几十秒启动。
    # 等待健康检查通过后再注入故障，避免把“启动窗口连接拒绝”误当成依赖故障。
    # MerchantFlow 首次启动可能需要接近两分钟；实验编排再额外留出 60 秒，
    # 避免“应用已经最终 healthy，但脚本先超时”的假失败。下一步才进入 Toxiproxy 注入。
    Wait-BackendHealthy
}

function Restore-Toxiproxy {
    <# 恢复 Redis/MySQL 代理和所有本脚本创建的延迟规则。 #>
    Assert-ToxiproxyAvailable
    foreach ($proxyName in @('redis', 'mysql')) {
        Assert-ProxyExists -ProxyName $proxyName
        Remove-ToxicIfPresent -ProxyName $proxyName -ToxicName 'latency_downstream'
        Remove-ToxicIfPresent -ProxyName $proxyName -ToxicName 'mysql_latency_downstream'
        Set-ProxyEnabled -ProxyName $proxyName -Enabled $true
    }
}

function Restore-All {
    <# 幂等恢复所有实验对象；只触碰本项目明确命名的代理和容器。 #>
    Restore-Toxiproxy
    Start-ContainerIfNeeded -ContainerName $rocketMqBrokerContainer
    # 实验可能使用了 lab Compose 覆盖层；恢复时显式切回基础 Compose，避免业务继续走代理端口。
    docker compose -f docker-compose.yml up -d --force-recreate backend nginx
    if ($LASTEXITCODE -ne 0) {
        throw 'MerchantFlow 基础 Compose 恢复失败。'
    }
    Wait-BackendHealthy
    Write-Host '已恢复 Redis、MySQL、RocketMQ 和 MerchantFlow 后端实验状态。'
}

function Invoke-TimedFault {
    <# 执行故障注入并在任何异常或正常结束时恢复，避免实验遗留故障。 #>
    param(
        [Parameter(Mandatory = $true)][scriptblock]$Apply,
        [Parameter(Mandatory = $true)][scriptblock]$Restore,
        [Parameter(Mandatory = $true)][string]$Description
    )

    try {
        & $Apply
        Write-Host "$Description，持续 $DurationSeconds 秒。"
        Start-Sleep -Seconds $DurationSeconds
    }
    finally {
        & $Restore
        Write-Host '实验已自动恢复。下一步：到 AIOps 页面查看告警、证据链和诊断 Run。'
    }
}

switch ($Scenario) {
    'restore' {
        Assert-LabMode
        Restore-All
        break
    }
    'cpu-saturation' {
        Assert-LabMode
        if ($DurationSeconds -lt 120) {
            throw 'CPU 场景至少需要 120 秒，才能覆盖抓取间隔和 HighCPU 的 1 分钟持续时间。'
        }
        $accessToken = Get-AIOpsAccessToken
        $baselineRunIds = @(Get-DiagnosisRunIds -AccessToken $accessToken -AlertName 'MerchantFlowHighCPU')
        $k6Container = 'merchantflow-k6-cpu'
        try {
            Start-LabBackend
            $existing = docker ps -a --filter "name=^/${k6Container}$" --format '{{.Names}}'
            if (-not [string]::IsNullOrWhiteSpace($existing)) {
                throw "实验容器 '$k6Container' 已存在，请先确认它不在运行后再处理。"
            }
            # 后台运行 k6，脚本在负载持续期间同步等待 firing 和新 Run，不能等压测结束后再检查。
            docker compose -f docker-compose.yml -f docker-compose.lab.yml --profile lab run -d `
                --name $k6Container -e "K6_DURATION=${DurationSeconds}s" k6 | Out-Null
            if ($LASTEXITCODE -ne 0) { throw 'CPU 压力探针启动失败。' }

            $null = Wait-PrometheusAlertState -AlertName 'MerchantFlowHighCPU' -ShouldFire $true -TimeoutSeconds 180
            $run = Wait-NewDiagnosis -AccessToken $accessToken -BaselineRunIds $baselineRunIds `
                -AlertName 'MerchantFlowHighCPU' -ExpectedCategory 'CPU_SATURATION' -TimeoutSeconds 240
            Write-Host "CPU 诊断闭环成功：run=$($run.id), category=$($run.conclusion.category)。"
        }
        finally {
            $containerId = docker ps -a --filter "name=^/${k6Container}$" --format '{{.ID}}'
            if (-not [string]::IsNullOrWhiteSpace($containerId)) {
                docker rm -f $k6Container | Out-Null
            }
            Restore-All
            $null = Wait-PrometheusAlertState -AlertName 'MerchantFlowHighCPU' -ShouldFire $false -TimeoutSeconds 180
            $business = Invoke-RestMethod -Method Get -Uri 'http://localhost:8080/api/shop-type/list'
            if (-not $business.success) { throw '恢复后 MerchantFlow 业务接口未成功。' }
            Write-Host 'CPU 压力实验已恢复：告警不再 firing，基础业务接口正常。'
        }
        break
    }
    'redis-latency' {
        Assert-LabMode
        Assert-ToxiproxyAvailable
        Assert-ProxyExists -ProxyName 'redis'
        Invoke-TimedFault `
            -Description "Redis 下游增加 ${LatencyMs}ms 延迟" `
            -Apply {
                Start-LabBackend
                Set-ProxyEnabled -ProxyName 'redis' -Enabled $true
                Add-LatencyToxic -ProxyName 'redis' -ToxicName 'latency_downstream'
            } `
            # 不仅恢复代理，还必须切回基础 Compose；否则下一轮测试会继承 Lab 的 toxiproxy 地址。
            -Restore { Restore-All }
        break
    }
    'redis-cut' {
        Assert-LabMode
        Assert-ToxiproxyAvailable
        Assert-ProxyExists -ProxyName 'redis'
        Invoke-TimedFault `
            -Description 'Redis 代理已禁用，MerchantFlow 将收到真实连接失败' `
            -Apply {
                Start-LabBackend
                Remove-ToxicIfPresent -ProxyName 'redis' -ToxicName 'latency_downstream'
                Set-ProxyEnabled -ProxyName 'redis' -Enabled $false
            } `
            -Restore { Restore-All }
        break
    }
    'mysql-latency' {
        Assert-LabMode
        Assert-ToxiproxyAvailable
        Assert-ProxyExists -ProxyName 'mysql'
        Invoke-TimedFault `
            -Description "MySQL 下游增加 ${LatencyMs}ms 延迟" `
            -Apply {
                Start-LabBackend
                Set-ProxyEnabled -ProxyName 'mysql' -Enabled $true
                Add-LatencyToxic -ProxyName 'mysql' -ToxicName 'mysql_latency_downstream'
            } `
            -Restore { Restore-All }
        break
    }
    'mysql-cut' {
        Assert-LabMode
        Assert-ToxiproxyAvailable
        Assert-ProxyExists -ProxyName 'mysql'
        Invoke-TimedFault `
            -Description 'MySQL 代理已禁用，MerchantFlow 将收到真实数据库连接失败' `
            -Apply {
                Start-LabBackend
                Remove-ToxicIfPresent -ProxyName 'mysql' -ToxicName 'mysql_latency_downstream'
                Set-ProxyEnabled -ProxyName 'mysql' -Enabled $false
            } `
            -Restore { Restore-All }
        break
    }
    'rocketmq-cut' {
        Assert-LabMode
        $accessToken = Get-AIOpsAccessToken
        $alertName = 'MerchantFlowRocketMQPublishFailure'
        $voucherId = $null
        $baselineRunIds = @(Get-DiagnosisRunIds -AccessToken $accessToken -AlertName $alertName)
        $prepareScript = Join-Path $PSScriptRoot 'prepare-rocketmq.ps1'
        $demoVoucherScript = Join-Path $PSScriptRoot 'prepare-demo-voucher.ps1'
        try {
            # 每轮刷新同一条无历史订单的演示券有效期，再以 MySQL 为事实源准备 Redis。
            # 不再依赖固定 voucherId，也不会误用已经过期的历史初始化数据。
            # 下一步：停止 Broker 后由 k6 走真实登录、Lua 预占和 Producer 发送链路。
            $voucherId = [int](& $demoVoucherScript -Stock 20 -DurationMinutes 60)
            & $prepareScript -VoucherId $voucherId -ClearUnpersistedReservation
            Wait-MqCounterBaseline -TimeoutSeconds 90
            Stop-ContainerForScenario -ContainerName $rocketMqBrokerContainer
            # --no-deps 是故障实验的关键边界：k6 只能发请求，不能把已停止的 Broker 自动拉起。
            docker compose -f docker-compose.yml -f docker-compose.lab.yml --profile lab run --rm --no-deps `
                -e "VOUCHER_ID=$voucherId" k6 run /scripts/rocketmq-probe.js
            if ($LASTEXITCODE -ne 0) { throw 'RocketMQ 业务探针执行失败。' }

            $null = Wait-PrometheusAlertState -AlertName $alertName -ShouldFire $true -TimeoutSeconds 120
            $run = Wait-NewDiagnosis -AccessToken $accessToken -BaselineRunIds $baselineRunIds `
                -AlertName $alertName -ExpectedCategory 'ROCKETMQ_FAILURE' -TimeoutSeconds 240
            Write-Host "RocketMQ 诊断闭环成功：run=$($run.id), category=$($run.conclusion.category)。"
        }
        finally {
            Start-ContainerIfNeeded -ContainerName $rocketMqBrokerContainer
            Wait-ContainerHealthy -ContainerName $rocketMqBrokerContainer -TimeoutSeconds 180
            # VerifyOnly 不修改库存，用它证明发送失败后的 Lua 回滚没有留下库存差异或一人一单标记。
            if ($null -ne $voucherId) {
                & $prepareScript -VoucherId $voucherId -VerifyOnly
            }
            # Counter 的 5 分钟 increase 窗口会自然保留故障事实；等待窗口滑出后再要求 resolved。
            $null = Wait-PrometheusAlertState -AlertName $alertName -ShouldFire $false -TimeoutSeconds 420
            Write-Host 'RocketMQ 实验已恢复：Broker healthy，库存与预占一致，告警不再 firing。'
        }
        break
    }
    'http-health-down' {
        Assert-LabMode
        Invoke-TimedFault `
            -Description 'MerchantFlow Backend 已停止，健康检查和 Nginx 代理请求将真实异常' `
            -Apply { Stop-ContainerForScenario -ContainerName $backendContainer } `
            -Restore { Start-ContainerIfNeeded -ContainerName $backendContainer }
        break
    }
}
