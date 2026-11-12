# 启动速卖商城微服务集群（本地开发环境）
# 前置：MySQL / Redis / Elasticsearch / RabbitMQ / Nacos(8848) 已启动
$ErrorActionPreference = "Continue"
$JAVA = "E:\jdk11\jdk-11.0.27_windows-x64_bin\jdk-11.0.27\bin\java.exe"
$ROOT = Split-Path -Parent $PSScriptRoot
$LOG  = "E:\hmall\tools\logs"
if (-not (Test-Path -LiteralPath $LOG)) { New-Item -ItemType Directory -Force -Path $LOG | Out-Null }

$services = @("sm-user", "sm-item", "sm-trade", "sm-pay", "sm-gateway")
foreach ($svc in $services) {
    $jar = Join-Path $ROOT "$svc\target\$svc.jar"
    if (-not (Test-Path -LiteralPath $jar)) {
        Write-Host "[skip] $svc 未打包：$jar"
        continue
    }
    $out = Join-Path $LOG "$svc.out.log"
    $err = Join-Path $LOG "$svc.err.log"
    $p = Start-Process -FilePath $JAVA -ArgumentList "-jar", "`"$jar`"" -WorkingDirectory $ROOT `
        -RedirectStandardOutput $out -RedirectStandardError $err -WindowStyle Hidden -PassThru
    Write-Host "[start] $svc pid=$($p.Id) log=$out"
    Start-Sleep -Seconds 12
}
Write-Host "全部启动完成，等待注册到 Nacos：http://127.0.0.1:8848/nacos"
