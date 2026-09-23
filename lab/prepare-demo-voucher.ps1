param(
    [ValidateRange(1, 999999)]
    [int]$ShopId = 1,
    [ValidateRange(1, 100000)]
    [int]$Stock = 20,
    [ValidateRange(5, 1440)]
    [int]$DurationMinutes = 60,
    [string]$Title = 'AIOps Lab 秒杀演示券'
)

$ErrorActionPreference = 'Stop'

# 这个脚本只创建或刷新一条带实验标识的未来有效秒杀券，不删除历史优惠券和数据卷。
# 下一步：使用脚本输出的 voucherId 运行 k6 秒杀探针或 RocketMQ 故障实验。
$projectRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $projectRoot '.env'
$mysqlContainer = 'merchantflow-mysql'
$redisContainer = 'merchantflow-redis'

function Get-DotEnvValue {
    param([Parameter(Mandatory = $true)][string]$Name)

    if (-not (Test-Path -LiteralPath $envFile)) {
        throw "未找到 $envFile，请先创建 MerchantFlow 本地环境配置。"
    }
    $line = Select-String -LiteralPath $envFile -Pattern ("^" + [regex]::Escape($Name) + '=') |
        Select-Object -First 1 |
        ForEach-Object { $_.Line }
    if (-not $line) {
        throw ".env 缺少 $Name 配置。"
    }
    return (($line -split '=', 2)[1]).Trim().Trim('"')
}

function Assert-ContainerRunning {
    param([Parameter(Mandatory = $true)][string]$ContainerName)

    $status = docker inspect --format '{{.State.Status}}' $ContainerName 2>$null
    if ($LASTEXITCODE -ne 0 -or $status.Trim() -ne 'running') {
        throw "容器 '$ContainerName' 未运行。"
    }
}

function Invoke-MySqlQuery {
    param(
        [Parameter(Mandatory = $true)][string]$Query,
        [Parameter(Mandatory = $true)][string]$Password
    )

    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $output = @(& docker exec $mysqlContainer mysql -uroot "-p$Password" --batch --skip-column-names -e $Query 2>&1)
    $exitCode = $LASTEXITCODE
    $ErrorActionPreference = $previousPreference
    if ($exitCode -ne 0) {
        throw "MerchantFlow MySQL 执行失败：$Query"
    }
    return @($output | Where-Object { $_.ToString() -notmatch 'Using a password' })
}

function Invoke-Redis {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)

    $output = @(& docker exec $redisContainer redis-cli -a $redisPassword --no-auth-warning @Arguments 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw 'MerchantFlow Redis 命令执行失败。'
    }
    return @($output)
}

$mysqlPassword = Get-DotEnvValue -Name 'MYSQL_PASSWORD'
$redisPassword = Get-DotEnvValue -Name 'REDIS_PASSWORD'
Assert-ContainerRunning -ContainerName $mysqlContainer
Assert-ContainerRunning -ContainerName $redisContainer

$safeTitle = $Title.Replace("'", "''")
$findSql = "SELECT id FROM hmdp.tb_voucher WHERE title = '$safeTitle' AND type = 1 ORDER BY id LIMIT 1;"
$existingIdOutput = Invoke-MySqlQuery -Query $findSql -Password $mysqlPassword
$existingId = ''
if ($existingIdOutput.Count -gt 0) {
    $existingId = ($existingIdOutput | Select-Object -First 1).ToString().Trim()
}

$beginTime = (Get-Date).AddMinutes(-1).ToString('yyyy-MM-dd HH:mm:ss')
$endTime = (Get-Date).AddMinutes($DurationMinutes).ToString('yyyy-MM-dd HH:mm:ss')

if ($existingId -match '^\d+$') {
    $orderCountOutput = Invoke-MySqlQuery -Query "SELECT COUNT(*) FROM hmdp.tb_voucher_order WHERE voucher_id = $existingId;" -Password $mysqlPassword
    $orderCount = ($orderCountOutput | Select-Object -First 1).ToString().Trim()
    if ($orderCount -ne '0') {
        throw "演示优惠券 $existingId 已存在订单，为避免覆盖历史实验，拒绝刷新。请更换 -Title。"
    }

    $sql = @"
UPDATE hmdp.tb_voucher
SET shop_id=$ShopId, sub_title='本地故障演练专用', rules='仅用于 MerchantFlow 本地演练', pay_value=1, actual_value=1000, status=1, update_time=NOW()
WHERE id=$existingId;
INSERT INTO hmdp.tb_seckill_voucher (voucher_id, stock, begin_time, end_time, update_time)
VALUES ($existingId, $Stock, '$beginTime', '$endTime', NOW())
ON DUPLICATE KEY UPDATE stock=VALUES(stock), begin_time=VALUES(begin_time), end_time=VALUES(end_time), update_time=NOW();
"@
    [void](Invoke-MySqlQuery -Query $sql -Password $mysqlPassword)
    $voucherId = [int]$existingId
} else {
    $sql = @"
INSERT INTO hmdp.tb_voucher (shop_id, title, sub_title, rules, pay_value, actual_value, type, status, create_time, update_time)
VALUES ($ShopId, '$safeTitle', '本地故障演练专用', '仅用于 MerchantFlow 本地演练', 1, 1000, 1, 1, NOW(), NOW());
SET @voucher_id = LAST_INSERT_ID();
INSERT INTO hmdp.tb_seckill_voucher (voucher_id, stock, begin_time, end_time, update_time)
VALUES (@voucher_id, $Stock, '$beginTime', '$endTime', NOW());
SELECT @voucher_id;
"@
    $createdOutput = Invoke-MySqlQuery -Query $sql -Password $mysqlPassword
    $createdId = ($createdOutput | Where-Object { $_.ToString().Trim() -match '^\d+$' } | Select-Object -Last 1).ToString().Trim()
    if ($createdId -notmatch '^\d+$') {
        throw '创建演示优惠券后没有取得 voucherId。'
    }
    $voucherId = [int]$createdId
}

# 该演示券没有历史订单时，可以安全重建其唯一的 Redis 协调状态。
$orderKey = "seckill:order:$voucherId"
$stockKey = "seckill:stock:$voucherId"
[void](Invoke-Redis DEL $orderKey)
[void](Invoke-Redis SET $stockKey $Stock)
$actualStock = (Invoke-Redis GET $stockKey | Select-Object -First 1).ToString().Trim()
if ($actualStock -ne $Stock.ToString()) {
    throw "演示券 Redis 库存校验失败，期望=$Stock，实际=$actualStock。"
}

Write-Host "演示优惠券已准备：voucherId=$voucherId，shopId=$ShopId，stock=$actualStock，结束时间=$endTime"
Write-Host '下一步：使用该 voucherId 调用 /voucher-order/seckill/{id}，或启动 lab/k6/rocketmq-probe.js。'
# 将 ID 写入成功输出流，故障编排脚本可直接接收，避免假设演示券永远是固定编号。
return $voucherId
