param(
    [Parameter(Mandatory=$true)][string]$Compiler,
    [Parameter(Mandatory=$true)][string]$JdkHeaders,
    [string]$OutputDirectory=(Join-Path $PSScriptRoot '..\.build-cache\spake2-host')
)
$ErrorActionPreference='Stop'
New-Item -ItemType Directory -Force $OutputDirectory | Out-Null
$source=Join-Path $PSScriptRoot 'src\main\cpp'
$library=Join-Path $OutputDirectory 'spake2.dll'
& $Compiler -shared -o $library "-I$JdkHeaders" "-I$(Join-Path $JdkHeaders 'win32')" "-I$(Join-Path $source 'spake2-c\include')" (Join-Path $source 'spake2-c\sha512.c') (Join-Path $source 'spake2-c\spake2.c') (Join-Path $source 'spake2_jni.c')
if ($LASTEXITCODE -ne 0) { throw 'Host JNI build failed' }
$env:SPAKE2_TEST_LIB_DIR=(Resolve-Path -LiteralPath $OutputDirectory).Path
Write-Output "SPAKE2_TEST_LIB_DIR=$env:SPAKE2_TEST_LIB_DIR"
