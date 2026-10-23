[CmdletBinding()]
param(
    [string]$Version = "0.3.0"
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$javaHome = "C:\tools\java\jdk-17.0.13"
$androidHome = "C:\tools\android\sdk"
$gradle = "C:\tools\gradle\gradle-9.2.1\bin\gradle.bat"
$keyTool = Join-Path $javaHome "bin\keytool.exe"
$zipAlign = Join-Path $androidHome "build-tools\36.1.0\zipalign.exe"
$apkSigner = Join-Path $androidHome "build-tools\36.1.0\apksigner.bat"
$signingDir = Join-Path $projectRoot "signing"
$keyStore = Join-Path $signingDir "TabletDisplayRelease.p12"
$passwordFile = Join-Path $signingDir "keystore-password.txt"
$unsignedApk = Join-Path $projectRoot "app\build\outputs\apk\release\app-release-unsigned.apk"
$dist = Join-Path $projectRoot "dist"
$alignedApk = Join-Path $dist "TabletDisplayAndroid-$Version-aligned.apk"
$releaseApk = Join-Path $dist "TabletDisplayAndroid-$Version.apk"

foreach ($path in @($keyTool, $zipAlign, $apkSigner, $gradle)) {
    if (-not (Test-Path -LiteralPath $path)) { throw "Required release tool not found: $path" }
}
$env:JAVA_HOME = $javaHome
$env:ANDROID_HOME = $androidHome
$env:GRADLE_USER_HOME = "C:\Users\ka\.gradle"
$env:Path = "$javaHome\bin;$androidHome\platform-tools;$env:Path"

New-Item -ItemType Directory -Force -Path $signingDir | Out-Null
if (-not (Test-Path -LiteralPath $keyStore)) {
    $bytes = New-Object byte[] 24
    $generator = New-Object System.Security.Cryptography.RNGCryptoServiceProvider
    $generator.GetBytes($bytes)
    $generator.Dispose()
    $password = ($bytes | ForEach-Object { $_.ToString("x2") }) -join ""
    [IO.File]::WriteAllText($passwordFile, $password, (New-Object Text.UTF8Encoding($false)))
    & $keyTool -genkeypair -v `
        -keystore $keyStore -storetype PKCS12 `
        -storepass $password -keypass $password `
        -alias tabletdisplay -keyalg RSA -keysize 3072 -validity 10000 `
        -dname "CN=TabletDisplay, OU=Local Release, O=TabletDisplay, C=UA"
    if ($LASTEXITCODE -ne 0) { throw "Could not create Android release key." }
}
if (-not (Test-Path -LiteralPath $passwordFile)) {
    throw "Keystore password file is missing: $passwordFile"
}
$password = [IO.File]::ReadAllText($passwordFile, [Text.Encoding]::UTF8).Trim()
if (-not $password) { throw "Keystore password is empty." }

Push-Location $projectRoot
try {
    & $gradle --no-daemon :app:testDebugUnitTest :app:assembleRelease
    if ($LASTEXITCODE -ne 0) { throw "Android release build failed with exit code $LASTEXITCODE." }
}
finally {
    Pop-Location
}
if (-not (Test-Path -LiteralPath $unsignedApk)) { throw "Unsigned release APK was not produced." }

New-Item -ItemType Directory -Force -Path $dist | Out-Null
foreach ($path in @($alignedApk, $releaseApk)) {
    if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path -Force }
}
& $zipAlign -f -p 4 $unsignedApk $alignedApk
if ($LASTEXITCODE -ne 0) { throw "zipalign failed with exit code $LASTEXITCODE." }
& $apkSigner sign `
    --ks $keyStore --ks-key-alias tabletdisplay `
    --ks-pass "pass:$password" --key-pass "pass:$password" `
    --out $releaseApk $alignedApk
if ($LASTEXITCODE -ne 0) { throw "APK signing failed with exit code $LASTEXITCODE." }
Remove-Item -LiteralPath $alignedApk -Force
& $apkSigner verify --verbose --print-certs $releaseApk
if ($LASTEXITCODE -ne 0) { throw "APK signature verification failed." }
$stream = [IO.File]::OpenRead($releaseApk)
$hasher = [Security.Cryptography.SHA256]::Create()
try {
    $hash = (($hasher.ComputeHash($stream) | ForEach-Object { $_.ToString("X2") }) -join "")
}
finally {
    $hasher.Dispose()
    $stream.Dispose()
}
Write-Output "Android release: $releaseApk"
Write-Output "SHA256: $hash"
Write-Output "Signing key: $keyStore"
Write-Output "Back up the signing key and password file to keep future APK upgrades compatible."
