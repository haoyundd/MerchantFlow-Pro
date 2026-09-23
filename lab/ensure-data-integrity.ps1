$ErrorActionPreference = 'Stop'

# 为已经存在的数据卷补齐订单唯一约束；脚本只加索引，不删除或修改订单数据。
# 下一步：约束生效后再执行 prepare-demo-voucher.ps1 和秒杀端到端验证。
$projectRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $projectRoot '.env'
$mysqlContainer = 'merchantflow-mysql'

function Get-DotEnvValue {
    param([Parameter(Mandatory = $true)][string]$Name)
    $line = Select-String -LiteralPath $envFile -Pattern ("^" + [regex]::Escape($Name) + '=') |
        Select-Object -First 1 |
        ForEach-Object { $_.Line }
    if (-not $line) { throw ".env 缺少 $Name 配置。" }
    return (($line -split '=', 2)[1]).Trim().Trim('"')
}

function Invoke-MySqlScalar {
    param([Parameter(Mandatory = $true)][string]$Query, [Parameter(Mandatory = $true)][string]$Password)
    $oldPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $output = @(& docker exec $mysqlContainer mysql -uroot "-p$Password" --batch --skip-column-names -e $Query 2>&1)
    $exitCode = $LASTEXITCODE
    $ErrorActionPreference = $oldPreference
    if ($exitCode -ne 0) { throw '读取 MerchantFlow MySQL 失败。' }
    return ($output | Where-Object { $_.ToString() -notmatch 'Using a password' } | Select-Object -First 1).ToString().Trim()
}

$mysqlPassword = Get-DotEnvValue -Name 'MYSQL_PASSWORD'
$duplicateCount = Invoke-MySqlScalar -Password $mysqlPassword -Query @'
SELECT COUNT(*) FROM (
  SELECT user_id, voucher_id FROM hmdp.tb_voucher_order
  GROUP BY user_id, voucher_id HAVING COUNT(*) > 1
) duplicate_groups;
'@
if ($duplicateCount -ne '0') {
    throw "发现 $duplicateCount 组重复订单，拒绝自动添加唯一索引，请先人工确认。"
}

$indexCount = Invoke-MySqlScalar -Password $mysqlPassword -Query @'
SELECT COUNT(*) FROM information_schema.statistics
WHERE table_schema='hmdp' AND table_name='tb_voucher_order'
  AND index_name='uk_voucher_order_user_voucher';
'@
if ($indexCount -eq '0') {
    [void](Invoke-MySqlScalar -Password $mysqlPassword -Query 'ALTER TABLE hmdp.tb_voucher_order ADD UNIQUE KEY uk_voucher_order_user_voucher (user_id, voucher_id); SELECT 1;')
    Write-Host '已为现有数据卷补齐订单唯一约束。'
} else {
    Write-Host '订单唯一约束已存在，无需重复添加。'
}
