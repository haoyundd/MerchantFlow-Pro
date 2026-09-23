$ErrorActionPreference = 'Stop'

# 只读检查 MerchantFlow 的 MySQL、Redis 派生缓存和秒杀协调状态，不执行任何修复动作。
# 下一步：如果报告存在 drift，先阅读差异，再执行 Redis 启动校准或对应的实验准备脚本。
$projectRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $projectRoot '.env'
$mysqlContainer = 'merchantflow-mysql'
$redisContainer = 'merchantflow-redis'

function Get-DotEnvValue {
    param([Parameter(Mandatory = $true)][string]$Name)
    $line = Select-String -LiteralPath $envFile -Pattern ("^" + [regex]::Escape($Name) + '=') |
        Select-Object -First 1 |
        ForEach-Object { $_.Line }
    if (-not $line) { throw ".env 缺少 $Name 配置。" }
    return (($line -split '=', 2)[1]).Trim().Trim('"')
}

function Invoke-MySqlQuery {
    param([Parameter(Mandatory = $true)][string]$Query, [Parameter(Mandatory = $true)][string]$Password)
    $oldPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $output = @(& docker exec $mysqlContainer mysql -uroot "-p$Password" --batch --skip-column-names -e $Query 2>&1)
    $exitCode = $LASTEXITCODE
    $ErrorActionPreference = $oldPreference
    if ($exitCode -ne 0) { throw '读取 MerchantFlow MySQL 失败。' }
    return @($output | Where-Object { $_.ToString() -notmatch 'Using a password' })
}

function Invoke-Redis {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    $output = @(& docker exec $redisContainer redis-cli -a $redisPassword --no-auth-warning @Arguments 2>&1)
    if ($LASTEXITCODE -ne 0) { throw '读取 MerchantFlow Redis 失败。' }
    return @($output)
}

$mysqlPassword = Get-DotEnvValue -Name 'MYSQL_PASSWORD'
$redisPassword = Get-DotEnvValue -Name 'REDIS_PASSWORD'

Write-Host '=== MySQL 业务数据 ==='
Invoke-MySqlQuery -Password $mysqlPassword -Query @'
SELECT 'counts' AS section,
  (SELECT COUNT(*) FROM hmdp.tb_user) AS users,
  (SELECT COUNT(*) FROM hmdp.tb_shop_type) AS shop_types,
  (SELECT COUNT(*) FROM hmdp.tb_shop) AS shops,
  (SELECT COUNT(*) FROM hmdp.tb_voucher) AS vouchers,
  (SELECT COUNT(*) FROM hmdp.tb_seckill_voucher) AS seckill_vouchers,
  (SELECT COUNT(*) FROM hmdp.tb_voucher_order) AS orders;
SELECT sv.voucher_id, sv.stock, sv.begin_time, sv.end_time, v.status
FROM hmdp.tb_seckill_voucher sv
LEFT JOIN hmdp.tb_voucher v ON v.id = sv.voucher_id
ORDER BY sv.voucher_id;
'@ | ForEach-Object { Write-Host $_ }

Write-Host '=== Redis 派生缓存 ==='
$typeTtl = (Invoke-Redis TTL cache:shoplist: | Select-Object -First 1).ToString().Trim()
Write-Host "cache:shoplist: ttl=$typeTtl seconds"
foreach ($key in @('shop:geo:1', 'shop:geo:2', 'seckill:stock:4', 'seckill:stock:7', 'seckill:stock:8')) {
    $type = (Invoke-Redis TYPE $key | Select-Object -Last 1).ToString().Trim()
    $ttl = (Invoke-Redis TTL $key | Select-Object -Last 1).ToString().Trim()
    $value = '-'
    if ($type -eq 'string') { $value = (Invoke-Redis GET $key | Select-Object -Last 1).ToString().Trim() }
    if ($type -eq 'zset') { $value = (Invoke-Redis ZCARD $key | Select-Object -Last 1).ToString().Trim() }
    Write-Host "$key type=$type ttl=$ttl value_or_size=$value"
}

Write-Host '=== 秒杀 Redis 预占与 MySQL 订单差异 ==='
$voucherRows = Invoke-MySqlQuery -Password $mysqlPassword -Query 'SELECT voucher_id FROM hmdp.tb_seckill_voucher ORDER BY voucher_id;'
foreach ($row in $voucherRows) {
    $voucherId = $row.ToString().Trim()
    if ($voucherId -notmatch '^\d+$') { continue }
    $members = @(Invoke-Redis SMEMBERS ("seckill:order:" + $voucherId)) |
        Where-Object { $_.ToString().Trim().Length -gt 0 }
    if ($members.Count -eq 0) {
        Write-Host "voucher=$voucherId reservations=0 orphan=0"
        continue
    }
    $orphan = 0
    foreach ($member in $members) {
        $userId = $member.ToString().Trim()
        if ($userId -notmatch '^\d+$') { $orphan++; continue }
        $count = (Invoke-MySqlQuery -Password $mysqlPassword -Query "SELECT COUNT(*) FROM hmdp.tb_voucher_order WHERE user_id=$userId AND voucher_id=$voucherId;" | Select-Object -First 1).ToString().Trim()
        if ($count -eq '0') { $orphan++ }
    }
    Write-Host "voucher=$voucherId reservations=$($members.Count) orphan=$orphan"
}
