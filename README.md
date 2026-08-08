# ReceiptSnap

A scrappy Android app that reads receipts in real time. Point the camera at a
receipt and it OCRs every frame on-device, parses out the merchant, date, line
items and total, and shows a live readout. Tap **Capture** to freeze the current
result and save it with a photo.

Everything runs offline — the OCR model is bundled into the APK.

## Getting the APK

Each push to the build branch produces debug-signed APKs. Grab the latest from
the [Releases page](../../releases), or from the **Build APK** workflow run's
artifacts.

Take **`receiptsnap-arm64.apk`** unless you know otherwise — every Android phone
from roughly 2019 on is arm64. `receiptsnap-arm32.apk` covers older 32-bit
devices, and `receiptsnap-universal.apk` bundles every architecture (including
x86 emulators) at around 48 MB.

To install: allow "install from unknown sources" for your browser or file
manager, then open the downloaded APK.

The build is split per ABI because ML Kit's bundled OCR pipeline is ~11 MB of
native code *per architecture* (`libmlkit_google_ocr_pipeline.so`). That native
inference engine — not the models — is the bulk of the download; the models
themselves are only ~1.4 MB of TFLite graphs under
`assets/mlkit-google-ocr-models/`. Both ship inside the APK, which is what lets
scanning work with no network round trip.

> Debug-signed, so it is not Play Store material — it is a sideload build.

## How it works

| Piece | What it does |
| --- | --- |
| `MainActivity` | CameraX preview + `ImageAnalysis`, feeds frames to ML Kit and renders the readout |
| `ReceiptParser` | Heuristic parser: merchant, date, total, subtotal, tax, line items |
| `ReceiptAccumulator` | Votes across a sliding window of frames so the readout stops flickering |
| `BoxOverlay` | Draws the OCR boxes over the preview; money-bearing lines are highlighted |
| `ReceiptStore` | JSON file + a folder of JPEGs in app-private storage |

The parsing leans on the few conventions receipts actually share: the merchant
is at the top, amounts are right-aligned on their line, and the total is
labelled. A "lock" percentage shows how strongly recent frames agree, so you
know when the reading has settled before you capture.

## Building it yourself

```bash
./gradlew assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest # parser tests
```

Needs JDK 17 and the Android SDK (compileSdk 34). minSdk is 26.
