"""Mask -> quad derivation (DESIGN.md §4.4), implemented here in Python so evaluate.py can
compute a genuine corner-MAE metric using the same algorithm :core:ml's Kotlin
`Postprocessor` implements on-device in Phase 8: threshold -> largest contour -> 4-point
approx (falling back to minAreaRect) -> ordered corners.
"""
from __future__ import annotations

import cv2
import numpy as np

THRESHOLD = 0.5  # DESIGN.md §4.4: tau = 0.5, a tuned hyperparameter, not a magic constant.
MIN_AREA_FRACTION = 0.08  # reject contours covering less than 8% of the frame.


def order_points(points: np.ndarray) -> np.ndarray:
    """Sorts 4 (x, y) points into top-left, top-right, bottom-right, bottom-left order."""
    points = points.reshape(4, 2)
    ordered = np.zeros((4, 2), dtype=np.float32)

    sums = points.sum(axis=1)
    diffs = np.diff(points, axis=1).flatten()

    ordered[0] = points[np.argmin(sums)]  # top-left: smallest x+y
    ordered[2] = points[np.argmax(sums)]  # bottom-right: largest x+y
    ordered[1] = points[np.argmin(diffs)]  # top-right: smallest y-x
    ordered[3] = points[np.argmax(diffs)]  # bottom-left: largest y-x
    return ordered


def mask_to_quad(mask: np.ndarray, threshold: float = THRESHOLD, min_area_fraction: float = MIN_AREA_FRACTION) -> np.ndarray | None:
    """`mask`: HxW float array in [0, 1]. Returns a (4, 2) array of ordered corners in this
    mask's own pixel coordinates, or None if nothing clears the area threshold.
    """
    height, width = mask.shape[:2]
    binary = (mask >= threshold).astype(np.uint8) * 255

    contours, _ = cv2.findContours(binary, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    if not contours:
        return None

    largest = max(contours, key=cv2.contourArea)
    if cv2.contourArea(largest) < min_area_fraction * height * width:
        return None

    perimeter = cv2.arcLength(largest, True)
    approx = cv2.approxPolyDP(largest, 0.02 * perimeter, True)

    if len(approx) == 4:
        quad = approx.reshape(4, 2).astype(np.float32)
    else:
        rect = cv2.minAreaRect(largest)
        quad = cv2.boxPoints(rect)

    return order_points(quad)


def corner_mae(pred_mask: np.ndarray, gt_mask: np.ndarray) -> float | None:
    """Mean absolute per-corner pixel error between quads derived from two masks the same
    way. Returns None if either mask doesn't yield a usable quad (a coverage failure, not a
    corner-position failure -- callers should track these separately, not average them in).
    """
    pred_quad = mask_to_quad(pred_mask)
    gt_quad = mask_to_quad(gt_mask)
    if pred_quad is None or gt_quad is None:
        return None
    return float(np.abs(pred_quad - gt_quad).mean())
