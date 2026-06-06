# DESIGN.md — On-Device Receipt / Menu Scanner (Android)

**Status:** Architecture locked, implementation not started
**Platform:** Android 8.0 (API 26) minimum, target API 35
**Language:** Kotlin (app), Python/PyTorch (model pipeline)
**Last updated:** 2026-07-27

---

## 1. What this is (and what it isn't)

An Android app that points a camera at a paper receipt or a restaurant menu, finds the
document in real time, auto-captures when it's stable and sharp, OCRs it, and turns it
into structured line items (name, qty, unit price, total).

The differentiator is **the per-frame model**: a custom text-region segmentation network
trained in PyTorch, exported to LiteRT (TFLite), quantized to INT8, and run inside the
CameraX `ImageAnalysis` pipeline at 15–30 FPS on a budget phone. The whole project is
built around being able to answer, with numbers: *how fast, how big, how accurate, and
what did quantization cost us?*

### In scope
- Custom PyTorch model → LiteRT export → INT8 quantization → on-device inference
- Real-time document detection + alignment guidance overlay in the CameraX analyzer
- Perspective-corrected still capture → ML Kit text recognition → deterministic parsing
- A first-class **benchmark harness**: p50/p90/p95/p99 latency, model size deltas,
  frame-drop and jank measurement, thermal-throttle curves, accuracy-vs-quantization table
- Local persistence of scans and benchmark runs

### Explicitly out of scope
- No server, no account, no sync. Everything is on-device.
- No custom text *recognition* model in v1 — ML Kit does OCR. Writing a CRNN adds months
  and beats nothing. Revisit only if ML Kit's accuracy on thermal-paper receipts is the
  measured bottleneck.
- No LLM anywhere in the parsing path. Parsing is rules over geometry + regex, and if
  rules plateau, a ~50 KB line classifier — not a language model.
- No handwriting, no multi-page stitching, no currency conversion, no expense-report export.
- No NNAPI delegate (deprecated as of Android 15; not worth the maintenance).

---

## 2. Decisions locked

These are the choices that everything else hangs off. Reasoning is given because reversing
any of them is expensive.

### D1 — Two-model split: custom detector per-frame, ML Kit OCR on capture

Running OCR on every frame is the naive design and it fails: ML Kit text recognition on a
1080p frame costs ~80–200 ms on mid-range hardware, which caps you at ~5–12 FPS and makes
the preview feel broken. Instead:

| Stage | Runs on | Model | Budget |
|---|---|---|---|
| A. Document detection | Every analyzed frame | **Custom, INT8, 256×256 seg net** | ≤ 25 ms |
| B. Text recognition | One still, after auto-capture | ML Kit `TextRecognition` | ≤ 600 ms |
| C. Structuring | Same still, after B | Rules (+ optional tiny classifier) | ≤ 30 ms |

Stage A is the thing that gets benchmarked to death. Stage B is a black box we measure but
don't optimize. This split is what makes the performance story honest: the per-frame budget
is real and tight, and every millisecond of quantization gain is visible as frames-per-second.

### D2 — Segmentation, not corner regression

The obvious cheap alternative for stage A is a 4-corner regressor (input image → 8 floats).
It's smaller and faster. It's also rejected, because:

- It has no meaningful confidence signal. A corner regressor always outputs four corners,
  including when there is no receipt in frame. Auto-capture needs "is there a document
  here at all?" and a regressor answers that badly.
- Receipts are frequently curled, folded, torn, or partially out of frame. A quadrilateral
  is the wrong prior; a per-pixel text/paper mask degrades gracefully.
- The mask is reusable — it gives sharpness and coverage estimates for free, which the
  auto-capture state machine needs anyway.

The quad is *derived* from the mask (threshold → largest connected component → contour →
`minAreaRect` / 4-point approx), which costs ~2–4 ms with OpenCV and gives us both signals.

### D3 — Export path: `ai-edge-torch`, with `onnx2tf` as the escape hatch

Three routes exist from PyTorch to LiteRT:

1. `torch → ONNX → onnx-tf → TFLite` — legacy, brittle, produces transpose-riddled graphs
   because of the NCHW/NHWC mismatch. **Rejected.**
2. `torch → ONNX → onnx2tf` — genuinely good, handles layout conversion properly, actively
   maintained. **Fallback.**
3. `ai_edge_torch.convert(...)` — Google's official `torch.export`-based converter,
   integrates with PT2E quantization. **Primary.**

Primary path, in the model repo:

```python
import ai_edge_torch
from ai_edge_torch.quantize import pt2e_quantizer, quant_config

sample = (torch.randn(1, 3, 256, 256),)
edge = ai_edge_torch.convert(model.eval(), sample)   # FP32 baseline
edge.export("build/detector_fp32.tflite")
```

If a layer refuses to convert (custom ops, dynamic shapes, exotic upsampling), fall back to
route 2 rather than fighting the converter. Design the network from day one to only use
converter-friendly ops: `Conv2d`, `BatchNorm2d`, `ReLU6`/`Hardswish`, `nn.Upsample` with
`mode="nearest"`, `Sigmoid`, `AdaptiveAvgPool2d` with fixed output. No `grid_sample`, no
`einsum`, no dynamic control flow, no `interpolate(mode="bilinear", align_corners=True)`.

### D4 — Quantization: full-integer INT8, per-channel weights, float I/O

Four variants get built and measured. All four ship in the debug APK so the benchmark
screen can switch between them at runtime:

| Variant | Weights | Activations | Delegate | Purpose |
|---|---|---|---|---|
| `fp32` | FP32 | FP32 | XNNPACK | Accuracy ceiling, size ceiling |
| `fp16` | FP16 | FP32 | GPU | Best-case GPU path |
| `int8_dr` | INT8 | FP32 (dynamic range) | XNNPACK | Cheap win, no calibration data |
| `int8_full` | INT8 per-channel | INT8 | XNNPACK | **Shipping variant** |

`int8_full` is the shipping default. Calibration uses ~300 representative frames drawn from
the held-out split, matching the real preprocessing pipeline exactly (same resize, same
normalization) — calibration mismatch is the number one cause of "quantization destroyed my
accuracy" and it's almost always a preprocessing bug, not a quantization limitation.

Input/output tensors stay **float32** even in `int8_full`. The quantize/dequantize ops at
the boundary cost <1 ms and save us from doing scale/zero-point arithmetic by hand in
Kotlin on every frame. Revisit only if profiling shows the boundary ops are material.

### D5 — Delegate: XNNPACK CPU is the default, GPU is a measured alternative

Counter-intuitive but correct for this workload. The model is small (~2 MB, 256×256 input).
GPU delegate has fixed per-inference overhead — upload, kernel dispatch, download,
occasional shader recompilation — that a small model can't amortize. XNNPACK with INT8 and
2 threads is frequently faster *and* far more predictable (tighter p95, which is what
actually matters for frame pacing).

The design does not assume this. It measures it. `int8_full` on XNNPACK is the default;
`fp16` on GPU is selectable in the benchmark screen and the numbers go in the results table.
If GPU wins on the low-end profile, the default flips — that's a data-driven change of one
enum value, and the architecture supports it.

Thread count: **2**, not 4. Budget SoCs have 4–6 cores of which the big ones are already
busy with camera, preview composition, and the UI thread. Oversubscribing raises p95 even
when it lowers p50. This gets measured too (1/2/4 threads × 3 delegates × 4 variants is a
48-cell grid, and the benchmark harness runs all of it unattended).

### D6 — Analyzer output format: `RGBA_8888`

CameraX can hand the analyzer either `YUV_420_888` (native) or `RGBA_8888` (CameraX does
the conversion internally, in optimized native code). Hand-rolling YUV→RGB in Kotlin is
slow; doing it in JNI/libyuv is fast but is a whole extra dependency and a source of
subtle color bugs.

Take `RGBA_8888`. The conversion cost lands inside CameraX rather than in our measured
inference time, and preprocessing (crop → resize → normalize) becomes a straightforward
`ImageProcessor` chain. **Preprocessing is timed and reported as its own stage** so the
cost is visible and not quietly hidden inside "inference latency."

### D7 — Analysis resolution decoupled from preview and capture

| Use case | Resolution | Why |
|---|---|---|
| Preview | Whatever the display wants (~1080p) | User-facing |
| Analysis | 640×480, `KEEP_ONLY_LATEST` | Downscale to 256×256 anyway |
| Capture | Max, or ≥ 8 MP | OCR needs the pixels |

Requesting 1080p for analysis is the single most common way to wreck an ML camera app: you
pay ISP bandwidth, buffer copies, and conversion cost for pixels you immediately throw away.

---

## 3. System architecture

```
┌───────────────────────────────────────────────────────────────────────┐
│                          UI (Jetpack Compose)                          │
│   ScanScreen · OverlayCanvas · ResultSheet · HistoryScreen · BenchScreen│
└───────────────▲──────────────────────────────────────┬────────────────┘
                │ StateFlow<ScanUiState>               │ intents
┌───────────────┴──────────────────────────────────────▼────────────────┐
│                             ScanViewModel                              │
│              auto-capture state machine · orchestration                │
└──┬──────────────────┬──────────────────────┬──────────────────┬───────┘
   │                  │                      │                  │
┌──▼───────────┐ ┌────▼─────────┐ ┌──────────▼───────┐ ┌────────▼──────┐
│ :core:camera │ │  :core:ml    │ │    :core:ocr     │ │ :core:bench   │
│              │ │              │ │                  │ │               │
│ CameraBinder │ │ Detector     │ │ MlKitRecognizer  │ │ LatencyRec.   │
│ FrameAnalyzer│ │ Preprocess   │ │ Perspective warp │ │ FrameStats    │
│ FrameClock   │ │ Postprocess  │ │                  │ │ ThermalWatch  │
│              │ │ ModelRegistry│ │                  │ │ BenchRunner   │
└──┬───────────┘ └────┬─────────┘ └──────────┬───────┘ └────────┬──────┘
   │                  │                      │                  │
   │            ┌─────▼──────┐         ┌─────▼──────┐    ┌──────▼──────┐
   │            │  LiteRT    │         │   ML Kit   │    │    Room     │
   │            │Interpreter │         │  (bundled) │    │  scans +    │
   │            │ +XNNPACK   │         │            │    │ bench_runs  │
   │            └────────────┘         └────────────┘    └─────────────┘
   │
┌──▼──────────────────────────────────────────────────────────────────┐
│                      :core:parse  (pure Kotlin, no Android deps)     │
│         line grouping · price regex · totals reconciliation          │
└──────────────────────────────────────────────────────────────────────┘
```

### Gradle module layout

```
:app                    # DI wiring, navigation, manifest
:core:camera            # CameraX binding, analyzer, frame clock
:core:ml                # LiteRT interpreter, pre/post-processing, model assets
:core:ocr               # ML Kit wrapper, perspective warp
:core:parse             # pure Kotlin/JVM — unit-testable without a device
:core:bench             # timing, percentiles, thermal, export
:core:data              # Room entities, DAOs, repositories
:core:model             # shared domain types
:feature:scan
:feature:history
:feature:benchmark
```

`:core:parse` being a pure JVM module is deliberate — it's the part with the most fiddly
logic and it gets fast JVM unit tests with fixture data instead of instrumented tests.

**Critical build config** in `:core:ml/build.gradle.kts`:

```kotlin
android {
    androidResources { noCompress += listOf("tflite", "lite") }
}
```

Without this, AAPT compresses the model, LiteRT can't memory-map it, and you pay a full
decompress + heap copy at startup. Silent, easy to miss, costs hundreds of milliseconds.

---

## 4. The per-frame pipeline

### 4.1 Threading

```
Camera HAL thread ──► CameraX internal ──► analysisExecutor (single thread)
                                                   │
                                    preprocess → infer → postprocess
                                                   │
                                          MutableStateFlow<FrameResult>
                                                   │
                                          main thread ──► Compose overlay
```

Rules that are not negotiable:

- **One** analyzer thread: `Executors.newSingleThreadExecutor()`. LiteRT `Interpreter` is
  not thread-safe; confining it to one thread removes an entire class of bug and the
  interpreter's internal threads already exploit multiple cores.
- The analyzer never touches the UI. It writes to a `MutableStateFlow`; Compose collects it.
- `imageProxy.close()` in a `finally` block, always. A leaked ImageProxy stalls the whole
  pipeline within ~3 frames and looks like a hang.
- Capture + OCR runs on `Dispatchers.Default`, and analysis is **paused** for its duration
  (`ImageAnalysis.clearAnalyzer()` then re-set). Competing for cores during OCR is the
  worst-case latency scenario and there's no reason to allow it.

### 4.2 Frame path in detail

```kotlin
override fun analyze(image: ImageProxy) {
    val tCallback = System.nanoTime()
    try {
        frameClock.onFrameDelivered(image.imageInfo.timestamp, tCallback)

        val tPre = System.nanoTime()
        val input = preprocessor.process(image)      // RGBA → crop → 256² → normalize
        val tInferStart = System.nanoTime()

        interpreter.run(input.buffer, outputMask)    // [1,64,64,1] float probs
        val nativeNs = interpreter.lastNativeInferenceDurationNanoseconds
        val tInferEnd = System.nanoTime()

        val det = postprocessor.process(outputMask, image.imageInfo.rotationDegrees)
        val tEnd = System.nanoTime()

        recorder.record(
            preNs   = tInferStart - tPre,
            wallNs  = tInferEnd - tInferStart,
            nativeNs = nativeNs,
            postNs  = tEnd - tInferEnd,
        )
        _frames.value = FrameResult(det, tEnd)
    } finally {
        image.close()
    }
}
```

Recording both **wall-clock** and `lastNativeInferenceDurationNanoseconds` is deliberate.
The gap between them is JNI + buffer-copy overhead, and on some devices it is 20–30% of the
total. Reporting only native time flatters the model; reporting only wall time hides where
the cost actually is. Report both.

### 4.3 Preprocessing

```
ImageProxy (RGBA_8888, 640×480)
  → wrap plane[0].buffer as TensorImage (no copy)
  → ResizeWithCropOrPadOp   center-crop to square
  → ResizeOp(256, 256, BILINEAR)
  → Rot90Op(rotationDegrees / 90)
  → NormalizeOp(mean = 127.5f, std = 127.5f)
  → ByteBuffer, direct, native order, allocated ONCE and reused
```

Every buffer in the hot path is allocated once at startup and reused. A per-frame
allocation of a 256×256×3 float buffer is 768 KB of garbage at 30 FPS = 23 MB/s of
allocation pressure, which on a budget device means GC pauses that show up directly as p95
latency spikes and dropped frames.

### 4.4 Postprocessing

```
[1,64,64,1] float probability map
  → threshold at τ = 0.5  (τ is a tuned hyperparameter, not a magic constant)
  → OpenCV findContours on the binary mask
  → largest contour by area; reject if area < 8% of frame
  → approxPolyDP → if 4 points, use them; else minAreaRect
  → scale quad back to preview coordinates
  → sharpness: variance of Laplacian inside the quad, on the downscaled Y channel
  → DetectionResult(quad, confidence, coverage, sharpness)
```

OpenCV is pulled in for `findContours`, `minAreaRect`, `getPerspectiveTransform`, and
`warpPerspective`. It costs roughly 15–25 MB per ABI. Mitigate with ABI splits and by
shipping only `arm64-v8a` + `armeabi-v7a`. Hand-rolling connected components is ~200 lines
and plausible, but the perspective warp for the capture path is needed regardless, so the
dependency is paying for itself twice.

### 4.5 Auto-capture state machine

```
                 conf < 0.4
        ┌──────────────────────────┐
        ▼                          │
   ┌─────────┐  conf ≥ 0.4   ┌─────────────┐
   │SEARCHING│──────────────►│  ALIGNING   │
   └─────────┘               └──────┬──────┘
                                    │ coverage ≥ 0.35
                                    │ ∧ sharpness ≥ S_min
                                    │ ∧ |quad drift| < 8 px
                                    ▼
                             ┌─────────────┐  8 consecutive frames
                             │   STABLE    │──────────────────────┐
                             └─────────────┘                      ▼
                                                          ┌──────────────┐
   ┌──────────┐    ┌────────────┐    ┌──────────┐         │  CAPTURING   │
   │  RESULT  │◄───│  PARSING   │◄───│ RECOGNIZE│◄────────└──────────────┘
   └──────────┘    └────────────┘    └──────────┘
```

Eight consecutive stable frames ≈ 270 ms at 30 FPS — long enough to reject a hand still
settling, short enough not to feel sluggish. Hysteresis on the SEARCHING↔ALIGNING edge
(enter at 0.4, exit at 0.3) prevents overlay flicker at the threshold.

A manual shutter button is always available. Auto-capture is an accelerator, never a gate.

---

## 5. The model

### 5.1 Architecture

```
Input  1×3×256×256  (RGB, normalized to [-1, 1])

MobileNetV3-Small backbone, width multiplier 0.75, ImageNet-pretrained
  ├─ C2  stride  4   →  1/4   scale, 16 ch
  ├─ C3  stride  8   →  1/8   scale, 24 ch
  ├─ C4  stride 16   →  1/16  scale, 40 ch
  └─ C5  stride 32   →  1/32  scale, 96 ch

Lightweight FPN (all lateral convs 1×1 → 32 ch, nearest-neighbour upsample, add)
  └─ fused feature at 1/8 scale, 32 ch

Head: 3×3 depthwise-separable conv → 32 ch → ReLU6
      1×1 conv → 1 ch → Sigmoid

Output 1×64×64×1  (per-pixel P(document))
```

Design constraints baked in:
- Nearest-neighbour upsampling only (bilinear resize converts inconsistently and quantizes
  poorly)
- ReLU6 / Hardswish only — bounded activations quantize far better than unbounded ReLU
- No squeeze-excite in the head (the sigmoid-multiply pattern is a quantization landmine;
  the backbone's SE blocks are kept because they're pretrained and per-channel quantization
  handles them adequately — this is verified, not assumed)
- Fixed input shape, no dynamic axes

Estimated ~1.4 M parameters. Targets (to be confirmed by measurement, not asserted):

| Variant | Target size |
|---|---|
| `fp32` | ~5.6 MB |
| `fp16` | ~2.8 MB |
| `int8_full` | ~1.5 MB |

### 5.2 Training data

| Source | Content | License note |
|---|---|---|
| CORD | 1000 Indonesian receipts, annotated | CC BY-SA 4.0 |
| SROIE (ICDAR'19) | 1000 scanned receipts | Research use — check terms |
| MIDV-2020 | Document photos, varied conditions | Research use |
| Self-collected | Menus + local receipts, ~500 images | Yours |
| Synthetic | Composited receipts on backgrounds | Generated |

Menus have no good public dataset, so they come from self-collection plus synthesis:
render receipt/menu textures, apply thin-plate-spline warp (curl), perspective transform,
lighting gradient, motion blur, JPEG recompression, composite onto varied backgrounds. The
synthetic pipeline is worth building — it's the only way to get enough coverage of the
"crumpled receipt on a dark restaurant table" case that dominates real failures.

Split by **source document**, never by image. Augmented variants of the same receipt in
both train and test is the classic leak and it produces beautiful, meaningless metrics.

### 5.3 Training and export pipeline

```
model/
├── data/            dataset, augmentation, synthetic generator
├── nets/            backbone, fpn, head
├── train.py         BCE + Dice loss, cosine LR, AMP, early stop on val IoU
├── calibrate.py     builds the 300-frame representative dataset
├── export.py        FP32 / FP16 / INT8-DR / INT8-full → build/*.tflite
├── evaluate.py      IoU, F1, corner error per variant, on held-out set
└── model_card.py    emits model_card.json
```

`model_card.json` ships alongside each `.tflite` and is the contract between the model repo
and the app:

```json
{
  "variant": "int8_full",
  "sha256": "…",
  "bytes": 1548912,
  "input":  { "shape": [1,256,256,3], "dtype": "float32",
              "mean": 127.5, "std": 127.5 },
  "output": { "shape": [1,64,64,1],   "dtype": "float32" },
  "threshold": 0.5,
  "metrics": { "iou": 0.0, "f1": 0.0, "corner_mae_px": 0.0 },
  "trained_commit": "…"
}
```

The app parses this at startup and **asserts** shape/dtype match before the first inference.
A silently changed model contract is the kind of bug that eats an evening.

---

## 6. Benchmark harness

This is a first-class feature, not a debug afterthought. It lives in `:core:bench` and has
its own screen.

### 6.1 Latency

Two independent measurement paths, because a single one is not trustworthy:

**In-app (`BenchRunner`)** — deterministic, no camera involved. Loads 50 fixed frames from
assets, replays them through the real pipeline.

```
warmup:     20 iterations, discarded
            (first inference includes delegate init + XNNPACK weight repacking —
             it is routinely 10–50× the steady-state figure)
cold-start: reported separately, from a fresh Interpreter, as its own metric
measured:   300 iterations
reported:   per stage {preprocess, inference-wall, inference-native, postprocess}
            × {mean, stddev, p50, p90, p95, p99, min, max}
```

Percentiles from raw sorted samples — never from a running approximation. 300 samples is
2.4 KB of longs; there is no reason to approximate.

**Ground truth (`benchmark_model`)** — the official LiteRT benchmark binary, pushed via ADB,
run outside the app entirely. If the in-app number and the binary's number disagree by more
than ~15%, the app harness has a bug. This cross-check is the point.

**Microbenchmark** — `androidx.benchmark:benchmark-junit4` around the inference call in CI,
so regressions are caught automatically rather than noticed later.

### 6.2 Frame drops

CameraX with `KEEP_ONLY_LATEST` silently discards frames — that's its job — so drops must
be inferred. Three complementary signals:

**1. Delivered-frame gap analysis (`FrameClock`)**

`ImageProxy.imageInfo.timestamp` is a sensor-domain timestamp. Consecutive analyzed frames
should be ~1/fps apart; a gap of k× the nominal interval means k−1 frames were dropped.

```
nominalIntervalNs = 1e9 / targetFps          (from CONTROL_AE_TARGET_FPS_RANGE)
gap               = t[i] - t[i-1]
dropped[i]        = round(gap / nominalIntervalNs) - 1
dropRate          = Σ dropped / (Σ dropped + analyzedCount)
```

Also reported: effective analysis FPS, and the analyzer's duty cycle
(time-in-analyze ÷ wall time), which tells you how much headroom is left.

**2. UI jank (`JankStats`)** — `androidx.metrics:metrics-performance`. Reports the
percentage of frames exceeding the frame deadline, with state annotations
(`"scanState" → "ALIGNING"`) so jank can be attributed to a pipeline stage.

**3. Macrobenchmark `FrameTimingMetric`** — CI-runnable, produces `frameDurationCpuMs`
p50/p90/p95/p99 for a scripted 30-second scanning session. This is the closest thing to an
objective, reproducible frame-health number.

### 6.3 Thermal and sustained load

A 30-second benchmark on a cold phone is close to a lie. Budget SoCs throttle hard.

- `PowerManager.addThermalStatusListener` — record status transitions with timestamps
- **10-minute sustained run**: continuous inference, latency bucketed into 30-second
  windows, plotted as p50 and p95 over time
- Report the throttle knee: at what elapsed time does p95 cross 1.5× its initial value
- Sample `BatteryManager.BATTERY_PROPERTY_CURRENT_NOW` every 5 s for a rough power figure

The sustained-load curve is the most informative single chart this project will produce.

### 6.4 Accuracy vs. quantization

Speed without accuracy is meaningless. `evaluate.py` produces, on the held-out set:

| Variant | Size | p50 (ms) | p95 (ms) | IoU | F1 | Corner MAE (px) |
|---|---|---|---|---|---|---|
| fp32 / XNNPACK | | | | | | |
| fp16 / GPU | | | | | | |
| int8_dr / XNNPACK | | | | | | |
| int8_full / XNNPACK | | | | | | |

Plus a per-layer diff (FP32 vs INT8 activation MSE) to identify which layers quantize badly,
which is the actionable output when accuracy drops more than expected.

### 6.5 Device matrix

"Low-end device profile" needs to mean something specific. Emulator numbers are not
credible for perf work — no thermal model, host-CPU-dependent, no real ISP.

| Tier | Example | Role |
|---|---|---|
| High | Recent flagship | Headroom ceiling |
| Mid | ~3-year-old midrange | Realistic target |
| **Low** | Snapdragon 4-series / Helio G-series, 4 GB RAM, Android 11 | **The number that matters** |

Every reported metric is tagged with `Build.SOC_MODEL` (API 31+), `Build.MODEL`, Android
version, thermal status at start and end, and the variant/delegate/thread configuration.

### 6.6 Results storage and export

Benchmark runs go into Room (`bench_runs` + `bench_samples`) and export as JSON and CSV via
`Intent.ACTION_CREATE_DOCUMENT`. Raw samples are kept, not just percentiles — you will want
to re-derive a different statistic later, and you will not want to re-run the matrix.

---

## 7. Capture, OCR, parsing

### 7.1 Capture

On entering `CAPTURING`: `ImageCapture.takePicture()` at max resolution, latest quad from
the analyzer converted to full-resolution coordinates, `getPerspectiveTransform` +
`warpPerspective` to a rectified image with the receipt's estimated aspect ratio. Rectified
input measurably improves ML Kit's accuracy on angled shots — it's not cosmetic.

### 7.2 Recognition

ML Kit `TextRecognition` with the **bundled** model (`text-recognition:16.x`), not the
Play-Services-downloaded variant. Bundled costs a few MB of APK and removes a first-run
network dependency and a whole error path. For a scanner that must work offline in a
restaurant basement, that trade is obvious.

Output: blocks → lines → elements, each with a bounding box and confidence.

### 7.3 Parsing (`:core:parse`, pure Kotlin)

```
1. Deskew:   estimate residual text-baseline angle, rotate boxes
2. Group:    cluster elements into rows by y-overlap (tolerance = 0.6 × median height)
3. Columns:  detect the price column — rightmost cluster of currency-shaped tokens
4. Classify: each row → HEADER | ITEM | MODIFIER | SUBTOTAL | TAX | TIP | TOTAL | FOOTER
5. Extract:  qty (leading integer / "2x"), name (middle), price (right column)
6. Reconcile: Σ items + tax + tip ≟ total.  Mismatch ⇒ confidence penalty + UI flag
```

Step 4 is rules first: keyword matching (multilingual lexicon), position in document,
currency-token presence, indentation. **Then measure the error rate.** Only if rules plateau
does a model appear — and it would be a ~50 KB gradient-boosted tree or 1D-CNN over hand
features (relative x/y, box dimensions, digit ratio, currency-symbol presence, keyword hits),
not anything larger.

Step 6 is the quality signal that makes the app trustworthy. Arithmetic that doesn't
reconcile is shown to the user as "check these lines" rather than silently presented as fact.

---

## 8. Data model

```kotlin
@Entity data class ScanEntity(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val imagePath: String,          // rectified capture, app-internal storage
    val merchant: String?,
    val currency: String?,
    val subtotalCents: Long?,
    val taxCents: Long?,
    val totalCents: Long?,
    val reconciled: Boolean,
    val detectorVariant: String,    // provenance: which model produced this
    val detectorSha: String,
)

@Entity data class LineItemEntity(
    @PrimaryKey val id: String,
    val scanId: String,             // FK, CASCADE
    val ordinal: Int,
    val name: String,
    val quantity: Int?,
    val unitPriceCents: Long?,
    val totalPriceCents: Long?,
    val ocrConfidence: Float,
    val userEdited: Boolean,
)

@Entity data class BenchRunEntity(
    @PrimaryKey val id: String,
    val startedAt: Long,
    val variant: String, val delegate: String, val threads: Int,
    val socModel: String, val deviceModel: String, val androidSdk: Int,
    val thermalAtStart: Int, val thermalAtEnd: Int,
    val warmupIters: Int, val measuredIters: Int,
    val coldStartNs: Long,
    val statsJson: String,          // full percentile block, all stages
)
```

**Money is `Long` cents. Never `Double`, never `Float`.** Parse the OCR string to cents
directly with integer arithmetic; the moment a price touches a float, `19.99` becomes
`19.989999` and totals stop reconciling.

`detectorVariant` + `detectorSha` on every scan means a past result can always be traced to
the exact model that produced it. That matters when comparing quantization variants on real
usage rather than a fixed test set.

---

## 9. Phased build plan

Each phase ends with something measurable. Nothing proceeds on an unvalidated foundation.

**Phase 0 — Pipeline skeleton, no real model (~1 week)**
CameraX preview + `ImageAnalysis` + a dummy `.tflite` (a single conv, correct shapes).
Full timing instrumentation live. Overlay draws a hardcoded quad.
*Gate:* sustained 30 FPS analysis on the low-end device with a trivial model, drop rate < 5%,
zero ImageProxy leaks over a 10-minute run. If the harness can't hit this with a no-op model,
no real model will fix it — fix the plumbing first.

**Phase 1 — Model, trained and exported (~2 weeks)**
Dataset assembly, synthetic generator, training loop, `ai-edge-torch` export of all four
variants, `evaluate.py`, `model_card.json`.
*Gate:* FP32 val IoU ≥ 0.85; `int8_full` within 3 IoU points of FP32; all four variants
convert cleanly and load in the LiteRT Python interpreter with matching outputs.

**Phase 2 — Real inference in the analyzer (~1 week)**
Swap the dummy model out. Real preprocessing, real postprocessing, live overlay, auto-capture
state machine.
*Gate:* `int8_full` p95 ≤ 25 ms and drop rate ≤ 10% on the low-end device.

**Phase 3 — Benchmark harness (~1.5 weeks)**
`BenchRunner`, the 48-cell config matrix, JankStats, thermal watch, sustained-load run,
CSV/JSON export, Macrobenchmark in CI, ADB `benchmark_model` cross-check.
*Gate:* in-app and ADB latency figures agree within 15%. Complete results table filled in.

**Phase 4 — Capture → OCR → parse (~2 weeks)**
Perspective warp, ML Kit integration, the parser, reconciliation.
*Gate:* on 50 held-out real receipts, ≥ 85% of line items extracted with correct name and
price; ≥ 95% of totals reconcile or are correctly flagged.

**Phase 5 — App shell (~1 week)**
History, editing, delete, share, permission flows, empty and error states.

**Phase 6 — Optional: learned line classifier**
Only if Phase 4's rules measurably plateau. Gated on evidence, not enthusiasm.

---

## 10. Risks

| Risk | Impact | Mitigation |
|---|---|---|
| `ai-edge-torch` fails on some op | Blocks Phase 1 | Op allowlist enforced from day one; `onnx2tf` fallback; convert a stub network in week 1 before writing the real one |
| INT8 costs more accuracy than expected | Ships a worse model | Per-layer activation-MSE diff identifies the culprit; selective FP16 fallback for that layer; QAT as last resort |
| OpenCV inflates APK by ~40 MB | User-visible | ABI splits, arm-only, App Bundle; measure and consider hand-rolled contour extraction if it's the deciding factor |
| Preview + analysis + OCR contend for cores | p95 blowout | Analysis paused during OCR; thread count tuned by measurement; duty cycle tracked continuously |
| ML Kit weak on thermal-paper receipts | Poor extraction | Measure first. Contrast normalization + adaptive threshold on the rectified image before OCR. Custom CRNN stays out of scope until data says otherwise |
| Benchmark numbers are unreproducible | Undermines the whole point | Fixed replay frames, ADB cross-check, thermal state recorded, raw samples persisted |
| Low-end device unavailable | Headline metric missing | Buy one. A ₹7–10k device is the cheapest credible part of this project |

---

## 11. Open questions

1. **Menu vs. receipt — one model or two?** Menus are larger, multi-column, and often
   glossy/reflective. Current assumption: one detector handles both (it's finding a
   document, not reading it) but the *parser* branches. Needs validation on real menu
   photos in Phase 1.
2. **Which low-end device is the reference?** Everything downstream calibrates to it. Pick
   before Phase 0's gate, not after.
3. **Is 256×256 the right input?** 192×192 would be ~1.8× faster; 320×320 would detect
   small/distant receipts better. Train at 256 first, then sweep — it's a cheap experiment
   once the pipeline exists.
4. **Multi-currency?** Affects the price regex and the lexicon. Defaulting to locale-driven
   single currency unless there's a reason otherwise.

---

## Appendix A — Key dependencies

```
CameraX                       androidx.camera:camera-{core,camera2,lifecycle,view}
LiteRT                        org.tensorflow:tensorflow-lite
                              org.tensorflow:tensorflow-lite-gpu (+ -api)
                              org.tensorflow:tensorflow-lite-support
ML Kit                        com.google.mlkit:text-recognition   (bundled)
OpenCV                        org.opencv:opencv
Jank/perf                     androidx.metrics:metrics-performance
                              androidx.benchmark:benchmark-{junit4,macro-junit4}
Persistence                   androidx.room:room-{runtime,ktx}
UI                            androidx.compose.*
DI                            Hilt

Python                        torch, ai-edge-torch, ai-edge-litert,
                              onnx + onnx2tf (fallback), albumentations,
                              opencv-python, numpy
```

## Appendix B — Metrics glossary

| Metric | Definition |
|---|---|
| `inference_wall_ns` | `System.nanoTime()` around `Interpreter.run()`, includes JNI + copies |
| `inference_native_ns` | `Interpreter.lastNativeInferenceDurationNanoseconds` |
| `cold_start_ns` | First inference on a fresh Interpreter, reported separately, never in percentiles |
| `preprocess_ns` | ImageProxy → normalized input tensor |
| `postprocess_ns` | Output mask → `DetectionResult` |
| `analysis_fps` | Frames actually analyzed per second |
| `drop_rate` | Inferred dropped frames ÷ frames produced by the sensor |
| `duty_cycle` | Time spent inside `analyze()` ÷ wall time; headroom indicator |
| `jank_pct` | JankStats frames over deadline ÷ total |
| `throttle_knee_s` | Elapsed seconds until p95 exceeds 1.5× its initial value |
| `model_bytes` | On-disk `.tflite` size, uncompressed |
