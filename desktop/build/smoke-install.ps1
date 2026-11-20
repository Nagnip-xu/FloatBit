$ErrorActionPreference = 'Stop'
$installer = (Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot '../dist') -Filter '*.exe' | Select-Object -First 1).FullName
if (!$installer) { throw 'Installer missing' }
# This test runs only in the disposable GitHub Actions Windows VM.
if (!$env:RUNNER_TEMP) { throw 'Run this installation test inside the CI runner' }
$installRoot = Join-Path $env:RUNNER_TEMP 'FloatBit-安装验证\FloatBit'
for ($attempt = 1; $attempt -le 2; $attempt++) {
    $process = Start-Process -FilePath $installer -ArgumentList "/S /currentuser /D=$installRoot" -WindowStyle Hidden -Wait -PassThru
    if ($process.ExitCode -ne 0) { throw "Installation $attempt failed: $($process.ExitCode)" }
    foreach ($relative in @('FloatBit.exe', 'resources\app.asar', 'Uninstall FloatBit.exe')) {
        if (!(Test-Path -LiteralPath (Join-Path $installRoot $relative))) { throw "Missing installed file: $relative" }
    }
    Write-Output "Installation/upgrade $attempt succeeded"
}
$appProcess = Start-Process -FilePath (Join-Path $installRoot 'FloatBit.exe') -WindowStyle Hidden -PassThru
Start-Sleep -Seconds 5
$appProcess.Refresh()
if ($appProcess.HasExited) { throw "Installed app exited unexpectedly: $($appProcess.ExitCode)" }
Write-Output 'Installed Electron application stayed running'
# Only terminate executables belonging to this test installation.
Get-CimInstance Win32_Process | Where-Object {
    $_.ExecutablePath -and $_.ExecutablePath.StartsWith($installRoot + '\', [StringComparison]::OrdinalIgnoreCase)
} | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
$uninstall = Start-Process -FilePath (Join-Path $installRoot 'Uninstall FloatBit.exe') -ArgumentList '/S /currentuser' -WindowStyle Hidden -Wait -PassThru
if ($uninstall.ExitCode -ne 0) { throw "Uninstall failed: $($uninstall.ExitCode)" }
for ($poll = 0; $poll -lt 30 -and (Test-Path -LiteralPath (Join-Path $installRoot 'FloatBit.exe')); $poll++) {
    Start-Sleep -Seconds 1
}
if (Test-Path -LiteralPath (Join-Path $installRoot 'FloatBit.exe')) { throw 'Uninstall left the application executable behind' }
Write-Output 'Silent uninstall succeeded'
