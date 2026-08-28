"""
key_sprites.py  –  isnet-anime + erode + gaussian feather + defringe despill
Usage:
    python key_sprites.py <input> <output_png> [erode_size] [erode_iter]

Despill strategy:
  For each semi-transparent pixel we do white-matte correction:
      C_corrected = (C_observed - (1 - alpha)) / alpha
  But ONLY if the pixel is very bright (looks like it came from a white bg).
  Fully opaque pixels are untouched.
  We also run an additional "matting" pass: for pixels at the silhouette
  edge that are suspiciously bright, we clamp their RGB toward the average
  of their nearest fully-opaque neighbors.
"""
import os
import sys
import numpy as np
import cv2
from PIL import Image
from rembg import remove, new_session


def white_despill(img_np: np.ndarray) -> np.ndarray:
    """
    White-background matte correction on semi-transparent pixels.
    Also applies neighbor-guided defringe using local median of opaque area.
    """
    out = img_np.astype(np.float32)
    a = out[:, :, 3] / 255.0  # alpha in [0,1]

    # Build a mask of semi-transparent edge pixels
    semi = (a > 0.01) & (a < 0.98)

    # Step A: White matte recovery
    for c in range(3):
        ch = out[:, :, c] / 255.0
        alpha_safe = np.where(a > 1e-5, a, 1e-5)
        recovered = (ch - (1.0 - a)) / alpha_safe
        recovered = np.clip(recovered, 0.0, 1.0)
        # Only apply to semi-transparent pixels
        out[:, :, c] = np.where(semi, recovered * 255.0, out[:, :, c])

    # Step B: Defringe – clamp suspiciously bright edge pixels
    # by pulling them toward the local median of opaque neighbors
    opaque_mask = (a > 0.95).astype(np.float32)

    # Dilate opaque mask to get "neighbor opaque" region
    kernel9 = np.ones((9, 9), np.float32)
    neighbor_weight = cv2.dilate(opaque_mask, kernel9, iterations=1)

    for c in range(3):
        ch_f = out[:, :, c]  # float32 [0,255]
        # weighted local mean of opaque neighbors
        neighbor_sum = cv2.filter2D(ch_f * opaque_mask, -1, kernel9)
        neighbor_cnt = cv2.filter2D(opaque_mask, -1, kernel9) + 1e-6
        neighbor_mean = neighbor_sum / neighbor_cnt

        # For semi-transparent pixels that are brighter than neighbor mean,
        # push RGB toward neighbor mean (softly blend 60%)
        is_bright_fringe = semi & (ch_f > neighbor_mean + 20)
        blend = np.where(is_bright_fringe, 0.6, 0.0)
        out[:, :, c] = ch_f * (1.0 - blend) + neighbor_mean * blend

    # Step C: Zero out fully transparent pixels to avoid dark/white blobs
    out[:, :, 0] = np.where(a < 0.005, 0.0, out[:, :, 0])
    out[:, :, 1] = np.where(a < 0.005, 0.0, out[:, :, 1])
    out[:, :, 2] = np.where(a < 0.005, 0.0, out[:, :, 2])

    return np.clip(out, 0, 255).astype(np.uint8)


def process_image(
    input_path: str,
    output_path: str,
    erode_kernel_size: int = 3,
    erode_iterations: int = 2,
):
    print(f"Processing: {input_path}")
    session = new_session("isnet-anime")

    inp = Image.open(input_path).convert("RGBA")
    out = remove(inp, session=session, post_process_mask=True)
    img_np = np.array(out)

    # -- Step 1: Erode alpha mask to cut into white fringe --
    alpha = img_np[:, :, 3]
    if erode_kernel_size > 0:
        kernel = cv2.getStructuringElement(
            cv2.MORPH_ELLIPSE, (erode_kernel_size, erode_kernel_size)
        )
        alpha = cv2.erode(alpha, kernel, iterations=erode_iterations)

    # -- Step 2: Feather with Gaussian blur --
    alpha = cv2.GaussianBlur(alpha, (3, 3), 0)
    img_np[:, :, 3] = alpha

    # -- Step 3: White-matte despill + neighbor defringe --
    img_np = white_despill(img_np)

    final_img = Image.fromarray(img_np)
    os.makedirs(os.path.dirname(os.path.abspath(output_path)), exist_ok=True)
    final_img.save(output_path, "PNG")
    print(f"Saved: {output_path}  ({final_img.size[0]}x{final_img.size[1]})")

    # Also save a dark-bg preview for inspection
    preview_path = output_path.replace(".png", "_preview.png")
    bg = np.full((final_img.size[1], final_img.size[0], 3), [30, 20, 45], dtype=np.uint8)
    fg = np.array(final_img).astype(np.float32)
    a_f = fg[:, :, 3:4] / 255.0
    composite = fg[:, :, :3] * a_f + bg * (1.0 - a_f)
    Image.fromarray(composite.astype(np.uint8)).save(preview_path, "PNG")
    print(f"Preview on dark bg: {preview_path}")


if __name__ == "__main__":
    if len(sys.argv) < 3:
        print("Usage: python key_sprites.py <input> <output_png> [erode_size] [erode_iter]")
        sys.exit(1)

    in_file  = sys.argv[1]
    out_file = sys.argv[2]
    k_size   = int(sys.argv[3]) if len(sys.argv) > 3 else 3
    k_iter   = int(sys.argv[4]) if len(sys.argv) > 4 else 2

    process_image(in_file, out_file, k_size, k_iter)
