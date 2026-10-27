#requires -Version 5.1
param(
    [switch]$RunLint,
    [switch]$RunBackendSmoke
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$runId = (Get-Date -Format "yyyyMMdd-HHmmss-fff") + "-" + ([guid]::NewGuid().ToString("N").Substring(0, 8))
$outputDir = Join-Path $repoRoot "build/verification/windows/$runId"
$null = New-Item -ItemType Directory -Path $outputDir -Force
$summaryPath = Join-Path $outputDir "summary.json"
$steps = New-Object System.Collections.Generic.List[object]
$summary = [ordered]@{
    schemaVersion = 1
    startedUtc = [DateTime]::UtcNow.ToString("o")
    finishedUtc = $null
    durationSeconds = $null
    status = "Running"
    exitCode = $null
    revision = $null
    dirty = $null
    powershellVersion = $PSVersionTable.PSVersion.ToString()
    steps = $steps
}
$runTimer = [Diagnostics.Stopwatch]::StartNew()
$scriptFailed = $false

# Never enumerate configuration in logs. Scrub inherited values and credential lines
# before either console or disk output; keep short non-secret flags readable.
$redactions = @(Get-ChildItem Env: | Where-Object {
    $_.Value.Length -ge 4 -or ($_.Name -match '(?i)key|token|secret|password|credential' -and $_.Value.Length -gt 0)
} | ForEach-Object { $_.Value } | Select-Object -Unique | Sort-Object Length -Descending)

function Protect-Output {
    param([string]$Text)
    if ($Text -match '(?i)authorization|\b(password|token|secret|api[_-]?key)\s*[=:]') {
        return "[REDACTED credential line]"
    }
    foreach ($value in $redactions) {
        $Text = $Text.Replace($value, "[REDACTED]")
    }
    return ($Text -replace '(?i)\b(?:https?|postgres(?:ql)?)://[^\s]+', '[REDACTED URL]')
}

function Save-Summary {
    $summary | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $summaryPath -Encoding UTF8
}

function Add-Step {
    param(
        [string]$Name,
        [string]$Executable,
        [string[]]$Arguments,
        [string]$Directory = ".",
        [bool]$Required = $true,
        [bool]$Enabled = $true,
        [string]$Reason = ""
    )
    $steps.Add([ordered]@{
        name = $Name
        command = (@($Executable) + $Arguments) -join " "
        directory = $Directory
        required = $Required
        enabled = $Enabled
        status = "Pending"
        exitCode = $null
        startedUtc = $null
        finishedUtc = $null
        durationSeconds = $null
        log = ("{0:D2}.log" -f ($steps.Count + 1))
        versionOutput = $null
        reason = $Reason
        executable = $Executable
        arguments = $Arguments
    })
}

# Use native applications only: npm.ps1 must not depend on execution policy.
Add-Step "Git version" "git.exe" @("--version")
Add-Step "Source revision" "git.exe" @("rev-parse", "HEAD")
Add-Step "Source dirty status" "git.exe" @("status", "--porcelain", "--untracked-files=normal")
Add-Step "Java version" "java.exe" @("-version")
Add-Step "Gradle version" ".\gradlew.bat" @("--version")
Add-Step "Node version" "node.exe" @("--version")
Add-Step "npm version" "npm.cmd" @("--version")
Add-Step "Python version" "python.exe" @("--version")
Add-Step "Android assembleDebug" ".\gradlew.bat" @("assembleDebug", "--stacktrace", "--console=plain")
Add-Step "Android unit tests" ".\gradlew.bat" @("testDebugUnitTest", "--stacktrace", "--console=plain")
Add-Step "Android lint (advisory)" ".\gradlew.bat" @("lintDebug", "--console=plain") -Required $false -Enabled ([bool]$RunLint) -Reason "Optional advisory baseline"
Add-Step "Model packager tests" "python.exe" @("-B", "-m", "unittest", "discover", "-s", "tools/model-packager/tests", "-v")
Add-Step "Backend npm ci" "npm.cmd" @("ci") -Directory "backend"
Add-Step "Backend typecheck" "npm.cmd" @("run", "typecheck") -Directory "backend"
Add-Step "Backend unit tests" "npm.cmd" @("test") -Directory "backend"
$smokeEnabled = [bool]$RunBackendSmoke -and -not [string]::IsNullOrWhiteSpace($env:PERSONA_BACKEND_BASE_URL)
Add-Step "Backend smoke" "npm.cmd" @("run", "smoke") -Directory "backend" -Required $smokeEnabled -Enabled $smokeEnabled -Reason "Requires -RunBackendSmoke and configured PERSONA_BACKEND_BASE_URL; backend/PostgreSQL must already be running"

Save-Summary
Write-Host "Verification output: build/verification/windows/$runId"
Push-Location $repoRoot
try {
    foreach ($step in $steps) {
        $logPath = Join-Path $outputDir $step.log
        if (-not $step.enabled) {
            "Pending: $($step.reason)" | Set-Content -LiteralPath $logPath -Encoding UTF8
            Write-Host "Pending: $($step.name)"
            continue
        }

        $step.status = "Running"
        $step.startedUtc = [DateTime]::UtcNow.ToString("o")
        $stepTimer = [Diagnostics.Stopwatch]::StartNew()
        Save-Summary
        Write-Host "==> $($step.name)"
        $writer = $null
        $locationPushed = $false
        $captured = New-Object System.Collections.Generic.List[string]
        try {
            $writer = New-Object System.IO.StreamWriter($logPath, $false, (New-Object System.Text.UTF8Encoding($false)))
            $writer.AutoFlush = $true
            Push-Location (Join-Path $repoRoot $step.directory)
            $locationPushed = $true
            $executable = $step.executable
            if ($step.name -eq "Java version" -and $env:JAVA_HOME) {
                $executable = Join-Path $env:JAVA_HOME.Trim('"') "bin/java.exe"
            }
            $tool = Get-Command $executable -CommandType Application -ErrorAction Stop | Select-Object -First 1
            $arguments = $step.arguments
            $LASTEXITCODE = $null
            # PS 5.1 wraps native stderr in ErrorRecord, even for successful tools.
            # Continue streams it to the sanitizer; only a fresh native exit is success.
            $ErrorActionPreference = "Continue"
            & $tool.Source @arguments 2>&1 | ForEach-Object {
                $line = Protect-Output ([string]$_)
                $writer.WriteLine($line)
                Write-Host $line
                if ($step.name -eq "Source revision" -or $step.name -eq "Source dirty status") {
                    $captured.Add([string]$_)
                } elseif ($step.name -like "* version") {
                    $captured.Add($line)
                }
            }
            $step.exitCode = $LASTEXITCODE
            $ErrorActionPreference = "Stop"
            if ($null -eq $step.exitCode) {
                $step.status = "Failed"
                $step.reason = "Command did not return a native exit code"
            } elseif ($step.exitCode -ne 0) {
                $step.status = "Failed"
                $step.reason = "Command returned a nonzero exit code"
            } else {
                $step.status = "Passed"
                $step.reason = ""
                if ($step.name -like "* version") {
                    $step.versionOutput = $captured -join "`n"
                } elseif ($step.name -eq "Source revision") {
                    $revision = ($captured -join "").Trim()
                    if ($revision -notmatch '^[0-9a-f]{40,64}$') {
                        throw "Invalid source revision"
                    }
                    $summary.revision = $revision
                } elseif ($step.name -eq "Source dirty status") {
                    $summary.dirty = $captured.Count -gt 0
                }
            }
        } catch {
            $step.status = "Failed"
            $step.reason = "Required tool missing, command invocation failed, or output could not be recorded"
            # Exception text can contain paths, arguments or environment values.
        } finally {
            $ErrorActionPreference = "Stop"
            if ($null -ne $writer) { $writer.Dispose() }
            if ($locationPushed) { Pop-Location }
            $stepTimer.Stop()
            $step.finishedUtc = [DateTime]::UtcNow.ToString("o")
            $step.durationSeconds = [math]::Round($stepTimer.Elapsed.TotalSeconds, 3)
            Add-Content -LiteralPath $logPath -Encoding UTF8 -Value "$($step.status): exit=$($step.exitCode); $($step.reason)"
            Save-Summary
        }
        Write-Host "$($step.status): $($step.name) (exit=$($step.exitCode), $($step.durationSeconds)s)"
    }
} catch {
    $scriptFailed = $true
    Write-Host "Verification could not complete; inspect the last checkpoint in summary.json."
} finally {
    Pop-Location
    $runTimer.Stop()
    $summary.finishedUtc = [DateTime]::UtcNow.ToString("o")
    $summary.durationSeconds = [math]::Round($runTimer.Elapsed.TotalSeconds, 3)
    $failed = $scriptFailed -or @($steps | Where-Object { $_.required -and $_.status -ne "Passed" }).Count -gt 0
    $summary.status = if ($failed) { "Failed" } else { "Passed" }
    $summary.exitCode = if ($failed) { 1 } else { 0 }
    Save-Summary
}

Write-Host "Baseline $($summary.status). Summary: build/verification/windows/$runId/summary.json"
Write-Host "Optional Pending steps and device/JNI acceptance are not baseline passes."
exit $summary.exitCode
