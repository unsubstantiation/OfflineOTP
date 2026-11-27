# Desktop camera QR capture

The shared URI import form now offers **Scan QR code…** on the Compose desktop host. A common
consent/configuration dialog collects a local camera device; capture reads a single frame into
memory, then requires explicit Import through the existing OTP/Google-migration validation and
confirmation flow. There is no live preview and no image file is written.

## Runtime requirements

This is an **optional FFmpeg-backed adapter**, not a bundled camera driver. Install a trusted local
FFmpeg build with the platform input enabled and grant camera permission. The default executable
is `ffmpeg` on PATH; `-Dofflineotp.ffmpeg=/absolute/path/to/ffmpeg` can select a trusted executable.
No shell command or downloaded executable is invoked by the application.

- Linux: V4L2 device such as `/dev/video0` (device permission required).
- macOS: numeric AVFoundation camera index such as `0` (OS camera permission required).
- Windows: exact DirectShow camera name. Multi-input separator syntax is rejected.

Position the QR code before capture. A failure reports in the common form; retry or use image/URI
input. Missing FFmpeg, missing/unsupported device or OS denial does not disable other imports.

## Safety and lifecycle

Capture is explicit, one frame, output bounded to the document limit, dimensions bounded by the
existing ZXing image reader, with a ten-second process deadline. No microphone output, network
source, saved image, raw diagnostic log or automatic import is requested. Captured bytes are wiped
after decoding. Closing/locking revokes the pending operation; the cancellation watcher terminates
the process and stale decoded results cannot publish into a replaced importer/session.

Tests cover platform argument validation (no shell/network/multiple-input source), revoked launch,
worker cancellation, hidden result staging and one-time consumption. They do not exercise real
FFmpeg, permission prompts or cameras. All three desktop OSes require native testing before this
conditional capability is described as release-verified parity. Automatic-backup workflow and iOS
hardware-management gaps are separate and are not fixed by this adapter.
