"""Training entry point (DESIGN.md §5.3): "BCE + Dice loss, cosine LR, AMP, early stop on
val IoU." Trains on the synthetic generator (data/synthetic.py) since the real corpora
(CORD/SROIE/MIDV-2020, §5.2) aren't present in this repo — see README.md's "What's verified
vs. not" section for what that means for the accuracy numbers this actually produces.

    python model/train.py --num-documents 60 --variants-per-document 4 --max-epochs 8
"""
from __future__ import annotations

import argparse
import json
import time
from pathlib import Path

import torch
import torch.nn.functional as F
from torch.utils.data import DataLoader

from data.dataset import SyntheticDocumentDataset, split_document_ids
from losses import bce_dice_loss, compute_iou
from nets.detector import INPUT_SIZE, DocumentDetector
from nets.op_allowlist import assert_converter_friendly

BUILD_DIR = Path(__file__).parent / "build"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--num-documents", type=int, default=60)
    parser.add_argument("--variants-per-document", type=int, default=4)
    parser.add_argument("--batch-size", type=int, default=8)
    parser.add_argument("--max-epochs", type=int, default=8)
    parser.add_argument("--patience", type=int, default=3, help="epochs without val-IoU improvement before stopping")
    parser.add_argument("--lr", type=float, default=1e-3)
    parser.add_argument("--seed", type=int, default=0)
    parser.add_argument("--num-workers", type=int, default=0)
    parser.add_argument("--output", type=Path, default=BUILD_DIR / "checkpoint.pt")
    return parser.parse_args()


def run_epoch(model: torch.nn.Module, loader: DataLoader, optimizer: torch.optim.Optimizer | None, device: torch.device) -> tuple[float, float]:
    is_train = optimizer is not None
    model.train(is_train)

    total_loss = 0.0
    total_iou = 0.0
    num_batches = 0

    for images, masks in loader:
        images = images.to(device)
        masks = masks.to(device)

        with torch.set_grad_enabled(is_train):
            probs = model(images)
            # Ground-truth masks come in at canvas resolution; the model's output is 1/4
            # scale (DESIGN.md §5.1 — 64x64 for a 256x256 input). 'area' downsampling keeps
            # the soft coverage fraction per output pixel rather than a hard binary sample.
            targets = F.interpolate(masks, size=probs.shape[-2:], mode="area")
            loss = bce_dice_loss(probs, targets)

        if is_train:
            optimizer.zero_grad(set_to_none=True)
            loss.backward()
            optimizer.step()

        total_loss += loss.item()
        total_iou += compute_iou(probs, targets).item()
        num_batches += 1

    return total_loss / max(num_batches, 1), total_iou / max(num_batches, 1)


def main() -> None:
    args = parse_args()
    torch.manual_seed(args.seed)

    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")

    train_ids, val_ids, _test_ids = split_document_ids(args.num_documents, seed=args.seed)
    train_set = SyntheticDocumentDataset(train_ids, args.variants_per_document, seed=args.seed)
    val_set = SyntheticDocumentDataset(val_ids, args.variants_per_document, seed=args.seed)

    train_loader = DataLoader(train_set, batch_size=args.batch_size, shuffle=True, num_workers=args.num_workers)
    val_loader = DataLoader(val_set, batch_size=args.batch_size, shuffle=False, num_workers=args.num_workers)

    model = DocumentDetector().to(device)
    print(f"model parameters: {model.count_parameters():,}")

    example_input = torch.randn(1, 3, INPUT_SIZE, INPUT_SIZE, device=device)
    assert_converter_friendly(model, example_input)
    print("op-allowlist check passed: no banned ops in the traced graph")

    optimizer = torch.optim.AdamW(model.parameters(), lr=args.lr)
    scheduler = torch.optim.lr_scheduler.CosineAnnealingLR(optimizer, T_max=args.max_epochs)

    best_val_iou = -1.0
    epochs_without_improvement = 0
    history = []

    BUILD_DIR.mkdir(parents=True, exist_ok=True)

    for epoch in range(args.max_epochs):
        t0 = time.time()
        train_loss, train_iou = run_epoch(model, train_loader, optimizer, device)
        val_loss, val_iou = run_epoch(model, val_loader, None, device)
        scheduler.step()
        elapsed = time.time() - t0

        print(
            f"epoch {epoch + 1}/{args.max_epochs}  "
            f"train_loss={train_loss:.4f} train_iou={train_iou:.4f}  "
            f"val_loss={val_loss:.4f} val_iou={val_iou:.4f}  ({elapsed:.1f}s)",
        )
        history.append(
            {"epoch": epoch + 1, "train_loss": train_loss, "train_iou": train_iou, "val_loss": val_loss, "val_iou": val_iou},
        )

        if val_iou > best_val_iou:
            best_val_iou = val_iou
            epochs_without_improvement = 0
            torch.save(model.state_dict(), args.output)
        else:
            epochs_without_improvement += 1
            if epochs_without_improvement >= args.patience:
                print(f"early stopping: no val-IoU improvement for {args.patience} epochs")
                break

    (BUILD_DIR / "train_history.json").write_text(json.dumps(history, indent=2))
    print(f"best val IoU: {best_val_iou:.4f} -- checkpoint saved to {args.output}")


if __name__ == "__main__":
    main()
