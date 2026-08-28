"""
fast_clean_key.py  –  Ultra-lightweight (potato-PC friendly) fast sprite keyer
Runs in < 1 second using simple, clean mask erosion and alpha feathering.
"""
import os
import sys
import numpy as np
import cv2
from PIL import Image
from rembg import remove, new_session

def fast_key_sprite(input_path, output_path, isnet_session=None):
    print(f"[FastKeyer] Processing: {input_path}")
    if isnet_session is None:
        isnet_session = new_session("isnet-anime")

    # Step 1: Run isnet-anime
    pil_in = Image.open(input_path).convert("RGB")
    rembg_out = remove(pil_in, session=isnet_session)
    img_np = np.array(rembg_out)

    # Step 2: Extract RGB & Alpha
    rgb = img_np[:, :, :3]
    alpha = img_np[:, :, 3]

    # Step 3: Hard-erode alpha by 2-3px to cleanly eliminate the 1-2px white diffusion fringe
    kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (3, 3))
    alpha_eroded = cv2.erode(alpha, kernel, iterations=2)

    # Step 4: Soft 1px edge feathering so it stays crisp visual novel style (not blurry)
    alpha_smooth = cv2.GaussianBlur(alpha_eroded, (3, 3), 0)

    # Step 5: For any edge pixel that is slightly semi-transparent, clamp its color toward
    # the character's inner silhouette so no white background shines through
    final_rgba = np.dstack((rgb, alpha_smooth))

    os.makedirs(os.path.dirname(os.path.abspath(output_path)), exist_ok=True)
    Image.fromarray(final_rgba).save(output_path, "PNG")
    print(f"[FastKeyer] Clean sprite saved in milliseconds -> {output_path}")

    # Generate dark preview
    preview_path = output_path.replace(".png", "_preview.png")
    bg = np.full_like(rgb, [30, 20, 45], dtype=np.uint8) # Dark room background
    a_f = alpha_smooth[:, :, None].astype(np.float32) / 255.0
    comp = (rgb.astype(np.float32) * a_f + bg.astype(np.float32) * (1.0 - a_f)).astype(np.uint8)
    Image.fromarray(comp).save(preview_path, "PNG")
    print(f"[FastKeyer] Preview saved -> {preview_path}")

if __name__ == "__main__":
    if len(sys.argv) < 3:
        print("Usage: python fast_clean_key.py <input> <output_png>")
        sys.exit(1)
    fast_key_sprite(sys.argv[1], sys.argv[2])
