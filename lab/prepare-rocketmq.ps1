param(
    [ValidateRange(1, 999999)]
    [int]$VoucherId = 8,
    [string]$LoginAccount = 'admin',
    [switch]$ClearUnpersistedReservation,
    [switch]$VerifyOnly
)

$ErrorActionPreference = 'Stop'

# 这个脚本只准备 lab 所需的 Redis 秒杀缓存，不修改 Java 业务源码，也不删除任何数据卷。
# 下一步：准备完成后再启动 rocketmq-cut，并用 k6 真实调用秒杀接口进入消息发送路径。
$projectRoot = Split-Path -Parent $PSScriptRoot
$script:envFile = Join-Path $projectRoot '.env'
$mysqlContainer = 'merchantflow-mysql'
$redisContainer = 'merchantflow-redis'

function Get-DotEnvValue {
    <# 从本地 .env 读取指定配置；不会把密码写入输出。 #>
    param([Parameter(Mandatory = $true)][string]$Name)

    if (-not (Test-Path -LiteralPath $script:envFile)) {
        throw "未找到 $script:envFile，请先创建 MerchantFlow 本地环境配置。"
    }
    # 用 Select-String 读取整行，兼容 .env 首行可能带 UTF-8 BOM 的 Windows PowerShell 读取结果。
    $line = Select-String -LiteralPath $script:envFile -Pattern ("^" + [regex]::Escape($Name) + "=") |
        Select-Object -First 1 |
        ForEach-Object { $_.Line }
    if (-not $line) {
        throw ".env 缺少 $Name 配置。"
    }
    $value = ($line -split '=', 2)[1].Trim()
    if ($value.Length -ge 2 -and $value.StartsWith('"') -and $value.EndsWith('"')) {
        $value = $value.Substring(1, $value.Length - 2)
    }
    return $value
}

function Assert-ContainerRunning {
    <# 确认数据库和 Redis 都处于运行状态，避免准备脚本误报业务数据问题。 #>
    param([Parameter(Mandatory = $true)][string]$ContainerName)

    $status = docker inspect --format '{{.State.Status}}' $ContainerName 2>$null
    if ($LASTEXITCODE -ne 0 -or $status.Trim() -ne 'running') {
        throw "容器 '$ContainerName' 未运行，请先启动 MerchantFlow lab Compose。"
    }
}

function Invoke-MySqlScalar {
    <# 读取指定优惠券或用户的单个数据库字段，作为 lab 前置条件的事实来源。 #>
    param(
        [Parameter(Mandatory = $true)][string]$Query,
        [Parameter(Mandatory = $true)][string]$Password
    )

    # mysql 客户端会把“命令行密码不安全”写到 stderr；捕获后过滤提示，避免 PowerShell Stop 模式把成功查询当异常。
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $output = @(& docker exec $mysqlContainer mysql -uroot "-p$Password" --batch --skip-column-names -e $Query 2>&1)
    $ErrorActionPreference = $previousPreference
    if ($LASTEXITCODE -ne 0) {
        throw '读取 MerchantFlow MySQL lab 数据失败。'
    }
    $value = $output |
        Where-Object { $_.ToString() -notmatch 'Using a password' } |
        Select-Object -First 1
    return $value.ToString().Trim()
}

$mysqlPassword = Get-DotEnvValue -Name 'MYSQL_PASSWORD'
$redisPassword = Get-DotEnvValue -Name 'REDIS_PASSWORD'
Assert-ContainerRunning -ContainerName $mysqlContainer
Assert-ContainerRunning -ContainerName $redisContainer

# 以 MySQL 中的真实库存为准恢复 Redis 缓存，避免 Lua 因缺少 key 在进入 RocketMQ 前失败。
$stock = Invoke-MySqlScalar -Query "SELECT stock FROM hmdp.tb_seckill_voucher WHERE voucher_id = $VoucherId;" -Password $mysqlPassword
if ($stock -notmatch '^\d+$') {
    throw "优惠券 $VoucherId 不存在或 MySQL 库存不是整数。"
}

$userId = Invoke-MySqlScalar -Query "SELECT id FROM hmdp.tb_user WHERE phone = '$LoginAccount' LIMIT 1;" -Password $mysqlPassword
if ($userId -notmatch '^\d+$') {
    throw "登录账号 '$LoginAccount' 不存在，无法确认一人一单前置条件。"
}

# 实验账号必须没有该券的数据库订单；否则请求会命中一人一单约束，无法进入本轮 Producer 故障路径。
$orderCount = Invoke-MySqlScalar -Query "SELECT COUNT(*) FROM hmdp.tb_voucher_order WHERE user_id = $userId AND voucher_id = $VoucherId;" -Password $mysqlPassword
if ($orderCount -ne '0') {
    throw "MySQL 已存在账号 '$LoginAccount' 的优惠券 $VoucherId 订单，拒绝复用该数据制造故障。"
}

$orderKey = "seckill:order:$VoucherId"
$previousPreference = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
$memberOutput = @(& docker exec $redisContainer redis-cli -a "$redisPassword" SISMEMBER $orderKey $userId 2>&1)
$ErrorActionPreference = $previousPreference
if ($LASTEXITCODE -ne 0) {
    throw '检查 Redis 秒杀订单集合失败。'
}
$member = ($memberOutput |
    Where-Object { $_.ToString() -notmatch 'Using a password' } |
    Select-Object -First 1).ToString().Trim()
if ($member -eq '1') {
    if (-not $ClearUnpersistedReservation) {
        throw "账号 '$LoginAccount' 已经占用优惠券 $VoucherId 的 Redis 一人一单标记；如确认是 Broker 故障留下的未落库预留，请显式传入 -ClearUnpersistedReservation。"
    }
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $removeOutput = @(& docker exec $redisContainer redis-cli -a "$redisPassword" SREM $orderKey $userId 2>&1)
    $ErrorActionPreference = $previousPreference
    if ($LASTEXITCODE -ne 0) {
        throw '清理未落库的 Redis lab 预留失败。'
    }
    Write-Host "已清理账号 '$LoginAccount' 的未落库 lab 预留；MySQL 订单数为 0。"
}

$stockKey = "seckill:stock:$VoucherId"
if (-not $VerifyOnly) {
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $setOutput = @(& docker exec $redisContainer redis-cli -a "$redisPassword" SET $stockKey $stock 2>&1)
    $ErrorActionPreference = $previousPreference
    if ($LASTEXITCODE -ne 0) {
        throw '写入指定优惠券的 Redis lab 库存失败。'
    }
}
$previousPreference = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
$actualOutput = @(& docker exec $redisContainer redis-cli -a "$redisPassword" GET $stockKey 2>&1)
$ErrorActionPreference = $previousPreference
$actual = ($actualOutput |
    Where-Object { $_.ToString() -notmatch 'Using a password' } |
    Select-Object -First 1).ToString().Trim()
if ($actual -ne $stock) {
    throw "Redis lab 库存校验失败，期望 $stock，实际 $actual。"
}

if ($VerifyOnly) {
    Write-Host "RocketMQ lab 恢复校验通过：voucher=$VoucherId，MySQL/Redis 库存=$actual，无孤立预占。"
}
else {
    Write-Host "RocketMQ lab 前置条件已准备：voucher=$VoucherId，Redis 库存=$actual，账号=$LoginAccount。"
    Write-Host '下一步：启动 rocketmq-cut，由脚本自动执行真实秒杀请求、告警和诊断验收。'
}
