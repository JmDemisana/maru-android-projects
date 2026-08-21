<#
.SYNOPSIS
  Build an app APK locally and publish it as a GitHub Release.

.DESCRIPTION
  Usage: .\release.ps1 -App <appName> [-Version <vX.Y.Z>] [-Draft]

  App names: lastnotif, marucast, tup-ers
  Version: defaults to the versionName from the app's build.gradle.kts.

  Steps:
    1. Determine version from Gradle if not supplied.
    2. Build the APK locally with Gradle (NEVER via GitHub Actions).
       - Uses the release variant if a keystore.properties exists.
       - Falls back to debug (auto-signed, sideloadable) otherwise.
    3. Create + push the prefixed git tag  (e.g. lastnotif/v1.0.0).
    4. Publish the APK via `gh release create`.
#>

param(
    [Parameter(Mandatory=$true)]
    [ValidateSet("lastnotif","marucast","tup-ers")]
    [string]$App,

    [string]$Version = "",

    [string]$Notes = "",

    [string]$NotesFile = "",

    [switch]$Draft
)

$ErrorActionPreference = "Stop"

# Auto-detect valid JAVA_HOME if missing or invalid
if (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
    $candidateJdks = @(
        "C:\Program Files\Android\Android Studio\jbr",
        "C:\Program Files\Java\jdk*",
        "C:\Program Files\Eclipse Adoptium\jdk*"
    )
    foreach ($cand in $candidateJdks) {
        $resolved = Get-Item $cand -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($resolved -and (Test-Path "$($resolved.FullName)\bin\java.exe")) {
            $env:JAVA_HOME = $resolved.FullName
            $env:Path = "$($resolved.FullName)\bin;$env:Path"
            break
        }
    }
}

# Resolve version from build.gradle.kts if not provided
if (-not $Version) {
    $gradleFile = "apps/$App/build.gradle.kts"
    $matchResult = Select-String -Path $gradleFile -Pattern 'versionName\s*=\s*"([^"]+)"'
    if (-not $matchResult) {
        Write-Error "Could not detect versionName from $gradleFile. Pass -Version manually."
    }
    $Version = "v" + $matchResult.Matches[0].Groups[1].Value
}

if (-not $Version.StartsWith("v")) {
    $Version = "v$Version"
}

$tag = "$App/$Version"

Write-Host ""
Write-Host "==> Releasing  $App  $Version  (tag: $tag)" -ForegroundColor Cyan
Write-Host ""

# Sanity check: tag must not already exist locally
$existingTag = git tag --list $tag
if ($existingTag) {
    Write-Error "Tag '$tag' already exists locally. Bump the version in build.gradle.kts first."
}

# Choose variant: release if keystore exists, debug otherwise
$hasKeystore = Test-Path "apps/$App/keystore.properties"
if ($hasKeystore) {
    $variant = "Release"
} else {
    $variant = "Debug"
}

$gradleTask = ":apps:${App}:assemble${variant}"
Write-Host "==> Building  $gradleTask  (variant: $variant)" -ForegroundColor Yellow

.\gradlew $gradleTask
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

# Locate built APK
$variantLower = $variant.ToLower()
$apkPattern = "apps/$App/build/outputs/apk/$variantLower/*.apk"
$apkFiles = Get-Item $apkPattern -ErrorAction SilentlyContinue

if (-not $apkFiles) {
    Write-Error "No APK found at $apkPattern -- did the build succeed?"
}

$apkPath = $apkFiles[0].FullName
Write-Host "==> APK: $apkPath" -ForegroundColor Green

# Create and push git tag
Write-Host ""
Write-Host "==> Creating tag $tag" -ForegroundColor Yellow
git tag $tag
git push origin $tag

# Publish GitHub Release with the APK attached
Write-Host ""
Write-Host "==> Publishing GitHub Release" -ForegroundColor Yellow

$releaseArgs = @(
    "release", "create", $tag,
    $apkPath,
    "--title", "$App $Version"
)

if ($NotesFile -and (Test-Path $NotesFile)) {
    $releaseArgs += @("--notes-file", $NotesFile)
} elseif ($Notes) {
    $releaseArgs += @("--notes", $Notes)
} else {
    $releaseArgs += @("--notes", "Release $Version of $App.")
}

if ($Draft) {
    $releaseArgs += "--draft"
}

gh @releaseArgs

Write-Host ""
Write-Host "==> Done! Release published:" -ForegroundColor Green
Write-Host "    https://github.com/JmDemisana/maru-android-projects/releases/tag/$tag" -ForegroundColor Cyan
Write-Host ""
