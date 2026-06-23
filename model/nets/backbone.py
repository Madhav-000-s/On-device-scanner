"""MobileNetV3-Small-style backbone, width multiplier 0.75 (DESIGN.md §5.1).

Deviation from DESIGN.md §5.1 ("ImageNet-pretrained"): torchvision only ships ImageNet
checkpoints for the standard width_mult=1.0 MobileNetV3-Small. There is no public checkpoint
for a custom width_mult=0.75 variant with these exact stage channel counts, so `pretrained`
below is a documented no-op rather than a silent lie — this backbone trains from scratch.
Revisit if a matching checkpoint becomes available, or accept the from-scratch accuracy cost
(§9 Phase 1 gate already accounts for this not being met on synthetic data alone).
"""
from __future__ import annotations

import torch
from torch import nn

from nets.blocks import ConvBNAct, InvertedResidual

# (kernel_size, expanded_channels, out_channels, use_se, activation, stride)
_STAGES: dict[str, list[tuple[int, int, int, bool, type[nn.Module], int]]] = {
    "C2": [(3, 16, 16, True, nn.ReLU6, 2)],
    "C3": [(3, 72, 24, False, nn.ReLU6, 2), (3, 88, 24, False, nn.ReLU6, 1)],
    "C4": [(5, 120, 40, True, nn.Hardswish, 2), (5, 160, 40, True, nn.Hardswish, 1)],
    "C5": [(5, 240, 96, True, nn.Hardswish, 2), (5, 384, 96, True, nn.Hardswish, 1)],
}

STAGE_OUT_CHANNELS: dict[str, int] = {name: blocks[-1][2] for name, blocks in _STAGES.items()}


class MobileNetV3SmallBackbone(nn.Module):
    """Returns a dict of {"C2": .., "C3": .., "C4": .., "C5": ..} feature maps at
    1/4, 1/8, 1/16, 1/32 scale respectively, per DESIGN.md §5.1's stage table.
    """

    def __init__(self, pretrained: bool = False) -> None:
        super().__init__()
        if pretrained:
            raise ValueError(
                "no ImageNet checkpoint exists for this custom width_mult=0.75 config; "
                "see the module docstring. Pass pretrained=False explicitly.",
            )

        self.stem = ConvBNAct(3, 16, kernel_size=3, stride=2, activation=nn.Hardswish)

        self.stages = nn.ModuleDict()
        in_channels = 16
        for stage_name, blocks in _STAGES.items():
            stage_layers = []
            for kernel_size, expanded_channels, out_channels, use_se, activation, stride in blocks:
                stage_layers.append(
                    InvertedResidual(
                        in_channels=in_channels,
                        expanded_channels=expanded_channels,
                        out_channels=out_channels,
                        kernel_size=kernel_size,
                        stride=stride,
                        use_se=use_se,
                        activation=activation,
                    ),
                )
                in_channels = out_channels
            self.stages[stage_name] = nn.Sequential(*stage_layers)

    def forward(self, x: torch.Tensor) -> dict[str, torch.Tensor]:
        features: dict[str, torch.Tensor] = {}
        out = self.stem(x)
        for stage_name, stage in self.stages.items():
            out = stage(out)
            features[stage_name] = out
        return features
