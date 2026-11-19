# Installs Shelf on a Quest connected over USB (developer mode on) and grants
# what the headset UI cannot grant to a sideloaded app:
#   - starting itself from the background (autostart),
#   - usage statistics ("recently used", time in app),
#   - the accessibility service that does autostart and library interception.
# Safe to run again after every update.

$pkg = "app.shelf"
$svc = "$pkg/$pkg.WatchService"
$apk = Join-Path $PSScriptRoot "dist\Shelf.apk"

if (-not (Test-Path $apk)) { Write-Host "APK not found: $apk"; exit 1 }

$state = (adb get-state | Out-String).Trim()
if ($state -ne "device") {
    Write-Host "Headset not found. Connect it over USB and allow debugging inside the headset."
    exit 1
}

adb install -r $apk
if ($LASTEXITCODE -ne 0) { Write-Host "Install failed."; exit 1 }

adb shell appops set $pkg SYSTEM_ALERT_WINDOW allow
adb shell appops set $pkg GET_USAGE_STATS allow

# Add our service to the enabled list without dropping services that are already there.
$cur = (adb shell settings get secure enabled_accessibility_services | Out-String).Trim()
if ($cur -eq "" -or $cur -eq "null") {
    $new = $svc
} elseif ($cur -like "*$svc*") {
    $new = $cur
} else {
    $new = "${cur}:$svc"
}
adb shell settings put secure enabled_accessibility_services $new
adb shell settings put secure accessibility_enabled 1

adb shell am start -n "$pkg/.MainActivity"
Write-Host "Done. Shelf is installed and opened in the headset."
