"""Builds the representative calibration dataset for INT8 quantization (DESIGN.md §5.3,
§D4): "~300 representative frames drawn from the held-out split, matching the real
preprocessing pipeline exactly (same resize, same normalization) — calibration mismatch is
the number one cause of 'quantization destroyed my accuracy' and it's almost always a
preprocessing bug, not a quantization limitation."

Saved as NHWC float32 in [0, 1] (raw_pixel / 255), per onnx2tf's `-cind` contract: onnx2tf
applies (x - mean) / std itself, so mean=std=0.5 reproduces this project's actual
(pixel - 127.5) / 127.5 normalization (DESIGN.md §4.3) without duplicating it here.

    python model/calibrate.py --count 300
"""
from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np

from data.dataset import split_document_ids
from data.synthetic import CANVAS_SIZE, augment_and_composite, render_receipt_texture

BUILD_DIR = Path(__file__).parent / "build"

# Matches onnx2tf's -cind contract: mean/std applied AFTER the caller pre-normalizes to
# [0, 1] -- (x/255 - 0.5) / 0.5 == (x - 127.5) / 127.5, this project's real normalization.
CALIBRATION_MEAN = [[[[0.5, 0.5, 0.5]]]]
CALIBRATION_STD = [[[[0.5, 0.5, 0.5]]]]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--count", type=int, default=300)
    parser.add_argument("--num-documents", type=int, default=60, help="must match train.py's --num-documents")
    parser.add_argument("--variants-per-document", type=int, default=4, help="must match train.py's setting")
    parser.add_argument("--seed", type=int, default=0, help="must match train.py's --seed")
    parser.add_argument("--output", type=Path, default=BUILD_DIR / "calibration_data.npy")
    return parser.parse_args()


def build_calibration_set(count: int, num_documents: int, variants_per_document: int, seed: int) -> np.ndarray:
    # The held-out (test) split — never seen during training or validation (DESIGN.md §5.2).
    _train_ids, _val_ids, test_ids = split_document_ids(num_documents, seed=seed)
    if not test_ids:
        raise ValueError("held-out test split is empty; increase --num-documents")

    samples = np.empty((count, CANVAS_SIZE, CANVAS_SIZE, 3), dtype=np.float32)
    for i in range(count):
        document_id = test_ids[i % len(test_ids)]
        variant_id = i // len(test_ids)

        content_rng = np.random.default_rng(seed * 1_000_003 + document_id)
        texture = render_receipt_texture(content_rng)

        variant_rng = np.random.default_rng((seed * 1_000_003 + document_id) * 7_919 + variant_id)
        sample = augment_and_composite(texture, variant_rng, canvas_size=CANVAS_SIZE)

        samples[i] = sample.image.astype(np.float32) / 255.0  # NHWC, [0, 1]

    return samples


def main() -> None:
    args = parse_args()
    BUILD_DIR.mkdir(parents=True, exist_ok=True)

    samples = build_calibration_set(args.count, args.num_documents, args.variants_per_document, args.seed)
    np.save(args.output, samples)
    print(f"wrote {samples.shape[0]} calibration frames ({samples.shape[1:]}) to {args.output}")


if __name__ == "__main__":
    main()
