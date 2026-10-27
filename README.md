# Lens Isolator — Samsung Galaxy S22 Camera Hardware Diagnostic & Lens Isolation Tool

A specialized Android diagnostic utility engineered to test, isolate, and debug individual camera sensors on devices with damaged camera modules (specifically tailored for the **Samsung Galaxy S22** and multi-camera Android devices).

---

## 🔍 Why Did Your S22 Rear Camera Stop Working While the QR Scanner Still Works?

### 1. The Multi-Camera Architecture of the Galaxy S22
The Samsung Galaxy S22 rear module contains three physical camera sensors:
1. **Primary Wide Sensor (1x)**: 50 MP ISOCELL GN5 (with OIS and Dual Pixel AF)
2. **Telephoto Sensor (3x)**: 10 MP (with OIS and 3x optical zoom)
3. **Ultra-Wide Sensor (0.6x)**: 12 MP (120° FOV)

These three sensors are mounted on a shared module and communicate over common power management rails (AVDD, DVDD) and an I2C control bus connected to the processor.

### 2. Why Normal Camera Apps (and Open Camera) Fail
On modern Android (Android 9+ / API 28+), the rear camera is exposed to apps as a **Logical Multi-Camera Device** (Camera ID `"0"`).
- When Samsung Camera, WhatsApp, Snapchat, or Open Camera starts, the app immediately issues a call to `CameraManager.openCamera("0")`.
- The Samsung Camera HAL receives this command and **attempts to power on and probe all three physical sub-sensors simultaneously** to prepare for smooth optical zoom transitions and sensor fusion.
- When water enters the camera enclosure, it typically causes corrosion or an electrical short on **one specific sub-sensor** (most commonly the telephoto OIS coil or ultra-wide sensor pins).
- When the HAL attempts to communicate with the damaged sensor over the I2C bus, it encounters an I2C ACK timeout or overcurrent condition.
- The HAL daemon (`cameraserver` / `vendor.samsung.hardware.camera.provider`) experiences a fatal exception and reports `ERROR_CAMERA_DEVICE` (Code 4) or `ERROR_CAMERA_SERVICE` (Code 5), **instantly killing the entire camera subsystem for the whole operating system**.
- **Open Camera fails** because it automatically opens the default logical camera on launch before the user can switch lenses.

### 3. Why the QR Code Scanner Works
- Many QR code scanners (like ZXing or legacy barcode scanning libraries) still invoke the legacy **Camera1 API** (`android.hardware.Camera.open(0)`).
- On Samsung devices, the Camera1 compatibility layer does not activate the multi-camera fusion coordinator; it often commands the driver to power **only the 50MP primary wide sensor**, bypassing the damaged telephoto and ultra-wide sensors completely!

---

## 🛠️ How Lens Isolator Solves This

### 1. Zero Auto-Open Policy (100% Passive Discovery)
- On application launch, **no camera sensor is opened** and no preview stream is initialized.
- Lens Isolator queries cached OS metadata (`CameraCharacteristics`) directly from system memory. This query is 100% passive, draws no sensor power, and **cannot trigger a HAL crash**.

### 2. Physical & Logical Camera Enumeration
- Identifies all exposed logical cameras and underlying physical camera IDs (`physicalCameraIds`).
- Reads focal lengths, apertures, 35mm equivalent focal lengths, optical image stabilization (OIS), and megapixel ratings for every sub-sensor.

### 3. "Wired Together" Hardware Warnings
- Clearly indicates when a camera ID is a **Logical Multi-Camera**.
- Explicitly lists every physical sensor bundled with that logical device (e.g. `Wired with: Sensor 2, Sensor 3, Sensor 4`).
- Alerts you that opening that camera will probe all bundled sensors simultaneously.

### 4. Granular Lens Testing Modes
For each camera sensor, you can run:
- **Modern Camera2 Direct Test**: Attempts to open the specific camera device ID directly.
- **Physical Stream Targeting (`setPhysicalCameraId`)**: When testing Logical Camera 0, instructs Camera2 to bind the preview pipeline specifically to one physical sensor (Wide, Telephoto, or Ultra-wide).
- **Legacy Camera1 API Test**: Replicates the exact legacy HAL path used by QR scanners to confirm if the 50MP main sensor works when multi-camera fusion is bypassed.

### 5. Concurrent Dual-Lens Testing
- Select two cameras simultaneously (e.g. Front Camera ID `1` + Rear Camera ID `0`).
- View split real-time preview streams with independent frame rates (FPS) and error monitors to verify if sensor bus collisions occur.

### 6. Live HAL Event Console & Error Decoder
- Live terminal displays every HAL callback with millisecond timestamps:
  - `onOpened`
  - `onConfigured`
  - `onCaptureCompleted`
  - `onError` with human-readable diagnostic explanations for `ERROR_CAMERA_DEVICE`, `ERROR_CAMERA_SERVICE`, `ERROR_MAX_CAMERAS_IN_USE`, etc.

### 7. Exportable Diagnostic Report
- Generates a full markdown report including device hardware identifiers, chip board info, camera enumeration, and test history.
- Share directly via the Android share sheet or copy to clipboard for repair technicians.

---

## 📱 Project Structure

```
s22-camera-fix/
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/s22camerafix/lensisolator/
│       │   ├── MainActivity.kt                      # App entry point & permission flow
│       │   ├── data/
│       │   │   ├── model/CameraInfo.kt              # Data models (Sensors, Hardware Level, Test Results)
│       │   │   ├── CameraDiscoveryManager.kt        # Passive camera characteristics reader
│       │   │   └── DiagnosticReportGenerator.kt     # Formatted report exporter
│       │   ├── engine/
│       │   │   ├── Camera2Tester.kt                 # Camera2 test engine with physical stream support
│       │   │   ├── Camera1LegacyTester.kt           # Legacy Camera1 test engine (QR code mode)
│       │   │   ├── ConcurrentCameraTester.kt        # Dual-camera concurrent test engine
│       │   │   └── TestLogState.kt                  # Real-time log entries
│       │   ├── ui/
│       │   │   ├── components/                      # UI components (SensorCard, LogTerminal, AutoFitTextureView)
│       │   │   ├── screens/                         # Screens (HomeScreen, SingleCameraTestScreen, MultiCameraTestScreen, DiagnosticReportScreen)
│       │   │   └── theme/                           # Modern Material3 cyber diagnostic theme
│       │   └── viewmodel/
│       │       └── CameraViewModel.kt               # Central test coordinator
│       └── res/                                     # Drawables, mipmaps, values
├── .github/workflows/
│   └── build-apk.yml                                # Automated GitHub Actions APK builder
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
└── gradlew / gradlew.bat
```

---

## 🚀 How to Build and Install

### Method 1: Open in Android Studio (Recommended)
1. Open **Android Studio** (Hedgehog 2023.1 or newer).
2. Select **Open** and choose the `s22-camera-fix` directory.
3. Allow Gradle to sync dependencies.
4. Connect your Samsung Galaxy S22 via USB (with **USB Debugging** enabled in Developer Options).
5. Click the green **Run (▶)** button or press `Shift + F10`.

### Method 2: Command Line (CLI)
Ensure JDK 17 is installed, then run:
```bash
# On macOS / Linux:
./gradlew assembleDebug

# On Windows:
gradlew.bat assembleDebug
```
The output APK will be located at:
`app/build/outputs/apk/debug/app-debug.apk`

Install it to your device via ADB:
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Method 3: Automated Build via GitHub Actions
If you don't have Android Studio installed locally:
1. Push this project to a GitHub repository.
2. The included `.github/workflows/build-apk.yml` will automatically build the APK.
3. Go to the **Actions** tab on GitHub, click the latest workflow run, and download the `LensIsolator-debug-apk` artifact.
4. Transfer the `.apk` file to your Galaxy S22 and tap to install!

---

## 🔧 Recommended Repair Action based on Findings

| Test Finding | Probable Cause | Action |
| :--- | :--- | :--- |
| **Legacy Camera1 works, but Camera2 fails with ERROR_CAMERA_DEVICE (4)** | The 50MP primary sensor is functioning, but the Telephoto or Ultra-wide sensor has an I2C/OIS failure causing multi-camera probe crashes. | Replace the rear camera submodule (Samsung Part # `GH96-14769A` or variant for your S22 model). |
| **All rear cameras fail, Front Camera works** | Water shorted the rear camera power rail (PMIC) or corroded the main rear camera FPC connector on the motherboard. | Inspect the rear camera FPC connector under a microscope. Clean corrosion with 99% isopropyl alcohol and replace rear module. |
| **Camera 2 works only when targeting a specific physical ID** | Vendor HAL permits direct stream isolation. | You can use this app to take photos with that specific working lens. |
