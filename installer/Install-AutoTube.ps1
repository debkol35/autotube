# AutoTube installer for Windows (run Install-AutoTube-Windows.bat, which starts this).
#
# Android Auto only shows full-screen apps whose install record says "Play Store", and only
# believes that record when the phone's own package installer made the install. Android 14+
# ignores that request from an ordinary app, but accepts it from adb (the USB debugging shell):
#   1. adb installs the APK
#   2. adb opens the phone's installer on that same APK, asking it to record Play Store.
#      You tap "Update" on the phone.
# Run it again after every AutoTube update, or Android Auto hides the app again.

param(
    [string]$Apk = "",
    [string]$Serial = "",     # empty = the phone on USB; or a serial from "adb devices"
    [string]$ToolsDir = (Join-Path $env:LOCALAPPDATA "AutoTube"),
    [switch]$Yes              # don't ask before downloading adb
)

# Not "Stop": in Windows PowerShell 5.1 that turns adb's normal stderr chatter into errors.
$ErrorActionPreference = "Continue"
$pkg = "com.autotube.car"
$googleInstaller = "com.google.android.packageinstaller"

function Say($msg, $color = "Gray") { Write-Host $msg -ForegroundColor $color }

if (-not $Apk) {
    $Apk = (Get-ChildItem -Path $PSScriptRoot -Filter "AutoTube-v*.apk" | Sort-Object LastWriteTime -Descending |
        Select-Object -First 1).FullName
}
if (-not $Apk -or -not (Test-Path $Apk)) { Say "No AutoTube-v*.apk next to this script." Red; exit 1 }
Say "AutoTube installer`nAPK: $Apk`n" Cyan

# ---- 1. adb (Google's Android platform-tools)
$adb = $null
foreach ($c in @((Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"),
                 (Join-Path $ToolsDir "platform-tools\adb.exe"))) {
    if (Test-Path $c) { $adb = $c; break }
}
if (-not $adb) {
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { $adb = $cmd.Source }
}
if (-not $adb) {
    $url = "https://dl.google.com/android/repository/platform-tools-latest-windows.zip"
    Say "This needs Google's 'adb' tool (Android SDK platform-tools, about 8 MB), which isn't on this computer."
    Say "It will be downloaded from $url into $ToolsDir"
    if (-not $Yes) {
        $a = Read-Host "Download it now? [Y/n]"
        if ($a -and $a -notmatch '^[Yy]') { Say "Cancelled." Red; exit 1 }
    }
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    $ProgressPreference = "SilentlyContinue"
    $zip = Join-Path $ToolsDir "platform-tools.zip"
    try {
        New-Item -ItemType Directory -Force $ToolsDir -ErrorAction Stop | Out-Null
        Invoke-WebRequest -Uri $url -OutFile $zip -UseBasicParsing -ErrorAction Stop
        Expand-Archive -Path $zip -DestinationPath $ToolsDir -Force -ErrorAction Stop
        Remove-Item $zip -ErrorAction SilentlyContinue
    } catch {
        Say "Download failed: $($_.Exception.Message)" Red
        exit 1
    }
    $adb = Join-Path $ToolsDir "platform-tools\adb.exe"
    if (-not (Test-Path $adb)) { Say "Download didn't contain adb.exe." Red; exit 1 }
    Say "adb ready.`n" Green
}
$target = if ($Serial) { @("-s", $Serial) } else { @("-d") }

# ---- 2. The phone
function Get-PhoneState {
    $lines = & $adb devices 2>$null | Select-Object -Skip 1 | Where-Object { $_ -match '\S' }
    if ($Serial) { $lines = $lines | Where-Object { $_ -like "$Serial*" } }
    else { $lines = $lines | Where-Object { $_ -notmatch '^emulator-' } }
    if (-not $lines) { return "none" }
    if (@($lines).Count -gt 1 -and -not $Serial) { return "many" }
    return (@($lines)[0] -split '\s+')[1]
}

& $adb start-server 2>$null | Out-Null
$shown = ""
$deadline = (Get-Date).AddMinutes(5)
while ($true) {
    $st = Get-PhoneState
    if ($st -eq "device") { break }
    if ($st -ne $shown) {
        $shown = $st
        switch ($st) {
            "none" {
                Say "Waiting for the phone. If nothing happens:" Yellow
                Say "  - Plug it in with a data USB cable."
                Say "  - Turn on USB debugging: Settings > About phone > tap 'Build number' 7 times,"
                Say "    then Settings > Developer options > USB debugging."
            }
            "unauthorized" { Say "On the PHONE: tap 'Allow' on 'Allow USB debugging?' (tick 'Always allow')." Yellow }
            "offline" { Say "The phone is connected but 'offline': unplug and replug the cable." Yellow }
            "many" { Say "More than one phone is connected. Unplug the others, or run with -Serial <id> (see 'adb devices')." Red; exit 1 }
            default { Say "Phone state: $st" Yellow }
        }
    }
    if ((Get-Date) -gt $deadline) { Say "No phone after 5 minutes. Run this again when it's plugged in." Red; exit 1 }
    Start-Sleep -Seconds 2
}
$model = (& $adb @target shell getprop ro.product.model 2>$null) -join ""
$release = (& $adb @target shell getprop ro.build.version.release 2>$null) -join ""
Say "Phone: $model (Android $release)" Green

function Get-Record {
    $out = & $adb @target shell dumpsys package $pkg 2>$null
    $inst = ($out | Select-String "installerPackageName=(\S+)" | Select-Object -First 1)
    $init = ($out | Select-String "initiatingPackageName=(\S+)" | Select-Object -First 1)
    return @($(if ($inst) { $inst.Matches.Groups[1].Value } else { "" }),
             $(if ($init) { $init.Matches.Groups[1].Value } else { "" }))
}

# ---- 3. Install, then reinstall through the phone's installer with Play Store recorded
Say "`n1/2 Installing AutoTube..." Cyan
& $adb @target install -r "$Apk"
if ($LASTEXITCODE -ne 0) {
    Say "Install failed. If it says INSTALL_FAILED_UPDATE_INCOMPATIBLE, an AutoTube signed by someone else is" Red
    Say "installed: uninstall it on the phone first (your AutoTube settings will be lost)." Red
    exit 1
}

Say "`n2/2 Opening the phone's installer to record Play Store..." Cyan
& $adb @target shell input keyevent KEYCODE_WAKEUP 2>$null | Out-Null
& $adb @target shell am force-stop $pkg
$since = (& $adb @target shell "date '+%m-%d %H:%M:%S.000'") -join ""
& $adb @target shell am start --activity-clear-task -a android.intent.action.INSTALL_PACKAGE `
    -d "content://$pkg.apk/self.apk" -t application/vnd.android.package-archive `
    --es android.intent.extra.INSTALLER_PACKAGE_NAME com.android.vending `
    --ez android.intent.extra.NOT_UNKNOWN_SOURCE true | Out-Null
# Newer phone installers ("PIA v2") drop the Play Store request when it comes from adb, and only
# accept it from AutoTube itself. They log this; then let AutoTube ask (allowed to install apps).
Start-Sleep -Seconds 3
$rejected = (& $adb @target shell "logcat -d -t '$since' -s InstallRepository:E" 2>$null) -join "`n"
if ($rejected -match "is invalid. Remove it") {
    Say "This phone's installer only accepts the request from AutoTube itself: asking AutoTube to do it." Cyan
    & $adb @target shell am force-stop com.google.android.packageinstaller
    & $adb @target shell appops set --user 0 $pkg REQUEST_INSTALL_PACKAGES allow
    & $adb @target shell am start -n "$pkg/.MainActivity" --ez com.autotube.car.SETUP true | Out-Null
}
Say ">>> On the PHONE: unlock it and tap 'Update'." Yellow
Say "    If Play Protect asks: 'More details' > 'Install without scanning' (or 'Install anyway')." Yellow

$deadline = (Get-Date).AddMinutes(5)
do {
    Start-Sleep -Seconds 3
    $rec = Get-Record
} while ($rec[0] -ne "com.android.vending" -and (Get-Date) -lt $deadline)

Say "`nInstall record: installer=$($rec[0])  made by=$($rec[1])"
if ($rec[0] -eq "com.android.vending" -and $rec[1] -eq $googleInstaller) {
    Say "Done: Android Auto will accept AutoTube." Green
    Say "Next on the phone: open AutoTube once (allow location 'all the time'), then"
    Say "Settings > Android Auto > Customize launcher > turn on AutoTube."
    exit 0
} elseif ($rec[0] -eq "com.android.vending") {
    Say "Recorded as Play Store, but this phone's installer is '$($rec[1])', not Google's." Yellow
    Say "Android Auto may still hide AutoTube on this phone. Check Customize launcher." Yellow
    exit 0
} else {
    Say "Not recorded as Play Store yet (was 'Update' tapped on the phone?). Run this again." Red
    exit 2
}
