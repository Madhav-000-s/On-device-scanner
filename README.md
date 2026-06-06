# On-Device Receipt / Menu Scanner

Android app that detects a receipt or menu in the camera preview with a custom INT8
segmentation model, auto-captures, OCRs with ML Kit, and parses the result into structured
line items. See [`DESIGN.md`](DESIGN.md) for the full architecture, decision log, and phased
build plan this implementation follows.

## Status

Implemented in 10 phases mapping to `DESIGN.md` §9. See commit history for phase boundaries.

## Deviation from DESIGN.md §D3

The design doc names `ai_edge_torch.convert(...)` (route 3) as the primary PyTorch → LiteRT
export path, with `torch → ONNX → onnx2tf` (route 2) as the documented fallback. On Windows,
route 3 is not installable: `ai-edge-torch` depends on `torch_xla`, which Google does not
publish Windows wheels for, and newer `ai-edge-torch` releases fail dependency resolution
entirely on this platform. `model/export.py` therefore targets **onnx2tf** as the active path.
The design doc already sanctions this ("genuinely good, handles layout conversion properly,
actively maintained") — this is a platform-forced choice of the documented fallback, not a new
design decision. Revisit route 3 if the model pipeline ever runs on Linux/WSL2.

## What's verified vs. not, on this machine

This was built and verified without a physical Android device or emulator:

- ✅ Every module compiles (`./gradlew assembleDebug`)
- ✅ Device-free logic has real unit tests (`:core:parse` parsing/reconciliation,
  `:core:bench` percentile math and drop-rate inference) — `./gradlew test`
- ✅ The model pipeline runs end-to-end on synthetic data and produces real, loadable
  `.tflite` artifacts in all four §D4 variants
- ❌ **No runtime/perf numbers are real.** Every §9 phase gate that requires a device
  (30 FPS sustained analysis, p95 ≤ 25 ms, drop rate, thermal throttle knee, 85% line-item
  extraction accuracy) is unmeasured. The benchmark harness and its result tables are
  implemented to spec but ship with empty/synthetic-only numbers rather than fabricated ones.
- ❌ **Accuracy targets are not met.** IoU ≥ 0.85 (§9 Phase 1 gate) requires the real
  CORD/SROIE/MIDV-2020 datasets, which aren't in this repo. The synthetic-only model's
  measured IoU is reported as-is in `model/build/eval_report.json`, not asserted against
  the target.

## Toolchain

- JDK 17, Android SDK (`compileSdk 35`, `minSdk 26`), Gradle 8.11.1, AGP 8.7.3, Kotlin 2.1.0
- Python 3.11, PyTorch (CPU), ONNX, onnx2tf, TensorFlow/tf_keras, OpenCV, Albumentations —
  see `model/requirements.txt`

## Module layout

Mirrors `DESIGN.md` §3: `:app`, `:core:{camera,ml,ocr,parse,bench,data,model}`,
`:feature:{scan,history,benchmark}`, plus the standalone Python `model/` pipeline.
