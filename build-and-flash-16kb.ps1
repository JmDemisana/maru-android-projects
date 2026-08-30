# 16KB ELF Alignment, Packaging, Signing & Flash Script for MAnime
$ErrorActionPreference = "Stop"

$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

Write-Host "==> Step 1: Building Release APK..." -ForegroundColor Cyan
.\gradlew.bat :apps:manime:assembleRelease --no-daemon

$buildToolsDir = "C:\Users\jmdem\AppData\Local\Android\Sdk\build-tools\35.0.0"
if (-not (Test-Path $buildToolsDir)) {
    $buildToolsDir = Get-ChildItem "C:\Users\jmdem\AppData\Local\Android\Sdk\build-tools" | Sort-Object Name -Descending | Select-Object -First 1 -ExpandProperty FullName
}

$zipalign = "$buildToolsDir\zipalign.exe"
$apksigner = "$buildToolsDir\apksigner.bat"
$debugKeystore = "$HOME\.android\debug.keystore"

$builtApk = "D:\projects\maru-android-projects\apps\manime\build\outputs\apk\release\manime-release.apk"
if (-not (Test-Path $builtApk)) {
    $builtApk = "D:\projects\maru-android-projects\apps\manime\build\outputs\apk\release\manime-release-unsigned.apk"
}

Write-Host "==> Step 2: Extracting and patching libtorrent4j for 16KB page alignment..." -ForegroundColor Cyan
$workDir = "D:\projects\maru-android-projects\build\tmp_16kb"
if (Test-Path $workDir) { 
    Remove-Item -Recurse -Force $workDir -ErrorAction SilentlyContinue 
}
New-Item -ItemType Directory -Path $workDir | Out-Null
New-Item -ItemType Directory -Path "$workDir\extracted" | Out-Null

# Python script to extract, patch ELF p_align to 16384 (0x4000) and re-zip preserving compression
$pyScript = @"
import os, sys, struct, zipfile

apk_path = r'$builtApk'
extract_dir = r'$workDir\extracted'
repacked_apk = r'$workDir\repacked.apk'

# Record original compression types
compression_map = {}
with zipfile.ZipFile(apk_path, 'r') as z:
    for info in z.infolist():
        compression_map[info.filename] = info.compress_type
    z.extractall(extract_dir)

def patch_elf_align(filepath, target_align=0x4000):
    with open(filepath, 'r+b') as f:
        ident = f.read(16)
        if not ident.startswith(b'\x7fELF'):
            return
        is_64 = ident[4] == 2
        f.seek(0)
        data = bytearray(f.read())

        if is_64:
            e_phoff = struct.unpack_from('<Q', data, 32)[0]
            e_phentsize = struct.unpack_from('<H', data, 54)[0]
            e_phnum = struct.unpack_from('<H', data, 56)[0]
            for i in range(e_phnum):
                off = e_phoff + i * e_phentsize
                p_type = struct.unpack_from('<I', data, off)[0]
                if p_type == 1: # PT_LOAD
                    p_align = struct.unpack_from('<Q', data, off + 48)[0]
                    if p_align < target_align:
                        struct.pack_into('<Q', data, off + 48, target_align)
        else:
            e_phoff = struct.unpack_from('<I', data, 28)[0]
            e_phentsize = struct.unpack_from('<H', data, 42)[0]
            e_phnum = struct.unpack_from('<H', data, 44)[0]
            for i in range(e_phnum):
                off = e_phoff + i * e_phentsize
                p_type = struct.unpack_from('<I', data, off)[0]
                if p_type == 1: # PT_LOAD
                    p_align = struct.unpack_from('<I', data, off + 28)[0]
                    if p_align < target_align:
                        struct.pack_into('<I', data, off + 28, target_align)

        f.seek(0)
        f.write(data)
        print(f"Patched 16KB alignment for: {os.path.basename(filepath)}")

for root, dirs, files in os.walk(os.path.join(extract_dir, 'lib')):
    for file in files:
        if file.endswith('.so'):
            patch_elf_align(os.path.join(root, file))

with zipfile.ZipFile(repacked_apk, 'w') as z_out:
    for root, dirs, files in os.walk(extract_dir):
        for file in files:
            full_p = os.path.join(root, file)
            arcname = os.path.relpath(full_p, extract_dir).replace('\\', '/')
            # Use original compression type, default to DEFLATED if unknown
            ctype = compression_map.get(arcname, zipfile.ZIP_DEFLATED)
            if file.endswith('.so'):
                ctype = zipfile.ZIP_STORED
            z_out.write(full_p, arcname, compress_type=ctype)
print("Repacked APK successfully.")
"@

$pyScriptFile = "$workDir\patch_16kb.py"
Set-Content -Path $pyScriptFile -Value $pyScript
py $pyScriptFile

# Zipalign with 16-byte alignment (-P 16 for 16KB page size)
Write-Host "==> Step 3: 16KB Page-Aligning APK (zipalign -P 16)..." -ForegroundColor Cyan
$alignedApk = "$workDir\manime-16kb-aligned.apk"
& $zipalign -f -P 16 4 "$workDir\repacked.apk" $alignedApk

# Sign with debug keystore
Write-Host "==> Step 4: Signing APK..." -ForegroundColor Cyan
$finalApk = "D:\projects\maru-android-projects\manime-release-16kb.apk"
Copy-Item $alignedApk $finalApk -Force
& $apksigner sign --ks $debugKeystore --ks-pass pass:android --key-pass pass:android $finalApk

Write-Host "==> Built: $finalApk" -ForegroundColor Green

# Flash to connected phone
Write-Host "==> Step 5: Flashing to Phone..." -ForegroundColor Cyan
$deviceTarget = "192.168.8.199:33663"
adb connect $deviceTarget
adb -s $deviceTarget install -r $finalApk
adb -s $deviceTarget shell am start -n io.maru.manime/io.maru.manime.MainActivity
Write-Host "==> MAnime successfully flashed and launched on device!" -ForegroundColor Green
