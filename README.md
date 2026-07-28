# On-Device Receipt / Menu Scanner

**A real-time document scanner for Android that runs entirely on the phone — custom PyTorch
segmentation model, INT8-quantized to LiteRT, driving a 30 FPS camera pipeline, with a
benchmark harness built as a first-class feature rather than a debug afterthought.**

No server. No account. No LLM in the parsing path. Everything — detection, capture, OCR,
and structuring — happens on-device.

<p align="left">
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-2.1.0-7F52FF?logo=kotlin&logoColor=white">
  <img alt="Android" src="https://img.shields.io/badge/Android-minSdk%2026%20%C2%B7%20compileSdk%2035-3DDC84?logo=android&logoColor=white">
  <img alt="Compose" src="https://img.shields.io/badge/Jetpack%20Compose-UI-4285F4?logo=jetpackcompose&logoColor=white">
  <img alt="PyTorch" src="https://img.shields.io/badge/PyTorch-detector-EE4C2C?logo=pytorch&logoColor=white">
  <img alt="LiteRT" src="https://img.shields.io/badge/LiteRT-INT8-FF6F00?logo=tensorflow&logoColor=white">
  <img alt="Modules" src="https://img.shields.io/badge/Gradle%20modules-11-02303A?logo=gradle&logoColor=white">
</p>

---

## What it does

Point the camera at a receipt or a menu. A custom segmentation network runs on every preview
frame to find the document, an overlay guides you into alignment, and the app auto-captures
the moment the shot is actually good. The still is perspective-corrected, recognized with
ML Kit, and parsed into structured line items — quantity, name, price — with the arithmetic
reconciled against the printed total.

```
CameraX analyzer ──► preprocess ──► INT8 detector ──► postprocess ──► auto-capture FSM
   (RGBA_8888)        256×256        1×64×64 mask      quad + conf      8 stable frames
                                     ≤ 25 ms budget    coverage         ↓
                                                       sharpness      still capture
                                                                        ↓
                              structured line items ◄── parser ◄── ML Kit OCR
                              (qty · name · price)     (pure Kotlin)
                                       ↓
                                Room persistence
```

## Highlights

**A custom model, not an off-the-shelf one.** MobileNetV3-Small backbone (width 0.75,
ImageNet-pretrained) → lightweight FPN → depthwise-separable head → per-pixel
`P(document)` at 64×64. ~1.4 M parameters. Trained, exported, quantized, and verified by
the Python pipeline in [`model/`](model).

**Quantization done properly, and proved.** Four variants are trained, exported, and shipped
in the APK — FP32, FP16, dynamic-range INT8, and full-integer INT8 with per-channel weights
and float I/O. Every one is verified against the original PyTorch model through the real
LiteRT interpreter, and the evaluator emits a **per-layer FP32-vs-INT8 activation MSE diff**
so quantization damage is attributable to a specific op, not guessed at.

**The architecture was designed for the quantizer.** Nearest-neighbour upsampling only
(bilinear converts inconsistently and quantizes poorly). Bounded activations only — ReLU6
and Hardswish, never unbounded ReLU. No squeeze-excite in the head, because the
sigmoid-multiply pattern is a quantization landmine; the backbone's SE blocks are kept
because per-channel quantization handles them adequately — and that was *verified*, not
assumed.

**Segmentation over corner regression — deliberately.** A 4-corner regressor is smaller and
faster, and it was rejected on purpose: it has no meaningful confidence signal (it always
emits four corners, including when there's no receipt in frame), it's the wrong prior for
curled and torn paper, and it gives you nothing else. The mask degrades gracefully *and*
yields coverage and sharpness for free — which is exactly what the auto-capture state
machine needs. The quad is derived from the mask via contour + `minAreaRect` in ~2–4 ms.

**The per-frame budget is the whole design.** Running OCR every frame is the naive approach
and it caps you at ~5–12 FPS. Splitting into a cheap per-frame detector (≤ 25 ms) and an
expensive one-shot recognizer (≤ 600 ms, once, after capture) is what makes the performance
story honest: every millisecond saved in quantization shows up directly as frames per second.

**Benchmarking is a feature, with its own screen.** 20 warmup iterations discarded (first
inference includes delegate init and XNNPACK weight repacking — routinely 10–50× steady
state), cold start reported separately as its own metric, 300 measured iterations over a
fixed 50-frame replay, percentiles computed from raw sorted samples rather than a running
approximation. Full variant × delegate × thread config matrix, JSON/CSV export with raw
samples included.

**Parsing that admits when it's unsure.** Deskew → row clustering by y-overlap → price-column
detection → row classification → field extraction → **reconciliation**. When
`Σ items + tax + tip` doesn't match the printed total, the app flags "check these lines"
instead of presenting a wrong number as fact.

## The detector

Trained, exported, and evaluated end-to-end by the Python pipeline. All four variants are
bundled in the APK and selectable at runtime from the benchmark screen.

| Variant | Size | IoU | F1 | Corner MAE |
|---|---:|---:|---:|---:|
| `fp32` | 1150 KB | 0.9700 | 0.9848 | 0.41 px |
| `fp16` | 615 KB | 0.9700 | 0.9848 | 0.41 px |
| `int8_dr` | 380 KB | 0.9701 | 0.9848 | 0.41 px |
| `int8_full` | 420 KB | 0.9701 | 0.9848 | 0.39 px |

<sub>Measured by `model/evaluate.py` on a held-out **synthetic** set — see
[`model/build/eval_report.json`](model/build/eval_report.json). Synthetic paper-vs-background
is an easier task than real thermal-paper receipts, so read these as *the quantization path
is lossless to four decimal places*, which is the interesting result here — not as a
real-world accuracy claim. Real-corpus numbers need CORD/SROIE/MIDV-2020, which aren't
vendored in this repo.</sub>

The headline: **full-integer INT8 costs essentially nothing in accuracy while cutting the
model to a third of its FP32 size.** That's the entire thesis of the export pipeline, and
it holds.

## Project status

10 phases, each its own commit — scaffold → domain/Room → parser → bench primitives →
camera → detector + training → calibration/export/evaluation → real on-device inference →
OCR + auto-capture → app shell + benchmark harness. All 10 are complete: every module
compiles, `assembleDebug` produces a real APK with CameraX/LiteRT/OpenCV/ML Kit/Room/Hilt/
Compose wired together, `./gradlew test` passes across every device-free module, and the
Python pipeline trains, exports, and evaluates a real detector end to end.

**On measurement discipline:** this project distinguishes numbers that were *measured* from
numbers that were *targeted*, and the README won't blur the two.

| | |
|---|---|
| ✅ **Measured** | Model accuracy and size per variant · quantization fidelity vs. PyTorch through the real LiteRT interpreter · per-layer activation MSE · parser reconciliation and bench percentile math (unit-tested) |
| 🎯 **Targeted, pending hardware** | Sustained 30 FPS analysis · p95 ≤ 25 ms inference · frame-drop rate · thermal-throttle knee · 85% line-item extraction accuracy |

The benchmark harness runs real inference through the real bundled models and is structurally
complete — it simply hasn't been pointed at a phone yet, because this was built without one.
Install the debug APK, open the Benchmark tab, and it produces the first real numbers with
no code changes. Those targets are the next milestone, not a to-do list of missing work.

## Engineering notes

Two environment problems worth documenting, both hit while getting the export pipeline
running on Windows:

**`ai-edge-torch` is not installable on Windows.** The design doc names
`ai_edge_torch.convert(...)` as the primary PyTorch → LiteRT route, but it depends on
`torch_xla`, for which Google publishes no Windows wheels; newer releases fail dependency
resolution outright. The pipeline therefore runs the documented fallback — `torch → ONNX →
onnx2tf` — which the design doc already sanctions as "genuinely good, handles layout
conversion properly, actively maintained." A platform-forced choice of a pre-approved
alternative, not an unplanned detour.

**onnx2tf 1.28.8 crashes against current numpy.** Its `download_test_image_data()` helper
calls `np.load()` without `allow_pickle=True` on a file that requires it, taking down the
entire conversion with `ValueError: This file contains pickled (object) data`. The helper is
onnx2tf's own generic sanity-check data and has nothing to do with this model, so
`export.py` monkeypatches it to return synthetic random data. This was isolated by first
reproducing it against a minimal single-conv model — confirming a library bug rather than
something specific to this network. (Related: onnx2tf shells out to the `onnxsim` CLI, which
pip installs somewhere not on Windows' `PATH` by default; without it, simplification fails
with a bare `FileNotFoundError: [WinError 2]`.)

## Module layout

11 Gradle modules, ~4.1 K lines of Kotlin and ~1.4 K lines of Python.

```
:app                     Compose shell, three-tab nav, runtime permission flow, Hilt graph
:core:camera             CameraX pipeline, RGBA_8888 analyzer, decoupled analysis resolution
:core:ml                 LiteRT interpreter, delegate/thread config, bundled model variants
:core:ocr                ML Kit recognition + perspective correction
:core:parse              Pure-Kotlin parser: deskew, rows, columns, classify, reconcile
:core:bench              Percentile math, frame-clock drop inference, thermal sampling
:core:data               Room persistence, schema-exported migrations
:core:model              Shared domain types
:feature:scan            Auto-capture state machine + alignment overlay
:feature:history         Saved scans, delete, share
:feature:benchmark       Config matrix runner, results table, JSON/CSV export
model/                   PyTorch detector, synthetic data, training, calibration,
                         export, evaluation, model cards
```

## Build

```bash
./gradlew assembleDebug        # debug APK with all four model variants bundled
./gradlew test                 # unit tests across device-free modules
```

Model pipeline:

```bash
pip install -r model/requirements.txt
python model/train.py
python model/export.py --verify   # exports 4 variants, verifies each against PyTorch
python model/evaluate.py          # IoU/F1/corner-MAE + per-layer activation MSE
```

**Toolchain** — JDK 17, Android SDK (`compileSdk 35`, `minSdk 26`), Gradle 8.11.1,
AGP 8.7.3, Kotlin 2.1.0 · Python 3.11, PyTorch (CPU), ONNX, onnx2tf, TensorFlow/tf_keras,
OpenCV, Albumentations.

## Design document

[`DESIGN.md`](DESIGN.md) is the full architecture: seven locked decisions with their
rejected alternatives and the reasoning behind each, the complete per-frame pipeline down to
the threading model, the benchmark methodology, the data model, the phased build plan, and
an explicit risk register. The implementation follows it, and deviations are documented
rather than silently absorbed.
