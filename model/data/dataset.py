"""Synthetic dataset, split by source document (DESIGN.md §5.2): "augmented variants of the
same receipt in both train and test is the classic leak and it produces beautiful,
meaningless metrics." Each document (fixed printed content) gets several augmented variants
(different curl/perspective/lighting/blur/compression/placement); a document's variants
always land entirely in one split.
"""
from __future__ import annotations

import numpy as np
import torch
from torch.utils.data import Dataset

from data.synthetic import CANVAS_SIZE, augment_and_composite, render_receipt_texture

# Mean/std matching the model_card.json contract (DESIGN.md §5.3): input normalized to [-1, 1].
_NORM_MEAN = 127.5
_NORM_STD = 127.5


class SyntheticDocumentDataset(Dataset):
    def __init__(
        self,
        document_ids: list[int],
        variants_per_document: int,
        seed: int = 0,
        canvas_size: int = CANVAS_SIZE,
    ) -> None:
        self.document_ids = document_ids
        self.variants_per_document = variants_per_document
        self.seed = seed
        self.canvas_size = canvas_size

    def __len__(self) -> int:
        return len(self.document_ids) * self.variants_per_document

    def __getitem__(self, index: int) -> tuple[torch.Tensor, torch.Tensor]:
        document_id = self.document_ids[index // self.variants_per_document]
        variant_id = index % self.variants_per_document

        # Every variant of a document shares the SAME printed content (content_rng is a
        # function of document_id only) and gets a DIFFERENT augmentation (variant_rng also
        # depends on variant_id) — otherwise "variants of one document" would just be
        # unrelated random receipts, defeating the point of splitting by document (§5.2).
        content_rng = np.random.default_rng(self.seed * 1_000_003 + document_id)
        texture = render_receipt_texture(content_rng)

        variant_rng = np.random.default_rng(
            (self.seed * 1_000_003 + document_id) * 7_919 + variant_id,
        )
        sample = augment_and_composite(texture, variant_rng, canvas_size=self.canvas_size)

        image = (sample.image.astype(np.float32) - _NORM_MEAN) / _NORM_STD  # HWC, [-1, 1]
        image_tensor = torch.from_numpy(image).permute(2, 0, 1).contiguous()  # CHW

        mask = sample.mask.astype(np.float32)
        mask_tensor = torch.from_numpy(mask).permute(2, 0, 1).contiguous()  # 1xHxW

        return image_tensor, mask_tensor


def split_document_ids(
    num_documents: int,
    train_fraction: float = 0.7,
    val_fraction: float = 0.15,
    seed: int = 0,
) -> tuple[list[int], list[int], list[int]]:
    """DESIGN.md §5.2: split by source document, never by image."""
    rng = np.random.default_rng(seed)
    ids = np.arange(num_documents)
    rng.shuffle(ids)

    train_end = int(num_documents * train_fraction)
    val_end = train_end + int(num_documents * val_fraction)

    train_ids = sorted(ids[:train_end].tolist())
    val_ids = sorted(ids[train_end:val_end].tolist())
    test_ids = sorted(ids[val_end:].tolist())
    return train_ids, val_ids, test_ids
