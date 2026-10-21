param([string]$OutputDirectory)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    if (!$OutputDirectory) { $OutputDirectory = Join-Path $root 'build/distributions' }
    $version = ((Get-Content gradle.properties | Where-Object { $_ -match '^modVersion=' }) -split '=', 2)[1]
    $files = @(
        "server/build/libs/ae2-inventory-dashboard-$version-server.jar",
        "client-exporter/build/libs/ae2-dashboard-assets-$version-client-only.jar"
    )
    foreach ($file in $files) {
        if (!(Test-Path -LiteralPath $file)) { throw "Build first: missing $file" }
    }
    New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
    $artifacts = foreach ($file in $files) {
        $name = Split-Path -Leaf $file
        Copy-Item -LiteralPath $file -Destination (Join-Path $OutputDirectory $name) -Force
        [ordered]@{ file = $name; sha256 = (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant(); bytes = (Get-Item -LiteralPath $file).Length }
    }
    $manifest = [ordered]@{
        version = $version
        gitCommit = (git rev-parse HEAD)
        workingTreeDirty = [bool](git status --porcelain)
        createdAt = [DateTimeOffset]::UtcNow.ToString('o')
        minecraft = '1.20.1'
        forge = '47.4.16'
        ae2 = '15.4.10'
        javaTarget = 17
        gradle = '9.4.0'
        modDevGradle = '2.0.86'
        node = (node --version)
        apiVersion = 1
        schemaVersion = 1
        identityContract = 'ae2wi-1.2.0-pre-forge-1.20.1'
        validation = 'See docs/VALIDATION.md; development build, not a full modpack certification.'
        artifacts = @($artifacts)
    }
    $manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $OutputDirectory 'manifest.json') -Encoding utf8NoBOM
    $artifacts | Format-Table file, sha256
} finally { Pop-Location }
