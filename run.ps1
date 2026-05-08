#!/usr/bin/env pwsh
# Single-run wrapper for youtube-perf-test.
# Sets console encoding to UTF-8 before launching the JVM so that
# box-drawing characters in the report render correctly in any PowerShell terminal.
#
# Usage:
#   .\run.ps1                                Default mode (all probes), duration from test.properties
#   .\run.ps1 --duration 60                  60-second monitoring window, all probes
#   .\run.ps1 --mode youtube                 YouTube probe only
#   .\run.ps1 --mode website                 Website probe only
#   .\run.ps1 --mode dns                     DNS probe only
#   .\run.ps1 --mode youtube,website         YouTube + Website (no DNS)
#   .\run.ps1 --mode youtube,dns             YouTube + DNS (no Website)
#   .\run.ps1 --mode website,dns             Website + DNS (no YouTube)
#   .\run.ps1 --mode all                     All three probes (explicit)
#   .\run.ps1 --mode all --duration 60       All probes, 60-second window
#   .\run.ps1 --mode all --duration 30 --report 5   Print JSON every 5 s, final at end
#   .\run.ps1 --mode dns --report 10                DNS-only, snapshot every 10 s
#
# Output: stdout + stderr are shown live in the terminal AND saved to
#         target\run-<yyyyMMdd-HHmmss>.log alongside the HTML report.
#
# Duration range: 30-900 seconds (30 s to 15 minutes).
# Edit test.properties to change URLs, domains, or the default duration.

Set-Location $PSScriptRoot

# Tell PowerShell to decode child-process bytes as UTF-8 before printing.
# Without this, PowerShell uses the system OEM code page (CP437/CP850 on US Windows),
# which maps UTF-8 multi-byte sequences for box-drawing chars to garbage.
$OutputEncoding           = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$JAR = "$PSScriptRoot\target\net-perf-monitor-jar-with-dependencies.jar"
if (-not (Test-Path $JAR)) {
    Write-Error ('JAR not found: ' + $JAR + ' - run mvn package first.')
    exit 1
}

$LogFile = "$PSScriptRoot\target\run-$(Get-Date -Format 'yyyyMMdd-HHmmss').log"
Write-Host ('Logging to: ' + $LogFile)

$javaArgs = @('-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8', '-jar', $JAR) + @($args)

# 2>&1 redirects native stderr to PowerShell success stream before it gets
# wrapped as ErrorRecord objects (which cause spurious NativeCommandError noise
# when SLF4J logs to stderr). ToString() normalises both strings and ErrorRecords.
& java @javaArgs 2>&1 | ForEach-Object { $_.ToString() } | Tee-Object -FilePath $LogFile