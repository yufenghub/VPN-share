param(
    [string]$AndroidSdkPath = $env:ANDROID_HOME,
    [string]$JavaPath = $env:JAVA_HOME,
    [Parameter(Mandatory = $true)][string]$GoRootPath,
    [switch]$SkipChecks
)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath (Join-Path $AndroidSdkPath 'platforms\android-35\android.jar'))) {
    throw '请通过 -AndroidSdkPath 指定已安装 Android API 35 和 NDK 28.2.13676358 的 SDK。'
}
if (-not (Test-Path -LiteralPath (Join-Path $JavaPath 'bin\java.exe'))) { throw '请通过 -JavaPath 指定 JDK 17 或更新版本。' }
if (-not (Test-Path -LiteralPath (Join-Path $GoRootPath 'bin\go.exe'))) { throw '请通过 -GoRootPath 指定 Go 1.26.3。' }
$cacheRoot = Join-Path $PSScriptRoot '.build-cache'
$env:JAVA_HOME = $JavaPath
$env:ANDROID_HOME = $AndroidSdkPath
$env:ANDROID_NDK_HOME = Join-Path $AndroidSdkPath 'ndk\28.2.13676358'
$env:ANDROID_USER_HOME = Join-Path $cacheRoot 'android'
New-Item -ItemType Directory -Force $env:ANDROID_USER_HOME | Out-Null
$env:ANDROID_SDK_HOME = $null
$env:GRADLE_USER_HOME = Join-Path $cacheRoot 'gradle'
$env:GOPATH = Join-Path $cacheRoot 'gopath'
$env:GOBIN = Join-Path $env:GOPATH 'bin'
$env:GOMODCACHE = Join-Path $cacheRoot 'go-mod'
$env:GOCACHE = Join-Path $cacheRoot 'go-cache'
$env:GOTMPDIR = Join-Path $cacheRoot 'tmp'
New-Item -ItemType Directory -Force $env:GOTMPDIR | Out-Null
$env:TEMP = $env:GOTMPDIR
$env:TMP = $env:GOTMPDIR
$env:TMPDIR = $env:GOTMPDIR
$env:GOFLAGS = '-p=1'
$env:GOMAXPROCS = '2'
$env:Path = (Join-Path $GoRootPath 'bin') + ';' + $env:GOBIN + ';' + (Join-Path $JavaPath 'bin') + ';' + $env:Path

Push-Location $PSScriptRoot
try {
    $debugKey = Join-Path $cacheRoot 'debug.keystore'
    if (-not (Test-Path -LiteralPath $debugKey)) {
        & (Join-Path $JavaPath 'bin\keytool.exe') -genkeypair -keystore $debugKey -storepass android -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Android Debug'
        if ($LASTEXITCODE -ne 0) { throw '调试签名创建失败。' }
    }
    & go install 'golang.org/x/mobile/cmd/gomobile@v0.0.0-20260803200217-62cee1672c8e'
    if ($LASTEXITCODE -ne 0) { throw 'gomobile 安装失败。' }
    & (Join-Path $env:GOBIN 'gomobile.exe') init
    if ($LASTEXITCODE -ne 0) { throw 'gomobile 初始化失败。' }
    $tasks = @('assembleDebug')
    if (-not $SkipChecks) { $tasks += @(':app:testDebugUnitTest', ':app:lintDebug') }
    & .\gradlew.bat @tasks --no-daemon --console=plain --max-workers=2 '-Pkotlin.compiler.execution.strategy=in-process'
    if ($LASTEXITCODE -ne 0) { throw '构建或检查失败，请查看上方日志。' }
    Write-Output (Join-Path $PSScriptRoot 'app\build\outputs\apk\debug\app-debug.apk')
} finally {
    & .\gradlew.bat --stop --console=plain | Out-Null
    Pop-Location
}
