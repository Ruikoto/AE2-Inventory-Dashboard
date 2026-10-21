param([Parameter(ValueFromRemainingArguments=$true)][string[]]$GradleArgs)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    if (!$env:JAVA_HOME) { throw 'Set JAVA_HOME to a full JDK 25 before building.' }
    $options = @()
    if ($env:HTTPS_PROXY) {
        $buildProxy = [Uri]$env:HTTPS_PROXY
        if ($buildProxy.UserInfo) { throw 'Configure authenticated Java proxy settings privately in GRADLE_USER_HOME.' }
        $options += "-Dhttps.proxyHost=$($buildProxy.Host)", "-Dhttps.proxyPort=$($buildProxy.Port)"
        $options += "-Dhttp.proxyHost=$($buildProxy.Host)", "-Dhttp.proxyPort=$($buildProxy.Port)"
    }
    & ./gradlew.bat @options @GradleArgs
    exit $LASTEXITCODE
} finally { Pop-Location }
