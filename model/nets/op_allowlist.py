"""Runtime op-allowlist check (DESIGN.md §D3 risk mitigation, §10):
"Op allowlist enforced from day one; convert a stub network in week 1 before writing the
real one." This traces the model and inspects the actual TorchScript graph — not a
convention someone can quietly break by adding a layer — for ops known to be hostile to the
PyTorch -> ONNX -> onnx2tf -> TFLite path (DESIGN.md README: onnx2tf is this pipeline's
active export path).
"""
from __future__ import annotations

import torch
from torch import nn

BANNED_ATEN_OPS: set[str] = {
    "aten::grid_sampler",
    "aten::einsum",
    "aten::upsample_bilinear2d",
    "aten::upsample_bicubic2d",
}


class ConverterFriendlyError(AssertionError):
    pass


def assert_converter_friendly(model: nn.Module, example_input: torch.Tensor) -> None:
    """Traces `model` and raises if the graph contains any banned op. Call this once at
    build/import time (DESIGN.md: "in CI-style fashion at import") — before spending an
    entire training run on an architecture that will never convert.
    """
    was_training = model.training
    model.eval()
    try:
        traced = torch.jit.trace(model, example_input)
    finally:
        model.train(was_training)

    offending = sorted({node.kind() for node in traced.graph.nodes() if node.kind() in BANNED_ATEN_OPS})
    if offending:
        raise ConverterFriendlyError(
            f"model graph uses ops that are known to be hostile to onnx2tf conversion: {offending}",
        )
