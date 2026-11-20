# Creates the key that signs Shelf's release builds, and the keystore.properties the build reads.
#
# Run it once, in the project folder:
#     powershell -ExecutionPolicy Bypass -File tools\make-release-key.ps1
#
# It asks for a password (typed hidden) and needs nothing else. Both files stay on this computer:
# they are in .gitignore and must never be published.
#
# KEEP A COPY of shelf-release.jks and of the password (a password manager and a USB stick are
# good places). Every update of Shelf has to be signed with this same key: if it is lost, nobody
# can update the app any more, only reinstall it and lose their settings.
#
#     -Check    only says which keytool it found, creates nothing

param([switch]$Check)

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
$jks = Join-Path $root "shelf-release.jks"
$props = Join-Path $root "keystore.properties"

function Find-Keytool {
    $found = Get-Command keytool -ErrorAction SilentlyContinue
    if ($found) { return $found.Source }
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += (Join-Path $env:JAVA_HOME "bin\keytool.exe") }
    $candidates += @(
        (Join-Path $env:ProgramFiles "Android\Android Studio\jbr\bin\keytool.exe"),
        (Join-Path $env:ProgramFiles "Android\Android Studio\jre\bin\keytool.exe"),
        (Join-Path $env:LOCALAPPDATA "Programs\Android Studio\jbr\bin\keytool.exe")
    )
    foreach ($base in @($env:ProgramFiles, ${env:ProgramFiles(x86)})) {
        if ($base) {
            $candidates += Get-ChildItem -Path (Join-Path $base "Java"), (Join-Path $base "Eclipse Adoptium"), (Join-Path $base "Microsoft") `
                -Filter keytool.exe -Recurse -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName }
        }
    }
    foreach ($c in $candidates) { if ($c -and (Test-Path $c)) { return $c } }
    return $null
}

$keytool = Find-Keytool
if (-not $keytool) {
    Write-Host "keytool (part of the JDK) was not found. Install a JDK 17 or Android Studio and run this again."
    exit 1
}
Write-Host "keytool: $keytool"
if ($Check) { exit 0 }

if ((Test-Path $jks) -or (Test-Path $props)) {
    Write-Host "shelf-release.jks or keystore.properties already exists. Nothing was changed."
    Write-Host "(Delete them only if you really want a different key: apps signed with the old key cannot be updated by it.)"
    exit 1
}

function Read-Secret($prompt) {
    $s = Read-Host $prompt -AsSecureString
    $ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($s)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr) }
}

Write-Host ""
Write-Host "Choose a password for the key: at least 10 characters, letters, digits and . _ - @ % + only."
$pass = Read-Secret "Password"
$again = Read-Secret "Same password again"
if ($pass -ne $again) { Write-Host "The two passwords differ. Run it again."; exit 1 }
if ($pass -notmatch '^[A-Za-z0-9._\-@%+]{10,}$') {
    Write-Host "The password must be at least 10 characters of letters, digits and . _ - @ % +"
    exit 1
}

# the password goes to keytool through the environment, not through the command line
$env:SHELF_KEY_PASS = $pass
try {
    & $keytool -genkeypair -v -keystore $jks -alias shelf -keyalg RSA -keysize 4096 -validity 10000 `
        -storepass:env SHELF_KEY_PASS -keypass:env SHELF_KEY_PASS -dname "CN=Shelf, O=Shelf launcher"
    if ($LASTEXITCODE -ne 0) { throw "keytool failed" }
} finally {
    Remove-Item Env:SHELF_KEY_PASS -ErrorAction SilentlyContinue
}

$lines = @(
    "storeFile=shelf-release.jks",
    "storePassword=$pass",
    "keyAlias=shelf",
    "keyPassword=$pass"
)
Set-Content -Path $props -Value $lines -Encoding ASCII

Write-Host ""
Write-Host "Done:"
Write-Host "  $jks"
Write-Host "  $props"
Write-Host ""
Write-Host "NOW MAKE A COPY of shelf-release.jks and the password somewhere safe (not in git)."
Write-Host "Then tell Claude it is ready."
