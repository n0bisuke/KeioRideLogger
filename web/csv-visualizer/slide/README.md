# LT Slide Presenter

The static page is available at `/slide/` when the dashboard is served from its web root. It shows a new opening slide, a question-led hook, the original deck's remaining 32 slides, and a new summary (35 slides total), plus a persistent live widget for speed-responsive character art and a small speed/RSRP chart. Spoken transitions are in `docs/lt/presenter-talk-track.md`.

- Navigate with the on-screen arrows or ← / → / Page Up / Page Down. Press `F` for fullscreen.
- Drag the live widget by its header; resize from its lower-right corner. Its placement and size are saved locally in this browser only.
- Select **Android BLE 接続** to connect this page directly to Keio Ride Logger. Web Bluetooth needs HTTPS and a supported browser, such as desktop Chrome or Edge. This page makes no Wi-Fi, HTTP, server, or cloud telemetry connection.
- The source deck export is image-based, so slide text is not individually editable here. Replace files in `deck/` to update slide images.

The existing slide images were extracted from `docs/lt/keio-ride-logger-iotlt-draft.pptx`; the original title slide is replaced by `deck/opening.svg`. `deck/opening.svg`, `deck/hook.svg`, and `deck/summary.svg` are the vector sources; matching PNGs are used in the browser and PPTX.
