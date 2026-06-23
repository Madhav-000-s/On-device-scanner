"""Loss and metric functions shared by train.py and evaluate.py (DESIGN.md §5.3: "BCE + Dice
loss ... early stop on val IoU").
"""
from __future__ import annotations

import torch

EPS = 1e-6


def dice_loss(probs: torch.Tensor, targets: torch.Tensor) -> torch.Tensor:
    """1 - Dice coefficient, averaged over the batch. `probs` are post-sigmoid in [0, 1]."""
    probs_flat = probs.flatten(1)
    targets_flat = targets.flatten(1)
    intersection = (probs_flat * targets_flat).sum(dim=1)
    union = probs_flat.sum(dim=1) + targets_flat.sum(dim=1)
    dice = (2 * intersection + EPS) / (union + EPS)
    return 1 - dice.mean()


def bce_dice_loss(probs: torch.Tensor, targets: torch.Tensor) -> torch.Tensor:
    """The head already applies Sigmoid (DESIGN.md §5.1 — the exported graph must end in a
    Sigmoid), so this uses plain BCELoss on probabilities rather than BCEWithLogitsLoss on
    logits. Slightly less numerically ideal at the extremes than a logits-based loss, but
    correct, and it avoids a separate train/export forward-pass branch.
    """
    bce = torch.nn.functional.binary_cross_entropy(probs, targets)
    return bce + dice_loss(probs, targets)


@torch.no_grad()
def compute_iou(probs: torch.Tensor, targets: torch.Tensor, threshold: float = 0.5) -> torch.Tensor:
    """Per-batch mean IoU at the given probability threshold (DESIGN.md §4.4: τ = 0.5)."""
    preds = (probs >= threshold).float()
    preds_flat = preds.flatten(1)
    targets_flat = targets.flatten(1)
    intersection = (preds_flat * targets_flat).sum(dim=1)
    union = preds_flat.sum(dim=1) + targets_flat.sum(dim=1) - intersection
    iou = (intersection + EPS) / (union + EPS)
    return iou.mean()


@torch.no_grad()
def compute_f1(probs: torch.Tensor, targets: torch.Tensor, threshold: float = 0.5) -> torch.Tensor:
    preds = (probs >= threshold).float()
    preds_flat = preds.flatten(1)
    targets_flat = targets.flatten(1)
    tp = (preds_flat * targets_flat).sum(dim=1)
    precision = (tp + EPS) / (preds_flat.sum(dim=1) + EPS)
    recall = (tp + EPS) / (targets_flat.sum(dim=1) + EPS)
    f1 = 2 * precision * recall / (precision + recall + EPS)
    return f1.mean()
