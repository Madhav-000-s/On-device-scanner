# On-Device Receipt / Menu Scanner

Android app that detects a receipt or menu in the camera preview with a custom INT8
segmentation model, auto-captures, OCRs with ML Kit, and parses the result into structured
line items. See [`DESIGN.md`](DESIGN.md) for the full architecture, decision log, and phased
build plan this implementation follows.

## Status

All 10 phases complete. See commit history for phase boundaries. Every module compiles,
`./gradlew assembleDebug` produces a real debug APK with CameraX/TFLite/OpenCV/ML Kit/Room/
Hilt/Compose all wired, `./gradlew test` passes across every device-free module, and the
Python model pipeline trains, exports, and evaluates a real detector end to end. What this
does **not** include is device verification — see "What's verified vs. not" below.

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
  `.tflite` artifacts in all four §D4 variants, each verified against the original PyTorch
  model through the real LiteRT Python interpreter (`model/export.py --verify`)
- ✅ `model/evaluate.py` measures real IoU/F1/corner-MAE per variant and a per-layer
  FP32-vs-INT8 activation MSE diff, on synthetic held-out data — see
  `model/build/eval_report.json`. All four variants land around 0.97 IoU / 0.98 F1 on the
  synthetic set, comfortably over the §9 Phase 1 IoU target, but synthetic paper-vs-background
  segmentation is a much easier task than real receipts — **this number does not demonstrate
  the §9 gate is met**, only that the pipeline and quantization path work correctly end to end.
  Meeting the real gate needs the CORD/SROIE/MIDV-2020 corpora, which aren't in this repo.
- ✅ The full app shell builds: three-tab navigation (scan/history/benchmark), a runtime
  camera-permission flow gating the scan tab, Room-backed history with delete/share, and the
  §6 benchmark harness (fixed 50-frame replay, 20 warmup + 300 measured iterations, cold-start
  isolated, full variant×delegate×thread config matrix, JSON/CSV export with raw samples).
- ❌ **No runtime/perf numbers are real.** Every §9 phase gate that requires a device
  (30 FPS sustained analysis, p95 ≤ 25 ms, drop rate, thermal throttle knee, 85% line-item
  extraction accuracy) is unmeasured. The benchmark harness runs real inference through the
  real bundled models and is structurally complete, but nothing in it has ever executed on
  an actual phone — install the debug APK on the target device and run it from the
  Benchmark tab to get the first real numbers.

## onnx2tf environment notes (Windows)

Two real issues surfaced getting `model/export.py` working here, both worth knowing if this
pipeline moves to another machine:

1. **`onnxsim` must be on `PATH`.** onnx2tf shells out to the `onnxsim` CLI; pip installs it
   under the Python user install's `Scripts/` directory, which isn't on `PATH` by default on
   Windows (pip prints a warning about this during install — easy to miss). Without it,
   onnx2tf's model-simplification step fails silently-ish (`FileNotFoundError: [WinError 2]`).
2. **onnx2tf 1.28.8's `download_test_image_data()` is broken against current numpy.** It
   calls `np.load()` without `allow_pickle=True` on a cached/downloaded reference file that
   needs it, raising `ValueError: This file contains pickled (object) data`. This helper is
   onnx2tf's own generic sanity-check/fallback-calibration data — unrelated to this project's
   correctness — so `export.py` monkeypatches it to return synthetic random data instead of
   crashing the whole conversion. Verified against a minimal single-conv model first to
   confirm this is an environment/library issue, not something specific to this model.

## Toolchain

- JDK 17, Android SDK (`compileSdk 35`, `minSdk 26`), Gradle 8.11.1, AGP 8.7.3, Kotlin 2.1.0
- Python 3.11, PyTorch (CPU), ONNX, onnx2tf, TensorFlow/tf_keras, OpenCV, Albumentations —
  see `model/requirements.txt`

## Module layout

Mirrors `DESIGN.md` §3: `:app`, `:core:{camera,ml,ocr,parse,bench,data,model}`,
`:feature:{scan,history,benchmark}`, plus the standalone Python `model/` pipeline.
