from PIL import Image
files = ['nami_pout_new.png', 'nami_love_heart.png', 'nami_tea_cup.png']
base = "D:/projects/maru-android-projects/apps/nami-space/src/main/res/drawable/"
for f in files:
    img = Image.open(base + f)
    print(f"{f}: mode={img.mode}, size={img.size}")
    if img.mode == "RGBA":
        alpha_vals = list(img.split()[3].getdata())
        transparent_px = sum(1 for a in alpha_vals if a < 10)
        total_px = len(alpha_vals)
        print(f"  -> {transparent_px}/{total_px} transparent pixels ({100*transparent_px//total_px}% background cut)")
    else:
        print("  -> WARNING: No alpha channel!")
