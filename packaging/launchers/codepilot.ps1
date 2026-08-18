# CodePilot distribution launcher for PowerShell (Windows).
# Keep CODEPILOT_JAVA as one executable path; pass CLI arguments through the
# PowerShell array so metacharacters are not reparsed by cmd.exe.
[CmdletBinding()]
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [object[]] $CliArguments
)

$ErrorActionPreference = 'Stop'
$jar = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\lib\codepilot-cli.jar'))
if (-not [IO.File]::Exists($jar)) {
    [Console]::Error.WriteLine("codepilot: distribution jar not found: $jar")
    exit 64
}

if ($env:CODEPILOT_JAVA) {
    $javaCommand = $env:CODEPILOT_JAVA
} elseif ($env:JAVA_HOME) {
    $javaCommand = Join-Path $env:JAVA_HOME 'bin\java.exe'
} else {
    $javaCommand = 'java.exe'
}

if ($javaCommand -ne 'java.exe' -and -not [IO.File]::Exists($javaCommand)) {
    [Console]::Error.WriteLine("codepilot: Java executable not found: $javaCommand")
    exit 64
}

& $javaCommand '-jar' $jar @CliArguments
exit $LASTEXITCODE
