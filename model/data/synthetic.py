"""Synthetic receipt/menu generator (DESIGN.md §5.2): "render receipt/menu textures, apply
thin-plate-spline warp (curl), perspective transform, lighting gradient, motion blur, JPEG
recompression, composite onto varied backgrounds."

Deviation from "thin-plate-spline": scipy's TPS/RBF interpolation and opencv-contrib's
dedicated TPS transformer both add dependencies this pipeline doesn't otherwise need. The
curl step instead upsamples a coarse random displacement grid (bicubic) into a dense
per-pixel field and applies it with `cv2.remap` — the same smooth, non-linear, corner-anchored
warp family, without the extra dependency. Documented here rather than silently relabeled.

Mask semantics: DESIGN.md §D2 — a per-pixel document/paper mask, not a text mask. Ground
truth is 1 wherever the (curled, perspective-warped, placed) receipt/menu rectangle covers
the canvas, regardless of what's drawn on it.
"""
from __future__ import annotations

import io
from dataclasses import dataclass

import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont

CANVAS_SIZE = 256

_MERCHANTS = ["CAFE MADHAV", "CORNER BISTRO", "STREET EATS", "GOLDEN WOK", "TAQUERIA SOL"]
_ITEMS = [
    "Burger", "Fries", "Soda", "Salad", "Coffee", "Iced Tea", "Pasta", "Pizza Slice",
    "Curry Bowl", "Spring Rolls", "Fried Rice", "Noodle Soup", "Sandwich", "Smoothie",
]


@dataclass
class RenderedDocument:
    image: np.ndarray  # HxWx3 uint8, RGB
    mask: np.ndarray  # HxWx1 float32 in [0, 1]


def _document_content(rng: np.random.Generator) -> list[str]:
    lines = [rng.choice(_MERCHANTS)]
    num_items = int(rng.integers(3, 8))
    for _ in range(num_items):
        qty = int(rng.integers(1, 4))
        item = rng.choice(_ITEMS)
        price = rng.uniform(1.5, 25.0)
        prefix = f"{qty}x " if qty > 1 else ""
        lines.append(f"{prefix}{item}  {price:.2f}")
    lines.append(f"Total  {rng.uniform(10, 80):.2f}")
    return lines


def render_receipt_texture(rng: np.random.Generator, width: int = 220, height: int = 320) -> RenderedDocument:
    """A clean, unwarped receipt: off-white paper with printed lines. Mask is 1 everywhere
    (the whole rendered rectangle IS the document, before any warp or placement).
    """
    background = int(rng.integers(235, 250))
    pil_image = Image.new("RGB", (width, height), color=(background, background, background))
    draw = ImageDraw.Draw(pil_image)

    try:
        font = ImageFont.load_default()
    except Exception:
        font = None

    y = 10
    for line in _document_content(rng):
        ink = int(rng.integers(20, 60))
        draw.text((10, y), line, fill=(ink, ink, ink), font=font)
        y += 14
        if y > height - 20:
            break

    image = np.array(pil_image, dtype=np.uint8)
    mask = np.ones((height, width, 1), dtype=np.float32)
    return RenderedDocument(image=image, mask=mask)


def _smooth_displacement_field(rng: np.random.Generator, width: int, height: int, max_shift: float) -> tuple[np.ndarray, np.ndarray]:
    """A coarse random grid, upsampled bicubically -> a smooth curl-like displacement field."""
    grid_size = 5
    coarse_dx = rng.uniform(-max_shift, max_shift, size=(grid_size, grid_size)).astype(np.float32)
    coarse_dy = rng.uniform(-max_shift, max_shift, size=(grid_size, grid_size)).astype(np.float32)
    dx = cv2.resize(coarse_dx, (width, height), interpolation=cv2.INTER_CUBIC)
    dy = cv2.resize(coarse_dy, (width, height), interpolation=cv2.INTER_CUBIC)
    return dx, dy


def apply_curl(doc: RenderedDocument, rng: np.random.Generator, max_shift: float = 6.0) -> RenderedDocument:
    height, width = doc.image.shape[:2]
    dx, dy = _smooth_displacement_field(rng, width, height, max_shift)
    grid_x, grid_y = np.meshgrid(np.arange(width, dtype=np.float32), np.arange(height, dtype=np.float32))
    map_x = grid_x + dx
    map_y = grid_y + dy

    warped_image = cv2.remap(doc.image, map_x, map_y, interpolation=cv2.INTER_LINEAR, borderMode=cv2.BORDER_REPLICATE)
    warped_mask = cv2.remap(doc.mask, map_x, map_y, interpolation=cv2.INTER_LINEAR, borderMode=cv2.BORDER_CONSTANT, borderValue=0)
    if warped_mask.ndim == 2:
        warped_mask = warped_mask[:, :, None]
    return RenderedDocument(image=warped_image, mask=warped_mask)


def apply_perspective(doc: RenderedDocument, rng: np.random.Generator, jitter_fraction: float = 0.08) -> RenderedDocument:
    height, width = doc.image.shape[:2]
    jitter = jitter_fraction * min(width, height)
    src = np.float32([[0, 0], [width, 0], [width, height], [0, height]])
    dst = src + rng.uniform(-jitter, jitter, size=src.shape).astype(np.float32)
    transform = cv2.getPerspectiveTransform(src, dst)

    warped_image = cv2.warpPerspective(doc.image, transform, (width, height), borderMode=cv2.BORDER_REPLICATE)
    warped_mask = cv2.warpPerspective(doc.mask, transform, (width, height), borderMode=cv2.BORDER_CONSTANT, borderValue=0)
    if warped_mask.ndim == 2:
        warped_mask = warped_mask[:, :, None]
    return RenderedDocument(image=warped_image, mask=warped_mask)


def apply_lighting_gradient(image: np.ndarray, rng: np.random.Generator) -> np.ndarray:
    height, width = image.shape[:2]
    angle = rng.uniform(0, 2 * np.pi)
    strength = rng.uniform(0.3, 0.9)
    xs, ys = np.meshgrid(np.linspace(-1, 1, width), np.linspace(-1, 1, height))
    gradient = np.cos(angle) * xs + np.sin(angle) * ys
    gradient = (gradient - gradient.min()) / (gradient.max() - gradient.min() + 1e-6)
    multiplier = (1 - strength) + strength * gradient
    lit = image.astype(np.float32) * multiplier[:, :, None]
    return np.clip(lit, 0, 255).astype(np.uint8)


def apply_motion_blur(image: np.ndarray, rng: np.random.Generator, max_kernel: int = 7) -> np.ndarray:
    kernel_size = int(rng.integers(3, max_kernel + 1))
    if kernel_size % 2 == 0:
        kernel_size += 1
    angle_deg = rng.uniform(0, 180)

    kernel = np.zeros((kernel_size, kernel_size), dtype=np.float32)
    kernel[kernel_size // 2, :] = 1.0
    rotation = cv2.getRotationMatrix2D((kernel_size / 2 - 0.5, kernel_size / 2 - 0.5), angle_deg, 1.0)
    kernel = cv2.warpAffine(kernel, rotation, (kernel_size, kernel_size))
    kernel_sum = kernel.sum()
    if kernel_sum > 0:
        kernel /= kernel_sum
    return cv2.filter2D(image, -1, kernel)


def jpeg_recompress(image: np.ndarray, rng: np.random.Generator, min_quality: int = 35, max_quality: int = 85) -> np.ndarray:
    quality = int(rng.integers(min_quality, max_quality + 1))
    bgr = cv2.cvtColor(image, cv2.COLOR_RGB2BGR)
    ok, encoded = cv2.imencode(".jpg", bgr, [cv2.IMWRITE_JPEG_QUALITY, quality])
    if not ok:
        return image
    decoded_bgr = cv2.imdecode(encoded, cv2.IMREAD_COLOR)
    return cv2.cvtColor(decoded_bgr, cv2.COLOR_BGR2RGB)


def _random_background(rng: np.random.Generator, size: int) -> np.ndarray:
    base_color = rng.integers(30, 180, size=3)
    noise = rng.normal(0, 12, size=(size, size, 3))
    background = base_color[None, None, :] + noise
    return np.clip(background, 0, 255).astype(np.uint8)


def composite_onto_background(doc: RenderedDocument, rng: np.random.Generator, canvas_size: int = CANVAS_SIZE) -> RenderedDocument:
    background = _random_background(rng, canvas_size)
    mask_canvas = np.zeros((canvas_size, canvas_size, 1), dtype=np.float32)

    scale = rng.uniform(0.55, 0.95)
    doc_h, doc_w = doc.image.shape[:2]
    target_w = int(canvas_size * scale)
    target_h = int(target_w * doc_h / doc_w)
    if target_h > canvas_size:
        target_h = int(canvas_size * scale)
        target_w = int(target_h * doc_w / doc_h)

    resized_image = cv2.resize(doc.image, (target_w, target_h), interpolation=cv2.INTER_AREA)
    resized_mask = cv2.resize(doc.mask, (target_w, target_h), interpolation=cv2.INTER_AREA)
    if resized_mask.ndim == 2:
        resized_mask = resized_mask[:, :, None]

    max_x = max(1, canvas_size - target_w)
    max_y = max(1, canvas_size - target_h)
    x0 = int(rng.integers(0, max_x))
    y0 = int(rng.integers(0, max_y))

    canvas = background.copy()
    region_mask = resized_mask[:, :, 0:1]
    canvas[y0:y0 + target_h, x0:x0 + target_w] = (
        resized_image * region_mask + canvas[y0:y0 + target_h, x0:x0 + target_w] * (1 - region_mask)
    ).astype(np.uint8)
    mask_canvas[y0:y0 + target_h, x0:x0 + target_w] = resized_mask

    return RenderedDocument(image=canvas, mask=mask_canvas)


def augment_and_composite(doc: RenderedDocument, rng: np.random.Generator, canvas_size: int = CANVAS_SIZE) -> RenderedDocument:
    """Curl -> perspective -> composite -> lighting -> blur -> JPEG, given an already
    rendered document texture. Split out from [generate_synthetic_sample] so a caller can
    render one document's content once and produce many augmented *variants* of it
    (DESIGN.md §5.2) — reusing this function with the same `doc` and a fresh `rng` each time.
    """
    warped = apply_curl(doc, rng)
    warped = apply_perspective(warped, rng)
    warped = composite_onto_background(warped, rng, canvas_size)

    image = apply_lighting_gradient(warped.image, rng)
    image = apply_motion_blur(image, rng)
    image = jpeg_recompress(image, rng)

    mask = np.clip(warped.mask, 0.0, 1.0)
    return RenderedDocument(image=image, mask=mask)


def generate_synthetic_sample(rng: np.random.Generator, canvas_size: int = CANVAS_SIZE) -> RenderedDocument:
    """Full pipeline for one (image, mask) pair, per DESIGN.md §5.2, using a single rng for
    both content and augmentation. Use [augment_and_composite] directly instead when several
    variants of the *same* document content are needed.
    """
    doc = render_receipt_texture(rng)
    return augment_and_composite(doc, rng, canvas_size)
