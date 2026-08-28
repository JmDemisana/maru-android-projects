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

# Test on APK
$apk = (Get-Item "D:\projects\maru-android-projects\apps\manime\build\outputs\apk\debug\*.apk")[0].FullName
$tempDir = "D:\projects\maru-android-projects\apps\manime\build\temp_apk"
if (Test-Path $tempDir) { Remove-Item $tempDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $tempDir | Out-Null

Expand-Archive -Path $apk -DestinationPath $tempDir -Force
Get-ChildItem -Path "$tempDir\lib" -Filter "*.so" -Recurse | ForEach-Object {
    Patch-Elf16Kb -FilePath $_.FullName
}

Write-Host "All ELF files patched successfully!"
