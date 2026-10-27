<#
  install.ps1 - build TubeOffline and install it on a USB-connected phone.

  Usage (from the repo folder, e.g. in Cursor's terminal):
    .\install.ps1              # build debug APK and install
    .\install.ps1 -SkipBuild   # install the last built APK without rebuilding

  Phone setup (once): Settings > About phone > tap "Build number" 7 times,
  then Settings > System > Developer options > USB debugging ON.
  Plug in via USB and accept the "Allow USB debugging?" prompt on the phone.
#>
param([switch]$SkipBuild)

$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

function Fail($msg) { Write-Host "ERROR: $msg" -ForegroundColor Red; exit 1 }

# --- Locate the Android SDK ---
$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
if (-not $sdk) { $sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
if (-not (Test-Path $sdk)) {
    Fail "Android SDK not found at '$sdk'. Install Android Studio (it installs the SDK) or set ANDROID_HOME."
}
Write-Host "Android SDK: $sdk"

# --- local.properties (Gradle needs sdk.dir) ---
if (-not (Test-Path 'local.properties')) {
    $escaped = ($sdk -replace '\\', '\\') -replace ':', '\:'
    "sdk.dir=$escaped" | Set-Content -Encoding ASCII 'local.properties'
    Write-Host "Created local.properties"
}

# --- Java (JDK 17+). Fall back to Android Studio's bundled JDK. ---
if (-not $env:JAVA_HOME -and -not (Get-Command java -ErrorAction SilentlyContinue)) {
    $jbr = @(
        "$env:ProgramFiles\Android\Android Studio\jbr",
        "$env:LOCALAPPDATA\Programs\Android Studio\jbr"
    ) | Where-Object { Test-Path $_ } | Select-Object -First 1
    if ($jbr) { $env:JAVA_HOME = $jbr; Write-Host "Using Android Studio JDK: $jbr" }
    else { Fail "No Java found. Install JDK 17+ or Android Studio." }
}

# --- adb ---
$adb = Join-Path $sdk 'platform-tools\adb.exe'
if (-not (Test-Path $adb)) {
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { $adb = $cmd.Source } else { Fail "adb not found. Install 'Android SDK Platform-Tools' from the SDK Manager." }
}

# --- Build ---
$apk = 'app\build\outputs\apk\debug\app-debug.apk'
if (-not $SkipBuild) {
    Write-Host "`nBuilding debug APK (first build downloads dependencies and can take several minutes)..." -ForegroundColor Cyan
    & .\gradlew.bat assembleDebug
    if ($LASTEXITCODE -ne 0) { Fail "Gradle build failed." }
}
if (-not (Test-Path $apk)) { Fail "APK not found at $apk. Run without -SkipBuild." }

# --- Device check ---
& $adb start-server | Out-Null
$devices = & $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match '\S' }
$ready = $devices | Where-Object { $_ -match '\tdevice$' }
if (-not $ready) {
    if ($devices -match 'unauthorized') { Fail "Phone is connected but not authorized. Unlock it and accept the USB debugging prompt, then rerun." }
    Fail "No phone found. Connect it by USB with USB debugging enabled, then rerun."
}
if (@($ready).Count -gt 1) { Fail "More than one device connected. Unplug the extras and rerun." }

# --- Install ---
Write-Host "`nInstalling on $(($ready -split "`t")[0])..." -ForegroundColor Cyan
& $adb install -r $apk
if ($LASTEXITCODE -ne 0) {
    Fail "Install failed. If it mentions a signature mismatch, run: `"$adb`" uninstall com.tubeoffline.app  (your saved videos stay on the phone)"
}
Write-Host "`nDone! Share a YouTube link and pick 'Download offline'." -ForegroundColor Green
