"""Building blocks for the detector backbone/head (DESIGN.md §5.1).

Every block here is deliberately restricted to ops DESIGN.md §D3 calls
converter-friendly: Conv2d, BatchNorm2d, ReLU6/Hardswish, nn.Upsample(mode="nearest"),
Sigmoid, AdaptiveAvgPool2d with a fixed output size. No grid_sample, no einsum, no dynamic
control flow, no bilinear interpolate with align_corners=True. `nets/op_allowlist.py`
verifies this at build time rather than trusting it by convention.
"""
from __future__ import annotations

import torch
from torch import nn


def _make_divisible(value: float, divisor: int = 8) -> int:
    """Standard MobileNet channel-rounding rule: keeps channel counts multiples of 8."""
    new_value = max(divisor, int(value + divisor / 2) // divisor * divisor)
    if new_value < 0.9 * value:
        new_value += divisor
    return new_value


class ConvBNAct(nn.Sequential):
    def __init__(
        self,
        in_channels: int,
        out_channels: int,
        kernel_size: int = 3,
        stride: int = 1,
        groups: int = 1,
        activation: type[nn.Module] | None = nn.ReLU6,
    ) -> None:
        padding = (kernel_size - 1) // 2
        layers: list[nn.Module] = [
            nn.Conv2d(
                in_channels,
                out_channels,
                kernel_size,
                stride=stride,
                padding=padding,
                groups=groups,
                bias=False,
            ),
            nn.BatchNorm2d(out_channels),
        ]
        if activation is not None:
            layers.append(activation(inplace=True))
        super().__init__(*layers)


class SqueezeExcite(nn.Module):
    """Sigmoid-multiply gate, kept in the pretrained-style backbone stages (DESIGN.md §5.1
    notes per-channel quantization handles these adequately) but deliberately NOT reused in
    the head, where the same pattern is a quantization landmine.
    """

    def __init__(self, channels: int, reduction: int = 4) -> None:
        super().__init__()
        reduced = _make_divisible(channels / reduction)
        self.pool = nn.AdaptiveAvgPool2d(1)
        self.fc1 = nn.Conv2d(channels, reduced, kernel_size=1)
        self.act = nn.ReLU6(inplace=True)
        self.fc2 = nn.Conv2d(reduced, channels, kernel_size=1)
        self.gate = nn.Sigmoid()

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        scale = self.pool(x)
        scale = self.act(self.fc1(scale))
        scale = self.gate(self.fc2(scale))
        return x * scale


class InvertedResidual(nn.Module):
    """MobileNetV3-style inverted residual: expand (1x1) -> depthwise (kxk) -> [SE] ->
    project (1x1), with a residual connection when shape-compatible.
    """

    def __init__(
        self,
        in_channels: int,
        expanded_channels: int,
        out_channels: int,
        kernel_size: int,
        stride: int,
        use_se: bool,
        activation: type[nn.Module],
    ) -> None:
        super().__init__()
        self.use_residual = stride == 1 and in_channels == out_channels

        layers: list[nn.Module] = []
        if expanded_channels != in_channels:
            layers.append(ConvBNAct(in_channels, expanded_channels, kernel_size=1, activation=activation))

        layers.append(
            ConvBNAct(
                expanded_channels,
                expanded_channels,
                kernel_size=kernel_size,
                stride=stride,
                groups=expanded_channels,
                activation=activation,
            ),
        )

        if use_se:
            layers.append(SqueezeExcite(expanded_channels))

        layers.append(ConvBNAct(expanded_channels, out_channels, kernel_size=1, activation=None))

        self.block = nn.Sequential(*layers)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        out = self.block(x)
        if self.use_residual:
            out = out + x
        return out


class DepthwiseSeparableConv(nn.Module):
    """3x3 depthwise + 1x1 pointwise — the head's only conv block (DESIGN.md §5.1)."""

    def __init__(self, in_channels: int, out_channels: int, activation: type[nn.Module] = nn.ReLU6) -> None:
        super().__init__()
        self.depthwise = ConvBNAct(
            in_channels, in_channels, kernel_size=3, groups=in_channels, activation=activation,
        )
        self.pointwise = ConvBNAct(in_channels, out_channels, kernel_size=1, activation=activation)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        return self.pointwise(self.depthwise(x))
