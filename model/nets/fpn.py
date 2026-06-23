"""Lightweight FPN (DESIGN.md §5.1): 1x1 lateral convs to 32ch, nearest-neighbour upsample,
add. Nearest-neighbour only — bilinear resize converts inconsistently between frameworks and
quantizes poorly (§D3, §5.1).

Note on scale: the design doc's prose names the fused feature "1/8 scale", but the stated
output shape is 1x64x64x1 for a 256x256 input, which is 1/4 scale (matching C2). This FPN
therefore runs the standard top-down path all the way to C2's resolution — the finest level
— since that is what is required for the head to produce the spec'd 64x64 output without an
extra upsample step hidden inside the head.
"""
from __future__ import annotations

import torch
import torch.nn.functional as F
from torch import nn

FPN_CHANNELS = 32


class LightweightFPN(nn.Module):
    def __init__(self, in_channels: dict[str, int]) -> None:
        super().__init__()
        self.laterals = nn.ModuleDict(
            {
                stage: nn.Conv2d(channels, FPN_CHANNELS, kernel_size=1)
                for stage, channels in in_channels.items()
            },
        )

    def forward(self, features: dict[str, torch.Tensor]) -> torch.Tensor:
        p5 = self.laterals["C5"](features["C5"])
        p4 = self.laterals["C4"](features["C4"]) + F.interpolate(p5, scale_factor=2, mode="nearest")
        p3 = self.laterals["C3"](features["C3"]) + F.interpolate(p4, scale_factor=2, mode="nearest")
        p2 = self.laterals["C2"](features["C2"]) + F.interpolate(p3, scale_factor=2, mode="nearest")
        return p2
