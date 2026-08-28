import os
import sys
import numpy as np
from PIL import Image
from rembg import remove, new_session
from pymatting import estimate_foreground_ml, load_image, save_image

def process_sprite_matting(input_path, output_path, isnet_session=None):
    print(f"[Matting Pipeline] Processing: {input_path}")
    if isnet_session is None:
        isnet_session = new_session("isnet-anime")

    # 1. Load original image as float [0, 1] RGB
    img_rgb = load_image(input_path, "RGB")

    # 2. Extract initial alpha with isnet-anime
    pil_in = Image.open(input_path).convert("RGB")
    rembg_out = remove(pil_in, session=isnet_session, post_process_mask=True)
    rembg_np = np.array(rembg_out)
    alpha = rembg_np[:, :, 3].astype(np.float64) / 255.0

    # 3. Use PyMatting's closed-form Maximum-Likelihood Foreground Estimator
    # This solves the true color unmixing equation F = (I - (1-a)B)/a using neighborhood priors
    print("[Matting Pipeline] Estimating true foreground colors (eliminating white matte bleed)...")
    foreground = estimate_foreground_ml(img_rgb, alpha)

    # 4. Save clean RGBA cutout
    os.makedirs(os.path.dirname(os.path.abspath(output_path)), exist_ok=True)
    save_image(output_path, foreground, alpha)
    print(f"[Matting Pipeline] Successfully saved true matte sprite: {output_path}")

    # 5. Generate dark background preview to verify zero fringe
    preview_path = output_path.replace(".png", "_preview.png")
    bg = np.full_like(foreground, [0.12, 0.08, 0.18]) # Dark room purple
    composite = foreground * alpha[:, :, None] + bg * (1.0 - alpha[:, :, None])
    save_image(preview_path, composite)
    print(f"[Matting Pipeline] Saved dark preview: {preview_path}")

if __name__ == "__main__":
    if len(sys.argv) < 3:
        print("Usage: python key_sprites_pymatting.py <input> <output_png>")
        sys.exit(1)
    process_sprite_matting(sys.argv[1], sys.argv[2])
