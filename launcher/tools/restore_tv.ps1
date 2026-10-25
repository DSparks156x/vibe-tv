# Restore packages removed/disabled during a TV cleanup (see removed_packages.txt for an example list).
# Usage: .\restore_tv.ps1 -Tv 192.168.1.50                      (restore everything in the list)
#        .\restore_tv.ps1 -Tv 192.168.1.50 com.netflix.ninja    (restore one)
# Wireless debugging must be on; the port changes every reboot, so it's discovered via mDNS.
param([Parameter(Mandatory)][string]$Tv, [string[]]$Packages)
$m = adb mdns services | Select-String "_adb-tls-connect._tcp\s+($([regex]::Escape($Tv)):\d+)"
if (-not $m) { throw 'TV not found - turn on Wireless debugging' }
$s = $m.Matches[0].Groups[1].Value
adb connect $s | Out-Null
if (-not $Packages) { $Packages = Get-Content "$PSScriptRoot\removed_packages.txt" }
foreach ($p in $Packages) {
    adb -s $s shell cmd package install-existing $p
    adb -s $s shell pm enable $p
}
# If stock YouTube was replaced by the rebranded TizenTube, uninstall it (io.gh.reisxd.tizentube.cobalt)
# and reinstall the original TizenTube or YouTube APK to go back fully.
