import os, sys, struct, zipfile

apk_path = r"D:\projects\maru-android-projects\apps\manime\build\outputs\apk\release\manime-release.apk"
if not os.path.exists(apk_path):
    apk_path = r"D:\projects\maru-android-projects\apps\manime\build\outputs\apk\release\manime-release-unsigned.apk"

work_dir = r"D:\projects\maru-android-projects\build\tmp_16kb"
extract_dir = os.path.join(work_dir, "extracted")
repacked_apk = os.path.join(work_dir, "repacked.apk")

if os.path.exists(work_dir):
    import shutil
    shutil.rmtree(work_dir, ignore_errors=True)
os.makedirs(extract_dir, exist_ok=True)

with zipfile.ZipFile(apk_path, "r") as z:
    z.extractall(extract_dir)

def patch_elf_align(filepath, target_align=0x4000):
    with open(filepath, "r+b") as f:
        ident = f.read(16)
        if not ident.startswith(b"\x7fELF"):
            return
        is_64 = ident[4] == 2
        f.seek(0)
        data = bytearray(f.read())

        if is_64:
            e_phoff = struct.unpack_from("<Q", data, 32)[0]
            e_phentsize = struct.unpack_from("<H", data, 54)[0]
            e_phnum = struct.unpack_from("<H", data, 56)[0]
            for i in range(e_phnum):
                off = e_phoff + i * e_phentsize
                p_type = struct.unpack_from("<I", data, off)[0]
                if p_type == 1: # PT_LOAD
                    p_align = struct.unpack_from("<Q", data, off + 48)[0]
                    if p_align < target_align:
                        struct.pack_into("<Q", data, off + 48, target_align)
        else:
            e_phoff = struct.unpack_from("<I", data, 28)[0]
            e_phentsize = struct.unpack_from("<H", data, 42)[0]
            e_phnum = struct.unpack_from("<H", data, 44)[0]
            for i in range(e_phnum):
                off = e_phoff + i * e_phentsize
                p_type = struct.unpack_from("<I", data, off)[0]
                if p_type == 1: # PT_LOAD
                    p_align = struct.unpack_from("<I", data, off + 28)[0]
                    if p_align < target_align:
                        struct.pack_into("<I", data, off + 28, target_align)

        f.seek(0)
        f.write(data)
        print("Patched 16KB alignment for: " + os.path.basename(filepath))

for root, dirs, files in os.walk(os.path.join(extract_dir, "lib")):
    for file in files:
        if file.endswith(".so"):
            patch_elf_align(os.path.join(root, file))

with zipfile.ZipFile(repacked_apk, "w") as z_out:
    for root, dirs, files in os.walk(extract_dir):
        for file in files:
            full_p = os.path.join(root, file)
            arcname = os.path.relpath(full_p, extract_dir)
            # Store uncompressed: .so files and resources.arsc
            if file.endswith(".so") or file == "resources.arsc":
                z_out.write(full_p, arcname, compress_type=zipfile.ZIP_STORED)
            else:
                z_out.write(full_p, arcname, compress_type=zipfile.ZIP_DEFLATED)
print("Repacked APK successfully.")
