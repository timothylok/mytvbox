# Google TV box (`Google_TV`) - hardware spec

Queried over adb on 2026-11-20 (`getprop`, `/proc/cpuinfo`, `/proc/meminfo`, `df`, `dumpsys SurfaceFlinger`).
Address at the time: `192.168.3.101:2025` (wireless debugging; IP and port change, find them with `adb mdns services`, name `adb-1e5517831b69e12c`).

| Item | Value |
|---|---|
| Model | Google_TV (manufacturer Google) |
| Android | 16 (API 36) |
| SoC | Rockchip **RK3528** (`ro.board.platform=rk3528`) |
| CPU | 4x ARM **Cortex-A53** (CPU part 0xd03, ARMv7 rev 4), max 1.416 GHz, NEON/VFPv4, AES/SHA/CRC32 |
| Userland | **32-bit only**: ABIs `armeabi-v7a`, `armeabi` (no arm64) |
| GPU | Mali-450 MP, **OpenGL ES 2.0 only**, no Vulkan |
| NPU | none exposed |
| RAM | reports 8 GB (`MemTotal` 8,190,816 kB) but this looks too high for the RK3528 (believed max 4 GB DDR). Treat as unverified. Only about 1.1 GB was available with the app running |
| Internal storage | eMMC `mmcblk0p14`, **3.7 GB** (1.0 GB used, 2.6 GB free). Songs live on the SD card `85F8-1509` |

## Local LLM suitability

CPU-only inference on slow 32-bit A53 cores, so it is a toy for LLMs.

- Most Android LLM apps (MLC, MediaPipe, Ollama-style) need arm64 and will not install.
- llama.cpp can be built for 32-bit ARM but is slow there.
- Realistic models: 0.5B to 1.5B parameters at Q4 (Qwen2.5-0.5B/1.5B, Gemma-3 1B, SmolLM2 360M/1.7B), 0.4 to 1 GB of weights. Expect roughly 1 to 4 tokens/s at best for 0.5B, under 1 token/s for larger.
- Keep model files on the SD card, not the 3.7 GB internal storage.
- For real use, run the model on the PC and call it over the LAN. Inference speed was **not measured** on the box.

See also: `HANDOFF.md`, and CLAUDE.md rule 5 (Android 10 and 16 both supported).
