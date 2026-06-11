# run.ps1 — launch structurizr-renderer from PowerShell
# Usage:  .\run.ps1 path\to\file.dsl [options]
# Options are forwarded directly to the JAR (--help for full list).

$jar = Join-Path $PSScriptRoot "target\structurizr-renderer-1.0.0-SNAPSHOT.jar"

if (-not (Test-Path $jar)) {
    Write-Error "JAR not found at $jar`nBuild first: .\mvnw.cmd package -DskipTests"
    exit 1
}

& java -jar $jar @args
exit $LASTEXITCODE
