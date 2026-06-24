"""Emits model_card.json per variant (DESIGN.md §5.3): "the contract between the model repo
and the app. The app parses this at startup and asserts shape/dtype match before the first
inference. A silently changed model contract is the kind of bug that eats an evening."

Every field is read from the real artifact or a real measurement -- sha256/bytes from the
.tflite file itself, shape/dtype from the LiteRT interpreter, metrics from evaluate.py's
eval_report.json (when present) -- never hand-typed placeholders.

    python model/model_card.py
"""
from __future__ import annotations

import hashlib
import json
import subprocess
from pathlib import Path

BUILD_DIR = Path(__file__).parent / "build"
VARIANTS = ["fp32", "fp16", "int8_dr", "int8_full"]

# DESIGN.md §5.1/§4.3: input normalized as (pixel - 127.5) / 127.5; §4.4: threshold tau = 0.5.
NORMALIZATION_MEAN = 127.5
NORMALIZATION_STD = 127.5
POSTPROCESS_THRESHOLD = 0.5


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def tensor_contract(tflite_path: Path) -> tuple[dict, dict]:
    from ai_edge_litert.interpreter import Interpreter

    interpreter = Interpreter(model_path=str(tflite_path))
    interpreter.allocate_tensors()
    input_detail = interpreter.get_input_details()[0]
    output_detail = interpreter.get_output_details()[0]

    input_contract = {
        "shape": input_detail["shape"].tolist(),
        "dtype": input_detail["dtype"].__name__,
        "mean": NORMALIZATION_MEAN,
        "std": NORMALIZATION_STD,
    }
    output_contract = {
        "shape": output_detail["shape"].tolist(),
        "dtype": output_detail["dtype"].__name__,
    }
    return input_contract, output_contract


def current_commit() -> str | None:
    try:
        result = subprocess.run(
            ["git", "rev-parse", "HEAD"], cwd=Path(__file__).parent, capture_output=True, text=True, timeout=5,
        )
        return result.stdout.strip() if result.returncode == 0 else None
    except (OSError, subprocess.SubprocessError):
        return None


def build_model_card(variant: str, eval_report: dict) -> dict | None:
    tflite_path = BUILD_DIR / f"detector_{variant}.tflite"
    if not tflite_path.exists():
        return None

    input_contract, output_contract = tensor_contract(tflite_path)
    metrics = eval_report.get(variant, {})

    return {
        "variant": variant,
        "sha256": sha256_of(tflite_path),
        "bytes": tflite_path.stat().st_size,
        "input": input_contract,
        "output": output_contract,
        "threshold": POSTPROCESS_THRESHOLD,
        "metrics": {
            "iou": metrics.get("iou"),
            "f1": metrics.get("f1"),
            "corner_mae_px": metrics.get("corner_mae_px"),
        },
        "trained_commit": current_commit(),
    }


def main() -> None:
    eval_report_path = BUILD_DIR / "eval_report.json"
    eval_report = json.loads(eval_report_path.read_text()) if eval_report_path.exists() else {}

    for variant in VARIANTS:
        card = build_model_card(variant, eval_report)
        if card is None:
            print(f"{variant}: skipped, no .tflite artifact found (run export.py first)")
            continue
        out_path = BUILD_DIR / f"model_card_{variant}.json"
        out_path.write_text(json.dumps(card, indent=2))
        print(f"{variant}: {out_path} (sha256={card['sha256'][:12]}..., {card['bytes']} bytes)")


if __name__ == "__main__":
    main()
