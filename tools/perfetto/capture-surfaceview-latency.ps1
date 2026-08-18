param(
    [ValidateRange(1, 600)]
    [int]$DurationSeconds = 30,
    [ValidateRange(100, 5000)]
    [int]$PollIntervalMs = 1000,
    [string]$OutputPath = "app/build/tracking-telemetry/surfaceview-latency.csv"
)

$ErrorActionPreference = "Stop"
$packageName = "com.example.armakeup"
$pendingTimestamp = [long]::MaxValue
$invariantCulture = [System.Globalization.CultureInfo]::InvariantCulture

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    throw "adb is not available on PATH"
}

$devices = @(
    adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "\sdevice$" }
)
if ($devices.Count -ne 1) {
    throw "Expected exactly one connected adb device, found $($devices.Count)"
}

$layerLine = $null
$layerDiscovery = [System.Diagnostics.Stopwatch]::StartNew()
while (-not $layerLine -and $layerDiscovery.Elapsed.TotalSeconds -lt 10) {
    $layerLines = @(adb shell dumpsys SurfaceFlinger --list)
    $layerLine = $layerLines | Where-Object {
        $_ -match "SurfaceView\[$([regex]::Escape($packageName))/.+?\].*?\(BLAST\)#\d+"
    } | Select-Object -First 1
    if (-not $layerLine) {
        Start-Sleep -Milliseconds 250
    }
}
if (-not $layerLine -or $layerLine -notmatch "RequestedLayerState\{(?<layer>.+?\(BLAST\)#\d+)\s+parentId=") {
    throw "Filament SurfaceView BLAST layer was not found for $packageName"
}
$layerName = $Matches.layer
if ($layerName.Contains("'")) {
    throw "Unsupported quote in SurfaceView layer name"
}
$escapedLayerName = $layerName

adb shell "dumpsys SurfaceFlinger --latency-clear '$escapedLayerName'" | Out-Null

$records = [System.Collections.Generic.HashSet[string]]::new()
$refreshPeriodNs = 0L
$stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
while ($stopwatch.Elapsed.TotalSeconds -lt $DurationSeconds) {
    $lines = @(adb shell "dumpsys SurfaceFlinger --latency '$escapedLayerName'")
    foreach ($line in $lines) {
        if ($line -match "^\s*(\d+)\s+(\d+)\s+(\d+)\s*$") {
            $desired = [long]$Matches[1]
            $actual = [long]$Matches[2]
            $ready = [long]$Matches[3]
            if (
                $desired -gt 0L -and $actual -gt 0L -and $ready -gt 0L -and
                $desired -ne $pendingTimestamp -and
                $actual -ne $pendingTimestamp -and
                $ready -ne $pendingTimestamp
            ) {
                $records.Add("$desired,$actual,$ready") | Out-Null
            }
        } elseif ($line -match "^\s*(\d+)\s*$") {
            $refreshPeriodNs = [long]$Matches[1]
        }
    }
    Start-Sleep -Milliseconds $PollIntervalMs
}

$rows = @($records | ForEach-Object {
    $parts = $_.Split(',')
    $desired = [long]$parts[0]
    $actual = [long]$parts[1]
    $ready = [long]$parts[2]
    [pscustomobject]@{
        layer_name = $layerName
        desired_present_ns = $desired
        actual_present_ns = $actual
        frame_ready_ns = $ready
        desired_to_actual_ms = (($actual - $desired) / 1000000.0).ToString(
            "F6",
            $invariantCulture
        )
        ready_to_actual_ms = (($actual - $ready) / 1000000.0).ToString(
            "F6",
            $invariantCulture
        )
    }
} | Sort-Object desired_present_ns)

$parentDirectory = Split-Path -Parent $OutputPath
if ($parentDirectory) {
    New-Item -ItemType Directory -Force -Path $parentDirectory | Out-Null
}
$rows | Export-Csv -NoTypeInformation -Encoding UTF8 -Path $OutputPath

function Get-Percentile([double[]]$Values, [double]$Fraction) {
    if ($Values.Count -eq 0) { return [double]::NaN }
    $sorted = @($Values | Sort-Object)
    $index = [math]::Floor(($sorted.Count - 1) * $Fraction)
    return $sorted[$index]
}

$desiredLatency = [double[]]@($rows | ForEach-Object {
    [double]::Parse($_.desired_to_actual_ms, $invariantCulture)
})
$readyLatency = [double[]]@($rows | ForEach-Object {
    [double]::Parse($_.ready_to_actual_ms, $invariantCulture)
})
$actualTimestamps = [long[]]@($rows | ForEach-Object { $_.actual_present_ns })
$actualIntervals = [System.Collections.Generic.List[double]]::new()
for ($index = 1; $index -lt $actualTimestamps.Count; $index++) {
    $actualIntervals.Add(($actualTimestamps[$index] - $actualTimestamps[$index - 1]) / 1000000.0)
}

[pscustomobject]@{
    layer_name = $layerName
    refresh_period_ms = $refreshPeriodNs / 1000000.0
    frame_count = $rows.Count
    desired_to_actual_median_ms = Get-Percentile $desiredLatency 0.50
    desired_to_actual_p95_ms = Get-Percentile $desiredLatency 0.95
    ready_to_actual_median_ms = Get-Percentile $readyLatency 0.50
    ready_to_actual_p95_ms = Get-Percentile $readyLatency 0.95
    actual_interval_median_ms = Get-Percentile ([double[]]$actualIntervals) 0.50
    actual_interval_p95_ms = Get-Percentile ([double[]]$actualIntervals) 0.95
    output = (Resolve-Path $OutputPath).Path
} | Format-List
