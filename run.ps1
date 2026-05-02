#!/usr/bin/env pwsh
# Single-run wrapper for youtube-perf-test.
# Sets console encoding to UTF-8 before launching the JVM so that
# box-drawing characters in the report render correctly in any PowerShell terminal.
#
# Usage:
#   .\run.ps1                          Default duration from test.properties (30 s)
#   .\run.ps1 --duration 60            60-second monitoring window
#   .\run.ps1 --duration 300           5-minute monitoring window
#   .\run.ps1 --duration 900           15-minute monitoring window (max)
#   .\run.ps1 | Tee-Object output.txt  Capture output while displaying it
#
# Duration range: 30 – 900 seconds (30 s to 15 minutes).
# Edit test.properties to change URLs, domains, or the default duration.

Set-Location $PSScriptRoot

# Tell PowerShell to decode child-process bytes as UTF-8 before printing.
# Without this, PowerShell uses the system OEM code page (CP437/CP850 on US Windows),
# which maps UTF-8 multi-byte sequences for box-drawing chars to garbage like "Γòö".
$OutputEncoding           = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$JAR = "$PSScriptRoot\target\net-perf-monitor-jar-with-dependencies.jar"
if (-not (Test-Path $JAR)) {
    Write-Error "JAR not found: $JAR  — run 'mvn package' first."
    exit 1
}

& java "-Dfile.encoding=UTF-8" "-Dstdout.encoding=UTF-8" "-Dstderr.encoding=UTF-8" -jar $JAR @args
