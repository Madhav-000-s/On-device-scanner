"""IoU / F1 / corner-MAE per variant on the held-out synthetic split, plus a per-layer
FP32-vs-INT8 activation MSE diff (DESIGN.md §6.4): "Speed without accuracy is meaningless
... Plus a per-layer diff (FP32 vs INT8 activation MSE) to identify which layers quantize
badly, which is the actionable output when accuracy drops more than expected."

This reports real, measured numbers on synthetic-only data (see README.md's "What's
verified vs. not"). The §9 Phase 1 gate (IoU >= 0.85, int8_full within 3 points of fp32) is
NOT asserted here -- it needs the real CORD/SROIE/MIDV-2020 corpora this repo doesn't have.

    python model/evaluate.py --count 60
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
import torch

from data.dataset import split_document_ids
from data.synthetic import CANVAS_SIZE, augment_and_composite, render_receipt_texture
from losses import compute_f1, compute_iou
from postprocess import mask_to_quad

BUILD_DIR = Path(__file__).parent / "build"
VARIANTS = ["fp32", "fp16", "int8_dr", "int8_full"]


def build_held_out_samples(count: int, num_documents: int, variants_per_document: int, seed: int) -> list[tuple[np.ndarray, np.ndarray]]:
    """Same held-out (test) document split as calibrate.py -- never seen in training,
    validation, or calibration.
    """
    _train_ids, _val_ids, test_ids = split_document_ids(num_documents, seed=seed)
    if not test_ids:
        raise ValueError("held-out test split is empty; increase --num-documents")

    samples = []
    for i in range(count):
        document_id = test_ids[i % len(test_ids)]
        variant_id = (i // len(test_ids)) + variants_per_document  # offset past calibrate.py's variant range
        content_rng = np.random.default_rng(seed * 1_000_003 + document_id)
        texture = render_receipt_texture(content_rng)
        variant_rng = np.random.default_rng((seed * 1_000_003 + document_id) * 7_919 + variant_id)
        sample = augment_and_composite(texture, variant_rng, canvas_size=CANVAS_SIZE)
        samples.append((sample.image, sample.mask))
    return samples


def run_variant(tflite_path: Path, samples: list[tuple[np.ndarray, np.ndarray]], preserve_tensors: bool = False):
    from ai_edge_litert.interpreter import Interpreter

    interpreter = Interpreter(model_path=str(tflite_path), experimental_preserve_all_tensors=preserve_tensors)
    interpreter.allocate_tensors()
    input_detail = interpreter.get_input_details()[0]
    output_detail = interpreter.get_output_details()[0]

    all_probs = []
    all_targets_64 = []
    corner_errors = []
    coverage_failures = 0

    for image, mask in samples:
        normalized = ((image.astype(np.float32) / 255.0) - 0.5) / 0.5
        input_tensor = normalized[None, ...].astype(input_detail["dtype"])

        interpreter.set_tensor(input_detail["index"], input_tensor)
        interpreter.invoke()
        probs = interpreter.get_tensor(output_detail["index"]).astype(np.float32)  # [1,64,64,1]

        target_64 = torch.nn.functional.interpolate(
            torch.from_numpy(mask).permute(2, 0, 1)[None, ...], size=probs.shape[1:3], mode="area",
        ).numpy()

        all_probs.append(probs.transpose(0, 3, 1, 2))  # -> [1,1,64,64] to match losses.py's NCHW convention
        all_targets_64.append(target_64)

        pred_quad = mask_to_quad(probs[0, :, :, 0])
        gt_quad = mask_to_quad(target_64[0, 0])
        if pred_quad is None or gt_quad is None:
            coverage_failures += 1
        else:
            corner_errors.append(float(np.abs(pred_quad - gt_quad).mean()))

    probs_t = torch.from_numpy(np.concatenate(all_probs, axis=0))
    targets_t = torch.from_numpy(np.concatenate(all_targets_64, axis=0))

    return {
        "iou": compute_iou(probs_t, targets_t).item(),
        "f1": compute_f1(probs_t, targets_t).item(),
        "corner_mae_px": (sum(corner_errors) / len(corner_errors)) if corner_errors else None,
        "coverage_failure_rate": coverage_failures / len(samples),
        "size_kb": tflite_path.stat().st_size / 1024,
    }, interpreter


def per_layer_activation_mse(fp32_path: Path, int8_path: Path, samples: list[tuple[np.ndarray, np.ndarray]], max_samples: int = 8) -> list[dict]:
    """Compares intermediate tensors with matching names between the fp32 and int8_full
    graphs, dequantizing the int8 side first. Only tensors present (by name) in both graphs
    are comparable -- quantize/dequantize boundary tensors and op-fusion artifacts differ
    between the two graphs and are skipped, not force-matched.
    """
    from ai_edge_litert.interpreter import Interpreter

    fp32 = Interpreter(model_path=str(fp32_path), experimental_preserve_all_tensors=True)
    fp32.allocate_tensors()
    int8 = Interpreter(model_path=str(int8_path), experimental_preserve_all_tensors=True)
    int8.allocate_tensors()

    fp32_by_name = {t["name"]: t for t in fp32.get_tensor_details() if t["name"]}
    int8_by_name = {t["name"]: t for t in int8.get_tensor_details() if t["name"]}
    common_names = sorted(set(fp32_by_name) & set(int8_by_name))

    fp32_input = fp32.get_input_details()[0]
    int8_input = int8.get_input_details()[0]

    mse_sums: dict[str, float] = {name: 0.0 for name in common_names}
    n = 0
    for image, _mask in samples[:max_samples]:
        normalized = (((image.astype(np.float32) / 255.0) - 0.5) / 0.5)[None, ...]

        fp32.set_tensor(fp32_input["index"], normalized.astype(fp32_input["dtype"]))
        fp32.invoke()
        int8.set_tensor(int8_input["index"], normalized.astype(int8_input["dtype"]))
        int8.invoke()

        for name in common_names:
            fp32_tensor = fp32.get_tensor(fp32_by_name[name]["index"]).astype(np.float32)
            int8_detail = int8_by_name[name]
            int8_raw = int8.get_tensor(int8_detail["index"])
            quant = int8_detail.get("quantization_parameters", {})
            scales = quant.get("scales")
            zero_points = quant.get("zero_points")
            if scales is not None and len(scales) > 0:
                int8_tensor = (int8_raw.astype(np.float32) - zero_points[0]) * scales[0]
            else:
                int8_tensor = int8_raw.astype(np.float32)

            if fp32_tensor.shape != int8_tensor.shape:
                continue  # a layout/shape mismatch means this "common name" isn't truly comparable
            mse_sums[name] += float(np.mean((fp32_tensor - int8_tensor) ** 2))
        n += 1

    results = [{"tensor": name, "activation_mse": mse_sums[name] / max(n, 1)} for name in common_names]
    results.sort(key=lambda r: r["activation_mse"], reverse=True)
    return results


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--count", type=int, default=60)
    parser.add_argument("--num-documents", type=int, default=60)
    parser.add_argument("--variants-per-document", type=int, default=4)
    parser.add_argument("--seed", type=int, default=0)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    samples = build_held_out_samples(args.count, args.num_documents, args.variants_per_document, args.seed)
    print(f"evaluating on {len(samples)} held-out synthetic samples\n")

    report: dict[str, dict] = {}
    print(f"{'variant':<10} {'size(KB)':>9} {'IoU':>7} {'F1':>7} {'corner MAE(px)':>15} {'cov.fail%':>10}")
    for variant in VARIANTS:
        path = BUILD_DIR / f"detector_{variant}.tflite"
        if not path.exists():
            print(f"{variant:<10} -- missing, run export.py first --")
            continue
        metrics, _ = run_variant(path, samples)
        report[variant] = metrics
        corner = f"{metrics['corner_mae_px']:.2f}" if metrics["corner_mae_px"] is not None else "n/a"
        print(
            f"{variant:<10} {metrics['size_kb']:>9.1f} {metrics['iou']:>7.4f} {metrics['f1']:>7.4f} "
            f"{corner:>15} {metrics['coverage_failure_rate'] * 100:>9.1f}%",
        )

    fp32_path = BUILD_DIR / "detector_fp32.tflite"
    int8_path = BUILD_DIR / "detector_int8_full.tflite"
    if fp32_path.exists() and int8_path.exists():
        print("\ntop-10 layers by FP32-vs-INT8 activation MSE (the actionable output when accuracy drops, DESIGN.md §6.4):")
        layer_diffs = per_layer_activation_mse(fp32_path, int8_path, samples)
        for row in layer_diffs[:10]:
            print(f"  {row['activation_mse']:.6f}  {row['tensor']}")
        report["per_layer_activation_mse"] = layer_diffs

    (BUILD_DIR / "eval_report.json").write_text(json.dumps(report, indent=2))
    print(f"\nfull report written to {BUILD_DIR / 'eval_report.json'}")


if __name__ == "__main__":
    main()
