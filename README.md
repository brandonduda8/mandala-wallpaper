# Mandala Eclipse Live

An Android GPU live wallpaper built from Brandon's mandala eclipse art —
`com.apexforge.mandalawallpaper`.

## What it does

- **Layer 1 (far):** the mandala, aspect-fill, slow "breathing" zoom (±1.5% / 12s)
- **Layer 2 (mid):** the eclipse core as its own sprite with stronger parallax,
  wrapped in an additive glow that pulses — music-reactive when the device
  grants audio capture, autonomous breath otherwise
- **Layer 3 (near):** ~120 glowing motes drifting upward, twinkling, strongest parallax
- **4D touches:** time-of-day aura (cool nights, golden days), unlock burst
  (a slow ring of light from the eclipse every time it becomes visible),
  tap ripples, gyroscope tilt + home-screen scroll parallax
- **Battery discipline:** 30fps cap, renders only while visible, zero
  allocations in the draw loop, sensors + audio capture only while visible

## Assets

`assets/mandala_base.png` + `assets/eclipse_core.png` are interim crops from a
screenshot — see `assets/README.md` for the clean-swap procedure.

## Gate

`.github/workflows/gate.yml` is the mandatory verification gate: boots an
API-34 emulator, installs the APK, sets it as the live wallpaper, proves
motion via pixel-diff of two screenshots 8s apart, and fails on any crash in
logcat. No APK reaches Brandon without passing.
