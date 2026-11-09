param([string]$SshKeyFile = "")
$ErrorActionPreference = "Stop"
$projectRoot = Split-Path $PSScriptRoot -Parent
Push-Location $projectRoot
try {
    # Avoid the JDK 17 worker argument-file encoding bug with this Chinese workspace path.
    & .\gradlew.bat :app:exportCheckClasspath --no-daemon --max-workers=1 --console plain
    if ($LASTEXITCODE -ne 0) { throw "Check compilation failed" }
    $runtimeClasspath = Get-Content -LiteralPath "$PSScriptRoot\check-classpath.txt" -Raw -Encoding utf8
    $previousKeyPath = $env:CODEX_CHECK_KEY_FILE
    if ($SshKeyFile) { $env:CODEX_CHECK_KEY_FILE = $SshKeyFile }
    try {
        $java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin\java.exe" } else { "java" }
        $checkClasses = Get-ChildItem -LiteralPath "$PSScriptRoot/checks" -Filter '*Check.kt' |
            Sort-Object Name | ForEach-Object { 'com.codexserver.mobile.' + $_.BaseName }
        & $java "-Dfile.encoding=UTF-8" -cp $runtimeClasspath org.junit.runner.JUnitCore @checkClasses
        if ($LASTEXITCODE -ne 0) { throw "Checks failed" }
    } finally { $env:CODEX_CHECK_KEY_FILE = $previousKeyPath }
} finally { Pop-Location }
