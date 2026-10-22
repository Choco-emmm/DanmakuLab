param(
    [ValidateRange(1, 60)][int]$Messages = 60,
    [string]$HttpUrl = 'http://127.0.0.1:8080',
    [string]$OutputDirectory
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Push-Location $projectRoot
try {
    if (-not $OutputDirectory) {
        $beijing = [TimeZoneInfo]::ConvertTimeBySystemTimeZoneId([DateTimeOffset]::UtcNow, 'China Standard Time')
        $OutputDirectory = "docs/results/$($beijing.ToString('yyyy-MM-dd'))/smoke-$($beijing.ToString('HHmmss'))"
    }
    if (Test-Path -LiteralPath $OutputDirectory) {
        throw "Output directory already exists; choose a new run directory: $OutputDirectory"
    }
    $baseUrl = $HttpUrl.TrimEnd('/')
    $health = Invoke-RestMethod "$baseUrl/actuator/health"
    if ($health.status -ne 'UP') { throw 'Server health check is not UP' }
    $webSocketUrl = $baseUrl -replace '^http', 'ws'
    New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
    $outputPath = (Resolve-Path -LiteralPath $OutputDirectory).Path

    function Save-Metrics([string]$Name) {
        $index = Invoke-RestMethod "$baseUrl/actuator/metrics"
        $names = $index.names | Where-Object { $_ -like 'lab.*' -or $_ -in @('jvm.memory.used', 'jvm.threads.live', 'process.cpu.usage') }
        $samples = foreach ($metricName in $names) {
            Invoke-RestMethod "$baseUrl/actuator/metrics/$metricName"
        }
        ConvertTo-Json -InputObject @($samples) -Depth 12 | Set-Content -LiteralPath (Join-Path $outputPath $Name) -Encoding utf8
    }

    Save-Metrics 'server-before.json'
    $cpu = Get-CimInstance Win32_Processor
    $machine = Get-CimInstance Win32_ComputerSystem
    $environment = [ordered]@{
        capturedAt = [DateTimeOffset]::Now.ToString('o')
        os = [Environment]::OSVersion.VersionString
        cpu = $cpu.Name.Trim()
        logicalProcessors = $cpu.NumberOfLogicalProcessors
        physicalMemoryBytes = $machine.TotalPhysicalMemory
        java = ((& java -version 2>&1) | Out-String).Trim()
        k6 = ((& k6 version) | Out-String).Trim()
        network = $baseUrl
        note = 'Client and server placement, JVM heap and other server flags must be recorded in notes.md.'
    }
    $environment | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $outputPath 'environment.json') -Encoding utf8
    @{ scenario = 'T0'; viewers = 10; isolationProbes = 1; messages = $Messages; targetInputPerSecond = 1;
       expectedUniqueDeliveries = 10 * $Messages; mode = 'direct-baseline'; wsUrl = "$webSocketUrl/ws" } |
        ConvertTo-Json | Set-Content (Join-Path $outputPath 'config.json') -Encoding utf8

    $sourceFiles = @(Get-Item pom.xml) + @(Get-ChildItem src,scripts -Recurse -File)
    $manifest = $sourceFiles | Sort-Object FullName | ForEach-Object {
        @{ path = [IO.Path]::GetRelativePath($projectRoot, $_.FullName).Replace('\', '/'); sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash }
    }
    ConvertTo-Json -InputObject @($manifest) -Depth 4 | Set-Content (Join-Path $outputPath 'source-sha256.json') -Encoding utf8
    "k6 run --quiet -e MESSAGES=$Messages -e WS_URL=$webSocketUrl/ws -e SUMMARY_PATH=<run-directory>/k6-summary.json scripts/load/smoke.js" |
        Set-Content (Join-Path $outputPath 'commands.txt') -Encoding utf8

    & k6 run --quiet -e "MESSAGES=$Messages" -e "WS_URL=$webSocketUrl/ws" `
        -e "SUMMARY_PATH=$outputPath/k6-summary.json" scripts/load/smoke.js 2>&1 |
        Tee-Object -FilePath (Join-Path $outputPath 'k6-output.txt')
    $k6ExitCode = $LASTEXITCODE
    Save-Metrics 'server-after.json'
    if ($k6ExitCode -ne 0) { throw "k6 failed with exit code $k6ExitCode; evidence retained at $outputPath" }
    Write-Output "Passed. Evidence: $outputPath"
} finally {
    Pop-Location
}
