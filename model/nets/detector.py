"""Full detector: backbone -> FPN -> head (DESIGN.md §5.1).

Input  1x3x256x256 (RGB, normalized to [-1, 1])
Output 1x1x64x64    (per-pixel P(document); NCHW internally, permuted to NHWC only at
                      export time to match the model_card.json / LiteRT contract)
"""
from __future__ import annotations

import torch
from torch import nn

from nets.backbone import STAGE_OUT_CHANNELS, MobileNetV3SmallBackbone
from nets.fpn import LightweightFPN
from nets.head import DetectionHead

INPUT_SIZE = 256
OUTPUT_SIZE = 64


class DocumentDetector(nn.Module):
    def __init__(self, pretrained_backbone: bool = False) -> None:
        super().__init__()
        self.backbone = MobileNetV3SmallBackbone(pretrained=pretrained_backbone)
        self.fpn = LightweightFPN(STAGE_OUT_CHANNELS)
        self.head = DetectionHead()

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        features = self.backbone(x)
        fused = self.fpn(features)
        return self.head(fused)

    def count_parameters(self) -> int:
        return sum(p.numel() for p in self.parameters())
