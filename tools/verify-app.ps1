param(
    [switch]$IncludeEmulators,
    [switch]$IncludeRelease,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$JavaTempDir = (Join-Path $env:SystemDrive 'vl-temp')
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
$previousLocation = Get-Location
$previousEnvironment = @{}
foreach ($key in @('JAVA_HOME', 'JAVA_OPTS', 'JAVA_TOOL_OPTIONS', 'TEMP', 'TMP', 'PATH')) {
    $previousEnvironment[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
}

function Invoke-Check([string]$Command, [string[]]$CheckArguments) {
    & $Command @CheckArguments
    if ($LASTEXITCODE -ne 0) { throw "Verification failed: $Command $($CheckArguments -join ' ')" }
}

try {
    Set-Location -LiteralPath $repoRoot
    if (-not $JavaHome) {
        $studioJava = Join-Path $env:ProgramFiles 'Android/Android Studio/jbr'
        if (Test-Path -LiteralPath "$studioJava/bin/java.exe") { $JavaHome = $studioJava }
    }
    if (-not (Test-Path -LiteralPath "$JavaHome/bin/java.exe")) {
        throw 'Set JAVA_HOME or pass -JavaHome with the Android Studio JDK path.'
    }
    New-Item -ItemType Directory -Path $JavaTempDir -Force | Out-Null
    $env:JAVA_HOME = $JavaHome
    $env:PATH = "$JavaHome/bin;$env:PATH"
    $env:TEMP = $JavaTempDir
    $env:TMP = $JavaTempDir
    # Short paths avoid Windows UnixDomainSockets loopback failures in Java NIO.
    $env:JAVA_OPTS = "$env:JAVA_OPTS -Djava.io.tmpdir=$JavaTempDir"
    $env:JAVA_TOOL_OPTIONS = "$env:JAVA_TOOL_OPTIONS -Djava.io.tmpdir=$JavaTempDir"

    Invoke-Check 'npm.cmd' @('--prefix', 'coach-web', 'run', 'build')
    Invoke-Check 'npm.cmd' @('--prefix', 'functions', 'test')
    foreach ($module in Get-ChildItem -LiteralPath 'functions' -Filter '*.mjs') {
        Invoke-Check 'node' @('--check', $module.FullName)
    }
    Invoke-Check 'python' @('tools/pose-pipeline/test_parity.py')
    Invoke-Check 'python' @('tools/pose-pipeline/test_jump_baseline.py')
    Invoke-Check 'python' @('-m', 'unittest', 'discover', '-s', 'tools/pose-pipeline', '-p', 'test_evaluate_mpjpe.py')

    if ($IncludeEmulators) {
        $firebase = Get-Command 'firebase.cmd' -ErrorAction SilentlyContinue
        if (-not $firebase) { throw 'Firebase CLI is required for -IncludeEmulators. Install it and retry.' }
        Invoke-Check $firebase.Source @('emulators:exec', '--config', 'firebase.storage-test.json',
            '--project', 'demo-volleyball-lab', '--only', 'firestore,storage', 'npm --prefix functions run test:rules')
    }
    $gradleTasks = @(':app:testDebugUnitTest', ':app:lintDebug', ':app:assembleDebug')
    if ($IncludeRelease) { $gradleTasks += ':app:assembleRelease' }
    Invoke-Check './gradlew.bat' ($gradleTasks + @('--console=plain', '--max-workers=2'))
    Write-Host 'All requested verification checks passed.'
} finally {
    foreach ($key in $previousEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($key, $previousEnvironment[$key], 'Process')
    }
    Set-Location -LiteralPath $previousLocation
}
