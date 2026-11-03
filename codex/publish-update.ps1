param([string]$ServerAlias = 'andlri-server', [string]$Notes = 'Interface and usability improvements.')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$apk = Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk'
$metadata = Get-Content -LiteralPath (Join-Path (Split-Path $apk -Parent) 'output-metadata.json') -Raw | ConvertFrom-Json
if ($metadata.applicationId -ne 'com.codexserver.mobile') { throw 'Unexpected application ID' }
$version = $metadata.elements[0].versionName
if ($version -notmatch '^\d+\.\d+\.\d+$') { throw 'Invalid version name' }
$hash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
$releaseRoot = '/home/ubuntu/codex/mobile-client/releases'
$remoteApk = "$releaseRoot/codex-server-$version-$($hash.Substring(0,12)).apk"
$manifest = [ordered]@{
    versionCode = [int]$metadata.elements[0].versionCode
    versionName = $version
    apkPath = $remoteApk
    sha256 = $hash
    sizeBytes = (Get-Item -LiteralPath $apk).Length
    notes = $Notes
}
$manifestFile = Join-Path $PSScriptRoot "release-$version.json"
[System.IO.File]::WriteAllText($manifestFile, ($manifest | ConvertTo-Json), (New-Object System.Text.UTF8Encoding($false)))
& ssh -o BatchMode=yes -o ConnectTimeout=15 $ServerAlias "mkdir -p '$releaseRoot'"
if ($LASTEXITCODE -ne 0) { throw 'Cannot access release directory' }
& scp -o BatchMode=yes $apk "${ServerAlias}:$remoteApk.uploading"
if ($LASTEXITCODE -ne 0) { throw 'APK upload failed' }
& ssh -o BatchMode=yes $ServerAlias "echo '$hash  $remoteApk.uploading' | sha256sum -c - && mv '$remoteApk.uploading' '$remoteApk'"
if ($LASTEXITCODE -ne 0) { throw 'Remote APK verification failed' }
& scp -o BatchMode=yes $manifestFile "${ServerAlias}:$releaseRoot/latest.json.uploading"
if ($LASTEXITCODE -ne 0) { throw 'Manifest upload failed' }
& ssh -o BatchMode=yes $ServerAlias "chmod 644 '$remoteApk' '$releaseRoot/latest.json.uploading' && mv '$releaseRoot/latest.json.uploading' '$releaseRoot/latest.json'"
if ($LASTEXITCODE -ne 0) { throw 'Manifest publish failed' }
Write-Output "Published $version ($hash) through SSH"
