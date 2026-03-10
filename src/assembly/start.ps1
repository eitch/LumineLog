#Requires -Version 5.1
$ErrorActionPreference = 'Stop'

# Resolve script directory
$ScriptDir = $PSScriptRoot

# Locate bundled Zulu JRE under runtime\ (prefer) and fall back to system Java
$javaBin = $null
$runtimeDir = Join-Path $ScriptDir 'runtime'
if (Test-Path -LiteralPath $runtimeDir) {
    try {
        $candidate = Get-ChildItem -LiteralPath $runtimeDir -Recurse -File -Filter 'java.exe' -ErrorAction SilentlyContinue |
            Where-Object { $_.FullName -match "\\bin\\java\.exe$" } |
            Select-Object -First 1
        if ($candidate) {
            $javaBin = $candidate.FullName
        }
    } catch {
        # ignore
    }
}

if (-not $javaBin) {
    $cmd = Get-Command java -ErrorAction SilentlyContinue
    if ($cmd) {
        $javaBin = $cmd.Path
    } else {
        Write-Error "Error: No Java runtime found. Neither bundled runtime nor system Java is available."
        exit 1
    }
}

$appJar = Join-Path $ScriptDir 'Tail4j.jar'

if (-not (Test-Path -LiteralPath $appJar)) {
    Write-Error "Error: Application jar not found: $appJar"
    exit 1
}

Write-Host "Using java $javaBin"

# Build argument list
$argsList = @(
    "--add-modules", "javafx.controls",
    "--enable-native-access=javafx.graphics",
    "-jar", $appJar
)

# Forward any additional arguments passed to the script
if ($args.Count -gt 0) {
    $argsList += $args
}

# Execute Java and propagate exit code
& $javaBin @argsList
exit $LASTEXITCODE
