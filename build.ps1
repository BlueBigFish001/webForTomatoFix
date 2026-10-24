# Web For Tomato 3.0.0 · 一键构建脚本
# 用法：powershell -ExecutionPolicy Bypass -File .\build.ps1
$ErrorActionPreference = 'Stop'

$root   = Split-Path -Parent $MyInvocation.MyCommand.Path
$proj   = Join-Path $root 'Tomato-Novel-Downloader-main\Android\TomatoWeb'
$dist   = Join-Path $root 'dist'
$apk    = Join-Path $proj 'app\build\outputs\apk\release\app-release.apk'

# —— 环境（按本机实际情况修改）——
$env:JAVA_HOME       = 'D:\Program Files\Java\jdk-17'
$env:ANDROID_HOME    = 'F:\ai\android-sdk'
$env:ANDROID_SDK_ROOT= 'F:\ai\android-sdk'
$env:GRADLE_USER_HOME= 'F:\ai\gradle-home'
$env:PATH            = "$env:JAVA_HOME\bin;$env:PATH"

$ks = Join-Path $root 'release.jks'
if (Test-Path $ks) {
    $env:TOMATO_KS_FILE  = $ks
    $env:TOMATO_KS_PASS  = 'Tomato2024!'
    $env:TOMATO_KS_ALIAS = 'tomato'
} else {
    Write-Warning '未找到 release.jks，release 产物将是未签名 APK'
}

$gradle = 'F:\ai\gradle-8.11.1\bin\gradle.bat'
if (-not (Test-Path $gradle)) { throw "找不到 Gradle: $gradle" }

# 内置内核检查：assets 里必须有两份二进制，否则装到手机上也起不来
$assets = Join-Path $proj 'app\src\main\assets\tomato'
foreach ($f in @('TomatoNovelDownloader-Android_arm64','TomatoNovelDownloader-Android_arm32')) {
    if (-not (Test-Path (Join-Path $assets $f))) { throw "缺少内置内核：$f" }
}

& $gradle -p $proj --no-daemon assembleRelease
if ($LASTEXITCODE -ne 0) { throw "构建失败" }

if (-not (Test-Path $apk)) { throw "未生成 APK：$apk" }
New-Item -ItemType Directory -Force -Path $dist | Out-Null
$out = Join-Path $dist 'WebForTomato-3.2.3.apk'
Copy-Item $apk $out -Force
"APK: $out ({0:N1} MB)" -f ((Get-Item $out).Length / 1MB)

# 校验：签名 + 包信息
& (Join-Path $env:ANDROID_HOME 'build-tools\35.0.0\apksigner.bat') verify $out
& (Join-Path $env:ANDROID_HOME 'build-tools\35.0.0\aapt2.exe') dump badging $out |
    Select-String -Pattern "package:|targetSdkVersion" | ForEach-Object { $_.Line }
"完成。"
