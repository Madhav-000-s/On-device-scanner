"""Detection head (DESIGN.md §5.1): 3x3 depthwise-separable conv -> 32ch -> ReLU6, then
1x1 conv -> 1ch -> Sigmoid. No squeeze-excite here — the sigmoid-multiply pattern is a
quantization landmine in the head specifically (the backbone's SE blocks are fine because
they're verified, not assumed, to quantize adequately per-channel).
"""
from __future__ import annotations

import torch
from torch import nn

from nets.blocks import DepthwiseSeparableConv
from nets.fpn import FPN_CHANNELS


class DetectionHead(nn.Module):
    def __init__(self) -> None:
        super().__init__()
        self.conv = DepthwiseSeparableConv(FPN_CHANNELS, FPN_CHANNELS, activation=nn.ReLU6)
        self.project = nn.Conv2d(FPN_CHANNELS, 1, kernel_size=1)
        self.gate = nn.Sigmoid()

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        return self.gate(self.project(self.conv(x)))
