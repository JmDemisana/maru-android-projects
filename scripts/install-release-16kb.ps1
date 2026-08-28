$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

function Patch-Elf16Kb {
    param([string]$FilePath)
    $bytes = [System.IO.File]::ReadAllBytes($FilePath)
    if ($bytes.Length -lt 16 -or $bytes[0] -ne 0x7F -or $bytes[1] -ne 0x45 -or $bytes[2] -ne 0x4C -or $bytes[3] -ne 0x46) {
        return
    }
    $is64 = ($bytes[4] -eq 2)
    $modified = $false

    if ($is64) {
        $phoff = [System.BitConverter]::ToUInt64($bytes, 0x20)
        $phentsize = [System.BitConverter]::ToUInt16($bytes, 0x36)
        $phnum = [System.BitConverter]::ToUInt16($bytes, 0x38)
        for ($i = 0; $i -lt $phnum; $i++) {
            $offset = [int64]($phoff + ($i * $phentsize))
            $pType = [System.BitConverter]::ToUInt32($bytes, $offset)
            if ($pType -eq 1) { # PT_LOAD
                $alignOffset = $offset + 0x30
                $currentAlign = [System.BitConverter]::ToUInt64($bytes, $alignOffset)
                if ($currentAlign -lt 16384) {
                    $alignBytes = [System.BitConverter]::GetBytes([uint64]16384)
                    [System.Array]::Copy($alignBytes, 0, $bytes, $alignOffset, 8)
                    $modified = $true
                    Write-Host "Patched 64-bit LOAD header $i in $([System.IO.Path]::GetFileName($FilePath)): $currentAlign -> 16384"
                }
            }
        }
    } else {
        $phoff = [System.BitConverter]::ToUInt32($bytes, 0x1C)
        $phentsize = [System.BitConverter]::ToUInt16($bytes, 0x2A)
        $phnum = [System.BitConverter]::ToUInt16($bytes, 0x2C)
        for ($i = 0; $i -lt $phnum; $i++) {
            $offset = [int64]($phoff + ($i * $phentsize))
            $pType = [System.BitConverter]::ToUInt32($bytes, $offset)
            if ($pType -eq 1) { # PT_LOAD
                $alignOffset = $offset + 0x1C
                $currentAlign = [System.BitConverter]::ToUInt32($bytes, $alignOffset)
                if ($currentAlign -lt 16384) {
                    $alignBytes = [System.BitConverter]::GetBytes([uint32]16384)
                    [System.Array]::Copy($alignBytes, 0, $bytes, $alignOffset, 4)
                    $modified = $true
                    Write-Host "Patched 32-bit LOAD header $i in $([System.IO.Path]::GetFileName($FilePath)): $currentAlign -> 16384"
                }
            }
        }
    }

    if ($modified) {
        [System.IO.File]::WriteAllBytes($FilePath, $bytes)
    }
}

$apkDir = "D:\projects\maru-android-projects\apps\manime\build\outputs\apk\release"
$origApk = (Get-Item "$apkDir\*.apk")[0].FullName
$workingApk = "$apkDir\manime-patched.apk"
Copy-Item $origApk $workingApk -Force

$tempDir = "D:\projects\maru-android-projects\apps\manime\build\temp_so_release"
if (Test-Path $tempDir) { Remove-Item $tempDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $tempDir | Out-Null

# Extract only lib folder with jar
Push-Location $tempDir
& "$env:JAVA_HOME\bin\jar.exe" -x -f $workingApk "lib"

# Patch all extracted .so files
Get-ChildItem -Path "$tempDir\lib" -Filter "*.so" -Recurse | ForEach-Object {
    Patch-Elf16Kb -FilePath $_.FullName
}

# Update the original APK in-place with patched .so files
& "$env:JAVA_HOME\bin\jar.exe" --update --no-compress --file $workingApk "lib"
Pop-Location

# 16KB align with zipalign -f -P 16 4
$zipalign = "C:\Users\jmdem\AppData\Local\Android\Sdk\build-tools\35.0.0\zipalign.exe"
$alignedApk = "$apkDir\manime-release-16kb.apk"
if (Test-Path $alignedApk) { Remove-Item $alignedApk -Force }

& $zipalign -f -P 16 4 $workingApk $alignedApk
Write-Host "Zipalign 16KB complete"

# Sign with debug keystore using apksigner
$apksigner = "C:\Users\jmdem\AppData\Local\Android\Sdk\build-tools\35.0.0\apksigner.bat"
$keystore = "$env:USERPROFILE\.android\debug.keystore"
& $apksigner sign --ks $keystore --ks-pass pass:android --ks-key-alias androiddebugkey --key-pass pass:android $alignedApk
Write-Host "Signed 16KB Release APK complete"

# Clean up
Remove-Item $tempDir -Recurse -Force
Remove-Item $workingApk -Force

# Install & Launch on Phone
adb connect 192.168.8.199:33847
adb -s 192.168.8.199:33847 uninstall io.maru.manime
adb -s 192.168.8.199:33847 install -r $alignedApk
adb -s 192.168.8.199:33847 shell monkey -p io.maru.manime -c android.intent.category.LAUNCHER 1
Start-Sleep -Seconds 2
adb -s 192.168.8.199:33847 shell screencap -p /sdcard/manime_maudio_style.png
adb -s 192.168.8.199:33847 pull /sdcard/manime_maudio_style.png "D:\projects\maru-android-projects\manime_maudio_style.png"
adb -s 192.168.8.199:33847 shell rm /sdcard/manime_maudio_style.png
