param(
    [Parameter(Mandatory = $true)]
    [string]$ServerDirectory,

    [Parameter(Mandatory = $true)]
    [string]$FileName,

    [Parameter(Mandatory = $true)]
    [string]$Arguments,

    [int]$StartupTimeoutSeconds = 180,
    [int]$ReloadWaitSeconds = 20,
    [string]$ReloadCommand = 'reload'
)

$ErrorActionPreference = 'Stop'
$resolvedServerDirectory = (Resolve-Path -LiteralPath $ServerDirectory).Path
$processInfo = [System.Diagnostics.ProcessStartInfo]::new()
$processInfo.FileName = $FileName
$processInfo.Arguments = $Arguments
$processInfo.WorkingDirectory = $resolvedServerDirectory
$processInfo.UseShellExecute = $false
$processInfo.CreateNoWindow = $true
$processInfo.RedirectStandardInput = $true

$process = [System.Diagnostics.Process]::new()
$process.StartInfo = $processInfo

if (-not $process.Start()) {
    throw 'Failed to start the server process.'
}

$startedAt = [DateTime]::UtcNow
$reloadSentAt = $null
$commandsSent = $false
$stopSent = $false
$sawDone = $false

try {
    while (-not $process.HasExited) {
        $logPath = Join-Path $resolvedServerDirectory 'logs\latest.log'
        if (-not $sawDone -and (Test-Path -LiteralPath $logPath)) {
            try {
                $logFile = Get-Item -LiteralPath $logPath
                if ($logFile.LastWriteTimeUtc -ge $startedAt.AddSeconds(-2)) {
                    $logText = Get-Content -Raw -LiteralPath $logPath
                    if ($logText -match 'Done \(' -and $logText -match '\[invadvopt\].*ready') {
                        $sawDone = $true
                        Write-Output 'SMOKE_MARKER server-ready-and-self-check-passed'
                    }
                }
            } catch {
                # Log4j can briefly hold the file while rotating it. Retry on the next poll.
            }
        }

        $now = [DateTime]::UtcNow
        if ($sawDone -and -not $commandsSent) {
            $process.StandardInput.WriteLine('invadvopt status')
            $process.StandardInput.WriteLine('invadvopt stats')
            $process.StandardInput.WriteLine($ReloadCommand)
            $process.StandardInput.Flush()
            $commandsSent = $true
            $reloadSentAt = $now
        }

        if ($commandsSent -and -not $stopSent -and ($now - $reloadSentAt).TotalSeconds -ge $ReloadWaitSeconds) {
            $process.StandardInput.WriteLine('invadvopt status')
            $process.StandardInput.WriteLine('invadvopt verify')
            $process.StandardInput.WriteLine('stop')
            $process.StandardInput.Flush()
            $stopSent = $true
        }

        if (-not $sawDone -and ($now - $startedAt).TotalSeconds -ge $StartupTimeoutSeconds) {
            throw "Server did not reach the ready state within $StartupTimeoutSeconds seconds."
        }

        if ($stopSent -and ($now - $reloadSentAt).TotalSeconds -ge ($ReloadWaitSeconds + 90)) {
            throw 'Server did not stop within 90 seconds after the stop command.'
        }

        Start-Sleep -Milliseconds 100
    }

    if (-not $sawDone) {
        throw "Server exited before reaching ready state (exit code $($process.ExitCode))."
    }
    if (-not $stopSent) {
        throw "Server exited before the smoke-test command sequence completed (exit code $($process.ExitCode))."
    }
    if ($process.ExitCode -ne 0) {
        throw "Server exited with code $($process.ExitCode)."
    }
} finally {
    if (-not $process.HasExited) {
        $process.StandardInput.WriteLine('stop')
        $process.StandardInput.Flush()
        if (-not $process.WaitForExit(30000)) {
            $process.Kill($true)
        }
    }
    $process.Dispose()
}
