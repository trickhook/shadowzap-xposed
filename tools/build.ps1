<#
.SYNOPSIS
    Runs Gradle for this project with a pinned local toolchain, serialized machine-wide.

.DESCRIPTION
    Sets JAVA_HOME, GRADLE_USER_HOME, TEMP/TMP and GRADLE_OPTS for this process only (never persistently),
    writes local.properties when it is missing, waits for the named mutex Global\shadowzap-gradle so only one
    Gradle build runs at a time, runs gradlew.bat without a daemon and prints a compact summary:
    every error/failure line followed by the last -TailLines lines of output. Exits with Gradle's exit code.

    Paths come from -JavaHome / -SdkDir / -GradleUserHome when given, otherwise from the maintainer's pinned
    locations (D:\Java\jdk-25, D:\Android\Sdk, D:\Gradle) when they exist, otherwise from JAVA_HOME,
    ANDROID_HOME / ANDROID_SDK_ROOT and %USERPROFILE%\.gradle.

    Exit codes: Gradle's own, 2 for bad arguments or a missing toolchain, 124 when the mutex could not be taken
    within -MutexTimeoutMinutes (another build holds it; retry later).

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File tools/build.ps1 -Project . -Tasks ':app:assembleDebug',':app:testDebugUnitTest'
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Project,

    [Parameter(Mandatory = $true, ValueFromRemainingArguments = $true)]
    [string[]]$Tasks,

    [int]$TailLines = 150,

    [int]$MutexTimeoutMinutes = 30,

    [string]$JavaHome = '',

    [string]$GradleUserHome = '',

    [string]$SdkDir = ''
)

$ErrorActionPreference = 'Stop'

# Write-Error would be a terminating error under 'Stop' and turn every documented exit code into 1.
function Exit-WithError([string]$Message, [int]$Code) {
    [Console]::Error.WriteLine($Message)
    exit $Code
}

# The explicit value if given (it must exist), else the first existing candidate, else $null.
function Resolve-Location([string]$Explicit, [string[]]$Candidates, [string]$What) {
    if ($Explicit) {
        if (Test-Path -LiteralPath $Explicit) { return $Explicit }
        Exit-WithError "$What '$Explicit' does not exist." 2
    }
    foreach ($candidate in $Candidates) {
        if ($candidate -and (Test-Path -LiteralPath $candidate)) { return $candidate }
    }
    return $null
}

# Stops a process and all of its descendants.
function Stop-ProcessTree([int]$RootId) {
    $all = @(Get-CimInstance -ClassName Win32_Process -ErrorAction SilentlyContinue)
    $queue = New-Object System.Collections.Generic.Queue[int]
    $queue.Enqueue($RootId)
    $seen = @{}
    while ($queue.Count -gt 0) {
        $id = $queue.Dequeue()
        if ($seen.ContainsKey($id)) { continue }
        $seen[$id] = $true
        foreach ($child in $all | Where-Object { $_.ParentProcessId -eq $id }) { $queue.Enqueue([int]$child.ProcessId) }
    }
    foreach ($id in $seen.Keys) { Stop-Process -Id $id -Force -ErrorAction SilentlyContinue }
}

# A previous holder died without releasing the mutex, but the Gradle it started may still be running. Stop it
# before starting another one on the same caches.
function Stop-AbandonedBuild([string]$PidFile) {
    if (-not (Test-Path -LiteralPath $PidFile)) { return }
    try {
        $recorded = [int](Get-Content -LiteralPath $PidFile -TotalCount 1)
        $process = Get-CimInstance -ClassName Win32_Process -Filter "ProcessId = $recorded" -ErrorAction SilentlyContinue
        # Only a cmd.exe that ran gradlew is ours; the PID may have been reused since.
        if ($process -and $process.Name -eq 'cmd.exe' -and $process.CommandLine -match 'gradlew') {
            Write-Host "Stopping the Gradle build left behind by an abandoned run (cmd PID $recorded)"
            Stop-ProcessTree $recorded
        }
    } catch {
        Write-Host "Could not inspect the abandoned build: $($_.Exception.Message)"
    }
    Remove-Item -LiteralPath $PidFile -Force -ErrorAction SilentlyContinue
}

# Accept "-Tasks a,b", "-Tasks 'a','b'" and "-Tasks a b" alike, however the caller's shell split them.
$taskList = @(
    $Tasks |
        ForEach-Object { $_ -split '[,\s]+' } |
        ForEach-Object { $_.Trim().Trim("'").Trim('"') } |
        Where-Object { $_ -ne '' }
)
if ($taskList.Count -eq 0) {
    Exit-WithError 'No Gradle tasks given.' 2
}

$projectDir = (Resolve-Path -LiteralPath $Project).Path
$gradlew = Join-Path $projectDir 'gradlew.bat'
if (-not (Test-Path -LiteralPath $gradlew)) {
    Exit-WithError "gradlew.bat not found in $projectDir" 2
}

$javaHomeDir = Resolve-Location $JavaHome @('D:\Java\jdk-25', $env:JAVA_HOME) 'JavaHome'
if (-not $javaHomeDir) {
    Exit-WithError 'No JDK found: pass -JavaHome or set JAVA_HOME.' 2
}
$sdkDirectory = Resolve-Location $SdkDir @(
    'D:\Android\Sdk',
    $env:ANDROID_HOME,
    $env:ANDROID_SDK_ROOT,
    $(if ($env:LOCALAPPDATA) { Join-Path $env:LOCALAPPDATA 'Android\Sdk' })
) 'SdkDir'
$gradleHome = Resolve-Location $GradleUserHome @('D:\Gradle') 'GradleUserHome'
if (-not $gradleHome) {
    $gradleHome = Join-Path $env:USERPROFILE '.gradle'
}

$tmpDir = Join-Path $gradleHome 'tmp'
New-Item -ItemType Directory -Force -Path $tmpDir | Out-Null

# Process-scoped environment. Without the temp overrides Gradle fails on some machines with
# "Unable to establish loopback connection".
$env:JAVA_HOME = $javaHomeDir
$env:GRADLE_USER_HOME = $gradleHome
$env:TEMP = $tmpDir
$env:TMP = $tmpDir
$env:GRADLE_OPTS = "-Djdk.net.unixdomain.tmpdir=$tmpDir -Djava.io.tmpdir=$tmpDir"
$env:PATH = (Join-Path $javaHomeDir 'bin') + ';' + $env:PATH

$localProperties = Join-Path $projectDir 'local.properties'
if (-not (Test-Path -LiteralPath $localProperties)) {
    if ($sdkDirectory) {
        $escaped = $sdkDirectory.Replace('\', '\\').Replace(':', '\:')
        [System.IO.File]::WriteAllText($localProperties, "sdk.dir=$escaped`n", [System.Text.Encoding]::ASCII)
        Write-Host "Wrote $localProperties"
    } else {
        Write-Host 'No Android SDK found (pass -SdkDir or set ANDROID_HOME); not writing local.properties.'
    }
}

$mutex = New-Object System.Threading.Mutex($false, 'Global\shadowzap-gradle')
$acquired = $false
$exitCode = 1
$logFile = Join-Path $tmpDir ("shadowzap-build-{0}.log" -f [System.Guid]::NewGuid().ToString('N'))
$pidFile = Join-Path $tmpDir 'shadowzap-gradle.pid'

try {
    try {
        $acquired = $mutex.WaitOne([System.TimeSpan]::FromMinutes($MutexTimeoutMinutes))
    } catch [System.Threading.AbandonedMutexException] {
        # A previous holder died without releasing; ownership passes to us, its Gradle must go first.
        $acquired = $true
        Stop-AbandonedBuild $pidFile
    }
    if (-not $acquired) {
        Exit-WithError "Timed out after $MutexTimeoutMinutes minutes waiting for Global\shadowzap-gradle" 124
    }

    $taskArgs = ($taskList | ForEach-Object { '"' + $_ + '"' }) -join ' '
    Write-Host "gradlew $($taskList -join ' ') (project: $projectDir)"

    # cmd.exe does the redirection so PowerShell 5.1 never wraps native stderr into error records. Its PID is
    # recorded so a later run can stop this build if this script is killed while holding the mutex.
    $command = "/d /s /c `"call `"$gradlew`" $taskArgs --no-daemon --console=plain > `"$logFile`" 2>&1`""
    $process = Start-Process -FilePath 'cmd.exe' -ArgumentList $command -WorkingDirectory $projectDir `
        -NoNewWindow -PassThru
    $null = $process.Handle # keeps ExitCode available after the process exits
    [System.IO.File]::WriteAllText($pidFile, [string]$process.Id, [System.Text.Encoding]::ASCII)
    $process.WaitForExit()
    $exitCode = $process.ExitCode
    Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue

    $lines = @()
    if (Test-Path -LiteralPath $logFile) {
        $lines = @(Get-Content -LiteralPath $logFile)
    }

    $warnings = @($lines | Where-Object { $_ -match '^w: ' })
    if ($warnings.Count -gt 0) {
        Write-Host ("===== compiler warnings ({0}, first 50) =====" -f $warnings.Count)
        $warnings | Select-Object -First 50 | ForEach-Object { Write-Host $_ }
    }

    $problems = @($lines | Where-Object { $_ -match '^\s*(e:|error:)|FAILED|What went wrong' })
    if ($problems.Count -gt 0) {
        Write-Host '===== errors and failures ====='
        $problems | ForEach-Object { Write-Host $_ }
    }

    Write-Host ("===== last {0} lines =====" -f $TailLines)
    $lines | Select-Object -Last $TailLines | ForEach-Object { Write-Host $_ }
    Write-Host "===== gradle exit code: $exitCode ====="
} finally {
    if ($acquired) {
        $mutex.ReleaseMutex()
    }
    $mutex.Dispose()
    if (Test-Path -LiteralPath $logFile) {
        Remove-Item -LiteralPath $logFile -Force -ErrorAction SilentlyContinue
    }
}

exit $exitCode
