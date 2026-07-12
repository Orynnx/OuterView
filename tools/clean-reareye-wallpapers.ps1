param(
    [Parameter(Mandatory = $true)][string]$InputRuntime,
    [Parameter(Mandatory = $true)][string]$OutputRuntime
)

$ErrorActionPreference = 'Stop'
$parsed = Get-Content -LiteralPath $InputRuntime -Raw -Encoding utf8 | ConvertFrom-Json
$items = @()
foreach ($item in $parsed) { $items += $item }
$removed = @($items | Where-Object {
    $_.packageName -eq 'hk.uwu.reareye' -or [string]$_.resId -like 'reareye_import_*'
})
$kept = @($items | Where-Object {
    -not ($_.packageName -eq 'hk.uwu.reareye' -or [string]$_.resId -like 'reareye_import_*')
})

$json = ConvertTo-Json -InputObject $kept -Depth 24
[System.IO.File]::WriteAllText(
    [System.IO.Path]::GetFullPath($OutputRuntime),
    $json,
    [System.Text.UTF8Encoding]::new($false)
)

$removed | ForEach-Object {
    [pscustomobject]@{
        resId = [string]$_.resId
        packageName = [string]$_.packageName
        resourceDirectory = Split-Path -Parent ([string]$_.resLocalPath)
    }
}
