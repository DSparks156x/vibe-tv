# Optional: relabel a TizenTube Cobalt APK as "YouTube" and add vnd.youtube: link handling, so the
# launcher's trailer links and the stock YouTube tile open it. Signs it with a local keystore
# (created on first run) and optionally installs it.
#
# Usage: .\rebrand_tizentube.ps1 -Apk path\to\tizentube.apk -Apktool path\to\apktool.jar
#            [-IconDir dir] [-Device ip:port]
#   -IconDir  optional folder with banner.png (320x180) and ic_launcher.png to replace TizenTube's art.
#   The same keystore is reused on later runs, so updates install over the top and keep your login.
#   Needs Java and the Android SDK build-tools (ANDROID_HOME or the default SDK location).
param(
    [Parameter(Mandatory)][string]$Apk,
    [Parameter(Mandatory)][string]$Apktool,
    [string]$IconDir,
    [string]$Device,
    [string]$Keystore = "$PSScriptRoot\out\tizentube.keystore",
    [string]$KeystorePass = 'tizentube'
)
$ErrorActionPreference = 'Stop'
$sdk  = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$bt   = Get-ChildItem "$sdk\build-tools" | Sort-Object Name | Select-Object -Last 1 -ExpandProperty FullName
$out  = Split-Path $Keystore
$w    = "$env:TEMP\tvb"   # short path: aapt2 breaks on long Windows paths
New-Item -ItemType Directory $out -Force | Out-Null
Remove-Item $w -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory $w | Out-Null

if (-not (Test-Path $Keystore)) {
    keytool -genkeypair -keystore $Keystore -storepass $KeystorePass -keypass $KeystorePass `
        -alias tizentube -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=TizenTube rebrand'
}

java -jar $Apktool d -f -s $Apk -o "$w\tz"
if ($LASTEXITCODE) { throw 'decode failed' }

if ($IconDir) {
    Copy-Item "$IconDir\banner.png" "$w\tz\res\drawable-xhdpi\app_banner.png" -Force
    foreach ($d in 'mdpi','hdpi','xhdpi','xxhdpi','xxxhdpi') {
        if (Test-Path "$w\tz\res\mipmap-$d") { Copy-Item "$IconDir\ic_launcher.png" "$w\tz\res\mipmap-$d\ic_app.png" -Force }
    }
}

$m = Get-Content "$w\tz\AndroidManifest.xml" -Raw
$m = $m -replace '(<application[^>]*?android:label=")[^"]*"', '$1YouTube"'
if ($m -notmatch 'vnd\.youtube') {
    $vnd = '<intent-filter><action android:name="android.intent.action.VIEW"/><category android:name="android.intent.category.DEFAULT"/><category android:name="android.intent.category.BROWSABLE"/><data android:scheme="vnd.youtube"/></intent-filter>'
    $m = [regex]::new('(<activity[^>]*android:name="dev\.cobalt\.app\.MainActivity"[\s\S]*?)(</activity>)').Replace($m, "`$1$vnd`$2", 1)
}
Set-Content "$w\tz\AndroidManifest.xml" $m -NoNewline

java -jar $Apktool b "$w\tz" -o "$w\unsigned.apk"
if ($LASTEXITCODE) { throw 'build failed' }
& "$bt\zipalign.exe" -f -p 4 "$w\unsigned.apk" "$w\aligned.apk"
& "$bt\apksigner.bat" sign --ks $Keystore --ks-pass "pass:$KeystorePass" --out "$out\YouTube-TizenTube.apk" "$w\aligned.apk"
Write-Host "Built $out\YouTube-TizenTube.apk"

if ($Device) { adb -s $Device install -r "$out\YouTube-TizenTube.apk" }
