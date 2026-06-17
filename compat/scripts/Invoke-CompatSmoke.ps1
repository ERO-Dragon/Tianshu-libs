param(
    [string[]] $Targets,
    [int] $TimeoutSeconds = 120,
    [int] $PostSmokeSeconds = 12,
    [switch] $FailOnWarn,
    [switch] $IncludeBlocked,
    [switch] $UnverifiedOnly,
    [switch] $NoConfigurationCache
)

$ErrorActionPreference = 'Stop'

$root = Resolve-Path (Join-Path $PSScriptRoot '..\..')
$matrixPath = Join-Path $root 'compat\matrix.json'
$matrix = Get-Content -Raw -LiteralPath $matrixPath | ConvertFrom-Json

if (-not $Targets -or $Targets.Count -eq 0) {
    $Targets = @($matrix.targets.PSObject.Properties | Where-Object {
        ($IncludeBlocked -or $_.Value.status -ne 'blocked') -and
        (-not $UnverifiedOnly -or $_.Value.status -ne 'verified')
    } | ForEach-Object { $_.Name })
} else {
    $Targets = @($Targets | ForEach-Object { $_ -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ })
}

if ($Targets.Count -eq 0) {
    Write-Host "No compat targets selected."
    exit 0
}

$requiredPatterns = @(
    'TIANSHU_LIBS_BOOTSTRAP_OK loader=',
    '[api][jjml] OK',
    '[api][sherpa-onnx] OK',
    '[api][onnxruntime] OK'
)
$fatalPatterns = @(
    'Exception in thread',
    'java.lang.UnsatisfiedLinkError',
    'java.lang.NoClassDefFoundError',
    'java.lang.ClassNotFoundException',
    'InvalidModFileException',
    'Failed to find system mod',
    'Native API smoke test: FAILED',
    'Native library loading failed',
    'Failed to load native'
)
$ignoredErrorPatterns = @(
    'Failed to fetch user properties',
    'Failed to fetch Realms feature flags',
    "Couldn't connect to realms",
    'Realms authentication error',
    'Could not authorize you against Realms server',
    'Unable to create custom ContextSelector. Falling back to default.'
)
$warnPatterns = @(
    '/WARN]',
    '[WARN]'
)

function Get-TargetSpec([string] $target) {
    $prop = $matrix.targets.PSObject.Properties[$target]
    if ($null -eq $prop) {
        throw "Unknown compat target '$target'."
    }
    $spec = $prop.Value
    if (-not $spec.loaderVersion) {
        throw "Target '$target' must define loaderVersion."
    }
    return $spec
}

function Get-RunProject([string] $loader) {
    switch ($loader) {
        'fabric' { return 'fabricverify' }
        'forge' { return 'forgeverify' }
        'neoforge' { return 'neoforgeverify' }
        default { throw "Unsupported loader '$loader'." }
    }
}

function Get-GradleArgs([string] $target, $spec) {
    $project = Get-RunProject $spec.loader
    $args = @()
    $args += "-Pcompat_target=$target"

    switch ($spec.loader) {
        'fabric' {
            $args += "-Pfabric_verify_minecraft_version=$($spec.minecraft)"
            $args += "-Pfabric_verify_loader_version=$($spec.loaderVersion)"
            if ($spec.java) { $args += "-Pfabric_verify_java_version=$($spec.java)" }
        }
        'forge' {
            $args += "-Pforge_verify_version=$($spec.loaderVersion)"
            if ($spec.java) { $args += "-Pforge_verify_java_version=$($spec.java)" }
        }
        'neoforge' {
            $args += "-Pneoforge_verify_version=$($spec.loaderVersion)"
            if ($spec.java) { $args += "-Pneoforge_verify_java_version=$($spec.java)" }
        }
    }

    $args += ":${project}:runClient"
    $args += '--rerun-tasks'
    if ($NoConfigurationCache) {
        $args += '--no-configuration-cache'
    }
    return $args
}

function Get-DescendantProcessIds([int] $processId) {
    $children = @(Get-CimInstance Win32_Process -Filter "ParentProcessId=$processId" -ErrorAction SilentlyContinue)
    foreach ($child in $children) {
        $childId = [int] $child.ProcessId
        $childId
        Get-DescendantProcessIds $childId
    }
}

function Stop-ProcessTree([int] $processId) {
    $ids = @(Get-DescendantProcessIds $processId) + $processId
    foreach ($id in ($ids | Select-Object -Unique)) {
        $process = Get-Process -Id $id -ErrorAction SilentlyContinue
        if ($process) {
            Stop-Process -Id $id -Force -ErrorAction SilentlyContinue
        }
    }
}

function Read-IfExists([string] $path) {
    if (Test-Path -LiteralPath $path) {
        return Get-Content -Raw -LiteralPath $path -ErrorAction SilentlyContinue
    }
    return ''
}

function Get-CombinedLog([string] $stdoutPath, [string] $stderrPath, [string] $logsDir) {
    $latestLog = Join-Path $logsDir 'latest.log'
    $debugLog = Join-Path $logsDir 'debug.log'
    return (Read-IfExists $stdoutPath) + "`n" + (Read-IfExists $stderrPath) + "`n" + (Read-IfExists $latestLog) + "`n" + (Read-IfExists $debugLog)
}

function Clear-RunDirectory([string] $path) {
    $rootPath = [System.IO.Path]::GetFullPath([string] $root)
    $targetPath = [System.IO.Path]::GetFullPath($path)
    if (-not $targetPath.StartsWith($rootPath, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to clean run directory outside workspace: $targetPath"
    }
    if (Test-Path -LiteralPath $targetPath) {
        Remove-Item -LiteralPath $targetPath -Recurse -Force
    }
}

function Select-MatchingLines([string] $text, [string[]] $patterns) {
    $lines = @($text -split "`r?`n")
    return @($lines | Where-Object {
        $line = $_
        $patterns | Where-Object { $line.Contains($_) } | Select-Object -First 1
    } | Select-Object -Unique)
}

function Select-FatalLines([string] $text) {
    return @(Select-MatchingLines $text $fatalPatterns | Where-Object {
        -not (Test-LineContainsAny $_ $ignoredErrorPatterns)
    })
}

function Select-ErrorLines([string] $text) {
    $lines = @($text -split "`r?`n")
    return @($lines | Where-Object {
        ($_.Contains('/ERROR]') -or $_.Contains('[ERROR]')) -and -not (Test-LineContainsAny $_ $ignoredErrorPatterns)
    } | Select-Object -Unique)
}

function Test-LineContainsAny([string] $line, [string[]] $patterns) {
    foreach ($pattern in $patterns) {
        if ($line.Contains($pattern)) {
            return $true
        }
    }
    return $false
}

$results = @()
$summaryPath = Join-Path $root 'build\compat\smoke-summary.json'

function Write-SmokeSummary {
    @($results) | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $summaryPath -Encoding UTF8
}

foreach ($target in $Targets) {
    $spec = Get-TargetSpec $target
    if ($spec.status -eq 'blocked' -and -not $IncludeBlocked) {
        Write-Host "SKIP $target blocked: $($spec.blockedReason)"
        continue
    }
    $project = Get-RunProject $spec.loader
    $runDir = Join-Path $root "$project\run"
    $logsDir = Join-Path $runDir 'logs'
    $stdoutPath = Join-Path $root "build\compat\smoke-$target.out.log"
    $stderrPath = Join-Path $root "build\compat\smoke-$target.err.log"

    New-Item -ItemType Directory -Force -Path (Split-Path $stdoutPath) | Out-Null
    Clear-RunDirectory $runDir
    if (Test-Path -LiteralPath $stdoutPath) { Remove-Item -LiteralPath $stdoutPath -Force }
    if (Test-Path -LiteralPath $stderrPath) { Remove-Item -LiteralPath $stderrPath -Force }

    $gradleArgs = Get-GradleArgs $target $spec
    Write-Host "==> $target :: .\gradlew.bat $($gradleArgs -join ' ')"

    $process = Start-Process -FilePath (Join-Path $root 'gradlew.bat') `
        -ArgumentList $gradleArgs `
        -WorkingDirectory $root `
        -RedirectStandardOutput $stdoutPath `
        -RedirectStandardError $stderrPath `
        -WindowStyle Hidden `
        -PassThru

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $success = $false
    $smokeSeen = $false
    $stoppedByHarness = $false
    $postSmokeDeadline = $null
    $matched = @()
    $combined = ''

    while ((Get-Date) -lt $deadline) {
        if ($process.HasExited) {
            break
        }

        $combined = Get-CombinedLog $stdoutPath $stderrPath $logsDir

        $matched = @($requiredPatterns | Where-Object { $combined.Contains($_) })
        if (-not $smokeSeen -and $matched.Count -eq $requiredPatterns.Count -and $combined.Contains("TIANSHU_LIBS_BOOTSTRAP_OK loader=$($spec.loader)")) {
            $smokeSeen = $true
            $postSmokeDeadline = (Get-Date).AddSeconds($PostSmokeSeconds)
            Write-Host "SMOKE $target observed; watching for $PostSmokeSeconds more seconds"
        }

        if ($smokeSeen -and (Get-Date) -ge $postSmokeDeadline) {
            $fatalLines = Select-FatalLines $combined
            $fatalLines += Select-ErrorLines $combined
            $warnLines = Select-MatchingLines $combined $warnPatterns
            if ($fatalLines.Count -eq 0 -and (-not $FailOnWarn -or $warnLines.Count -eq 0)) {
                $success = $true
            }
            $stoppedByHarness = $true
            Stop-ProcessTree $process.Id
            break
        }

        Start-Sleep -Seconds 1
    }

    if (-not $process.HasExited) {
        Stop-ProcessTree $process.Id
    }
    $process.WaitForExit()
    $process.Refresh()

    if (-not $success) {
        $combined = Get-CombinedLog $stdoutPath $stderrPath $logsDir
        $matched = @($requiredPatterns | Where-Object { $combined.Contains($_) })
    }
    $fatalLines = Select-FatalLines $combined
    $fatalLines += Select-ErrorLines $combined
    $warnLines = Select-MatchingLines $combined $warnPatterns
    $ignoredErrorLines = Select-MatchingLines $combined $ignoredErrorPatterns

    $result = [pscustomobject]@{
        target = $target
        loader = $spec.loader
        minecraft = $spec.minecraft
        loaderVersion = $spec.loaderVersion
        success = $success
        smokeSeen = $smokeSeen
        stoppedByHarness = $stoppedByHarness
        matched = $matched
        fatalLines = $fatalLines
        ignoredErrorCount = $ignoredErrorLines.Count
        ignoredErrorSample = @($ignoredErrorLines | Select-Object -First 20)
        warnCount = $warnLines.Count
        warnSample = @($warnLines | Select-Object -First 20)
        exitCode = $process.ExitCode
        stdout = $stdoutPath
        stderr = $stderrPath
    }
    $results += $result

    if ($success) {
        Write-Host "PASS $target"
    } else {
        Write-Host "FAIL $target matched=$($matched.Count)/$($requiredPatterns.Count) exit=$($process.ExitCode)"
    }

    Write-SmokeSummary
}

Write-SmokeSummary
Write-Host "Summary: $summaryPath"

if ($results | Where-Object { -not $_.success }) {
    exit 1
}
