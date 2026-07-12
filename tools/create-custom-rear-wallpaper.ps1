param(
    [Parameter(Mandatory = $true)][string]$ReferenceMrc,
    [Parameter(Mandatory = $true)][string]$OutputMrc
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
Copy-Item -LiteralPath $ReferenceMrc -Destination $OutputMrc -Force

$stream = [System.IO.File]::Open($OutputMrc, [System.IO.FileMode]::Open)
try {
    $archive = [System.IO.Compression.ZipArchive]::new(
        $stream,
        [System.IO.Compression.ZipArchiveMode]::Update,
        $false
    )
    $entry = $archive.GetEntry('manifest.xml')
    if ($null -eq $entry) { throw 'manifest.xml is missing' }
    $reader = [System.IO.StreamReader]::new($entry.Open())
    try { $maml = $reader.ReadToEnd() } finally { $reader.Dispose() }
    if ($maml -notmatch '<Widget\b' -or $maml -notmatch '</Widget>') {
        throw 'manifest.xml is not a MAML Widget document'
    }

    # This final layer deliberately covers the reference wallpaper.  It makes the
    # exported card visually unambiguous while retaining the proven MRC scaffold.
    $overlay = @'
    <Group x="0" y="0" w="#view_width" h="#view_height">
        <Rectangle x="0" y="0" w="#view_width" h="#view_height" fillColor="#FF07101F"/>
        <!-- Xiaomi 17 Pro/Max camera-safe area: keep all meaningful content right of x=340. -->
        <Rectangle x="320" y="34" w="#view_width-354" h="#view_height-68" fillColor="#FF13243D" cornerRadius="32"/>
        <Rectangle x="356" y="76" w="12" h="74" fillColor="#FF35D6D1" cornerRadius="6"/>
        <Text x="390" y="82" text="OUTER VIEW" size="40" color="#FF35D6D1"/>
        <Text x="390" y="132" text="DEEP SPACE CLOCK" size="22" color="#FF93A4BD"/>
        <DateTime x="700" y="#view_height/2-12" align="center" alignV="center" format="HH:mm" size="142" color="#FFFFFFFF"/>
        <Rectangle x="390" y="#view_height-138" w="#view_width-434" h="2" fillColor="#FF35D6D1"/>
        <DateTime x="390" y="#view_height-104" format="yyyy.MM.dd  EEEE" size="27" color="#FFD7E4F4"/>
        <Text x="#view_width-44" y="#view_height-104" align="right" text="ACTIVE" size="27" color="#FF35D6D1"/>
    </Group>
'@
    $maml = $maml -replace '</Widget>', ($overlay + "`n</Widget>")
    $entry.Delete()
    $newEntry = $archive.CreateEntry('manifest.xml', [System.IO.Compression.CompressionLevel]::Optimal)
    $writer = [System.IO.StreamWriter]::new($newEntry.Open(), [System.Text.UTF8Encoding]::new($false))
    try { $writer.Write($maml) } finally { $writer.Dispose() }
} finally {
    if ($null -ne $archive) { $archive.Dispose() }
    $stream.Dispose()
}
