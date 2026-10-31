# Camera 3X Fix — Minimal Camera App for Samsung Galaxy S22 Ultra

A minimal, dedicated camera application specifically engineered for the **Samsung Galaxy S22 Ultra (SM-S908E)** with a water-damaged 3x telephoto camera module.

---

## ⚡ What This App Does

- **Only .6x, 1x, and 10x**:
  - **.6x**: Direct open of Ultra-Wide Sensor (Sensor 2, 14mm eq).
  - **1x**: Primary Wide Sensor (Sensor 5, 23mm eq).
  - **10x**: Periscope Optical Telephoto (Sensor 7, 230mm eq).
  - **3x Telephoto (Sensor 6) is completely excluded and never probed**, avoiding the fatal hardware bus crash (`Broken pipe -32` / `ERROR_CAMERA_DEVICE`).
- **Flash (ON / OFF)**:
  - Toggle between **Flash ON** and **Flash OFF**.
  - Uses both in-session torch and direct hardware controller so the flash LED stays on and illuminates the scene in all three lens modes.
- **Fast & Minimal**:
  - No settings menus, no timers, no extra screens.
  - Launches straight into the live camera viewfinder.
  - One-tap shutter button with haptic feedback, shutter sound, and full-resolution saving to `DCIM/Camera`.
  - Tap the gallery thumbnail to preview saved shots in Samsung Gallery.

---

## 🚀 Installation

Download `app-debug.apk` directly to your S22 Ultra and install it.
Ensure Camera permission is granted upon opening.
