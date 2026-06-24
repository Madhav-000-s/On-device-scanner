"""torch -> ONNX -> onnx2tf -> TFLite export, producing all four DESIGN.md §D4 variants.

Deviation from DESIGN.md §D3 (see README.md): the primary path (`ai_edge_torch.convert`)
needs `torch_xla`, which has no Windows distribution, so this targets the documented
fallback, onnx2tf, as the active path on this machine.

Environment note: onnx2tf 1.28.8's own `download_test_image_data()` helper (used for its
internal ONNX-vs-TF sanity check and, separately, as a fallback auto-calibration source)
raises on this machine's numpy version -- `np.load()` on its cached/downloaded reference
file fails with "contains pickled (object) data" because that file was saved with an
object dtype and the installed numpy no longer defaults `allow_pickle=True`. That check is
onnx2tf's own generic sanity tooling, unrelated to this project's actual correctness (which
this script verifies itself, below, against the real PyTorch model). The monkeypatch below
replaces it with synthetic random data so conversion can proceed at all; verified against a
minimal single-conv model before use on the real detector, to confirm this is an
environment/library issue and not specific to this architecture.

    python model/export.py --checkpoint build/checkpoint.pt --calibration build/calibration_data.npy
"""
from __future__ import annotations

import argparse
import json
import shutil
from pathlib import Path

import numpy as np
import torch

from data.dataset import split_document_ids
from data.synthetic import augment_and_composite, render_receipt_texture
from nets.detector import INPUT_SIZE, DocumentDetector

BUILD_DIR = Path(__file__).parent / "build"

# Reproduces this project's real (pixel - 127.5) / 127.5 normalization on top of onnx2tf's
# required [0, 1] pre-normalized calibration input: (x/255 - 0.5) / 0.5 == (x - 127.5) / 127.5.
CALIBRATION_MEAN = [[[[0.5, 0.5, 0.5]]]]
CALIBRATION_STD = [[[[0.5, 0.5, 0.5]]]]

# onnx2tf's own output filenames -> this project's DESIGN.md §D4 variant names.
_ONNX2TF_TO_VARIANT = {
    "float32": "fp32",
    "float16": "fp16",
    "dynamic_range_quant": "int8_dr",
    "integer_quant": "int8_full",
}


def _patch_broken_test_image_download() -> None:
    """See module docstring. Must run before calling onnx2tf.convert()."""
    import onnx2tf.onnx2tf as onnx2tf_internal

    def _synthetic_test_image_data() -> np.ndarray:
        return np.random.default_rng(0).random((20, 128, 128, 3)).astype(np.float32)

    onnx2tf_internal.download_test_image_data = _synthetic_test_image_data


def export_to_onnx(model: torch.nn.Module, onnx_path: Path) -> None:
    model.eval()
    example = torch.randn(1, 3, INPUT_SIZE, INPUT_SIZE)
    torch.onnx.export(
        model,
        example,
        str(onnx_path),
        input_names=["input"],
        output_names=["output"],
        opset_version=17,
        dynamic_axes=None,  # DESIGN.md §5.1: fixed input shape, no dynamic axes.
    )


def convert_to_tflite(onnx_path: Path, output_dir: Path, calibration_path: Path) -> dict[str, Path]:
    _patch_broken_test_image_download()
    import onnx2tf

    if output_dir.exists():
        shutil.rmtree(output_dir)

    onnx2tf.convert(
        input_onnx_file_path=str(onnx_path),
        output_folder_path=str(output_dir),
        output_dynamic_range_quantized_tflite=True,
        output_integer_quantized_tflite=True,
        quant_type="per-channel",  # DESIGN.md §D4: per-channel weights.
        input_quant_dtype="float32",  # DESIGN.md §D4: float32 I/O even in int8_full.
        output_quant_dtype="float32",
        custom_input_op_name_np_data_path=[
            ["input", str(calibration_path), CALIBRATION_MEAN, CALIBRATION_STD],
        ],
        non_verbose=True,
    )

    model_stem = onnx_path.stem
    variants: dict[str, Path] = {}
    for onnx2tf_name, variant_name in _ONNX2TF_TO_VARIANT.items():
        src = output_dir / f"{model_stem}_{onnx2tf_name}.tflite"
        dst = BUILD_DIR / f"detector_{variant_name}.tflite"
        shutil.copyfile(src, dst)
        variants[variant_name] = dst
    return variants


def _representative_sample_nhwc(seed: int = 42) -> np.ndarray:
    """A real synthetic receipt image, not uniform noise -- quantization scales are tuned to
    the calibration set's statistics, so a noise input is out-of-distribution and produces a
    misleadingly large error even for a well-calibrated model. This is a smoke check, not the
    real accuracy gate; evaluate.py's IoU/F1 comparison over the held-out set is that.
    """
    train_ids, _val_ids, _test_ids = split_document_ids(num_documents=60, seed=0)
    content_rng = np.random.default_rng(seed)
    texture = render_receipt_texture(content_rng)
    sample = augment_and_composite(texture, np.random.default_rng(seed + 1))
    return (sample.image.astype(np.float32) / 255.0)[None, ...]


def verify_variant_against_pytorch(model: torch.nn.Module, tflite_path: Path, atol: float = 0.08) -> float:
    """Loads `tflite_path` through the real LiteRT Python interpreter and compares its
    output against the original PyTorch model on the same input (DESIGN.md §7: "Each variant
    loaded back through the LiteRT Python interpreter and output-matched against the PyTorch
    model before being accepted"). Returns the max absolute difference.
    """
    from ai_edge_litert.interpreter import Interpreter

    sample_nhwc = _representative_sample_nhwc()
    normalized_nhwc = (sample_nhwc - 0.5) / 0.5  # matches this project's -1..1 normalization

    with torch.no_grad():
        torch_input = torch.from_numpy(normalized_nhwc).permute(0, 3, 1, 2).contiguous()
        torch_output = model(torch_input).permute(0, 2, 3, 1).numpy()

    interpreter = Interpreter(model_path=str(tflite_path))
    interpreter.allocate_tensors()
    input_detail = interpreter.get_input_details()[0]
    output_detail = interpreter.get_output_details()[0]
    interpreter.set_tensor(input_detail["index"], normalized_nhwc.astype(input_detail["dtype"]))
    interpreter.invoke()
    tflite_output = interpreter.get_tensor(output_detail["index"]).astype(np.float32)

    max_diff = float(np.abs(torch_output - tflite_output).max())
    status = "OK" if max_diff <= atol else "EXCEEDS TOLERANCE"
    print(f"  {tflite_path.name}: max |torch - tflite| = {max_diff:.4f} ({status}, atol={atol})")
    return max_diff


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--checkpoint", type=Path, default=BUILD_DIR / "checkpoint.pt")
    parser.add_argument("--calibration", type=Path, default=BUILD_DIR / "calibration_data.npy")
    parser.add_argument("--verify", action="store_true", help="only run the PyTorch-vs-TFLite comparison on existing variants")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    BUILD_DIR.mkdir(parents=True, exist_ok=True)

    model = DocumentDetector()
    model.load_state_dict(torch.load(args.checkpoint, map_location="cpu", weights_only=True))
    model.eval()

    if not args.verify:
        onnx_path = BUILD_DIR / "detector_fp32.onnx"
        export_to_onnx(model, onnx_path)
        print(f"exported ONNX -> {onnx_path}")

        variants = convert_to_tflite(onnx_path, BUILD_DIR / "tf_saved_model", args.calibration)
        for name, path in variants.items():
            size_kb = path.stat().st_size / 1024
            print(f"{name}: {path} ({size_kb:.1f} KB)")
    else:
        variants = {
            variant_name: BUILD_DIR / f"detector_{variant_name}.tflite"
            for variant_name in _ONNX2TF_TO_VARIANT.values()
        }

    print("\nverifying each variant against the PyTorch model:")
    results = {name: verify_variant_against_pytorch(model, path) for name, path in variants.items()}
    (BUILD_DIR / "export_verification.json").write_text(json.dumps(results, indent=2))


if __name__ == "__main__":
    main()
