param(
    [string]$RepositoryInitScript,
    [string]$GradleExecutable
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$gradle = if ($GradleExecutable) {
    (Resolve-Path -LiteralPath $GradleExecutable).Path
} else {
    Join-Path $root 'gradlew.bat'
}
$coordinateInit = Join-Path $PSScriptRoot 'print-runtime-dependencies.init.gradle'
$arguments = @('-I', $coordinateInit)
if ($RepositoryInitScript) {
    $arguments += @('-I', (Resolve-Path -LiteralPath $RepositoryInitScript).Path)
}
$arguments += ':app:printRuntimeCoordinates'

$output = & $gradle @arguments 2>&1
if ($LASTEXITCODE -ne 0) {
    $output | ForEach-Object { Write-Output $_ }
    throw "Gradle dependency resolution failed with exit code $LASTEXITCODE"
}

$coordinates = @($output | ForEach-Object {
    if ($_ -match 'OUTERVIEW_RUNTIME\s+([^\s]+)$') { $Matches[1] }
} | Where-Object { $_ -and $_ -notmatch '^OuterView:' } | Sort-Object -Unique)

$rules = [ordered]@{
    'Apache-2.0 / AndroidX' = '^androidx\.'
    'Apache-2.0 / Android Material' = '^com\.google\.android\.material:'
    'Apache-2.0 / Kotlin and JetBrains' = '^org\.jetbrains(?:\.kotlinx|\.kotlin)?:'
    'Apache-2.0 / HighCapable' = '^com\.highcapable\.'
    'Apache-2.0 / Google libraries' = '^com\.google\.(?:code\.gson|code\.findbugs|errorprone|guava|j2objc):'
    'Apache-2.0 / JSpecify' = '^org\.jspecify:'
    'Apache-2.0 / Hidden API Bypass' = '^org\.lsposed\.hiddenapibypass:'
    'BSD-3-Clause and Apache-2.0 / smali' = '^com\.android\.tools\.smali:smali-dexlib2:'
    'MIT / Checker qualifiers' = '^org\.checkerframework:checker-qual:'
    'MIT / SLF4J' = '^org\.slf4j:slf4j-api:'
}

$classified = @{}
$unknown = [System.Collections.Generic.List[string]]::new()
foreach ($coordinate in $coordinates) {
    $license = $rules.Keys | Where-Object { $coordinate -match $rules[$_] } | Select-Object -First 1
    if (-not $license) {
        $unknown.Add($coordinate)
        continue
    }
    if (-not $classified.ContainsKey($license)) {
        $classified[$license] = [System.Collections.Generic.List[string]]::new()
    }
    $classified[$license].Add($coordinate)
}

$forbidden = @($coordinates | Where-Object {
    $_ -match '(?i)(dexkit|mmkv|reareye|(?:^|[.:-])(?:a?gpl|lgpl)(?:[.:-]|$))'
})
if ($forbidden.Count -gt 0) {
    throw "Strict or removed runtime dependencies found: $($forbidden -join ', ')"
}
if ($unknown.Count -gt 0) {
    throw "Runtime dependencies without an approved license mapping: $($unknown -join ', ')"
}

foreach ($license in $rules.Keys) {
    if ($classified.ContainsKey($license)) {
        Write-Output ("{0}: {1} component(s)" -f $license, $classified[$license].Count)
    }
}
Write-Output "OK: $($coordinates.Count) exact runtime coordinates map to approved permissive licenses."
