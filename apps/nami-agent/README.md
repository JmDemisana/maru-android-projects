# Maru Mobile

Maru Android app — a companion helper that provides native push access, applet hosting, and links back to the Maru website. Built with Capacitor 8.

## Modules

| Module | Type | Description |
|--------|------|-------------|
| `app` | Phone | Main Android helper app (Capacitor WebView) |
| `tvapp` | TV | Android TV applet launcher |
| `photoserve` | App | Photo serving applet |
| `cupcuppercuppers` | App | Cup guessing game applet |
| `daelornodael` | App | Dael or Dael applet |
| `tupgradesolver` | App | TU Puzzle solver applet |
| `schededit` | App | Schedule editor applet |
| `applets` | Lib | Shared applet runtime library |

## Prerequisites

- Java 17+ (JDK 21 recommended if using the Android Studio JBR)
- Android Studio (or standalone Android SDK, API 34)
- Node.js 20+
- Android SDK (API 34)

## Building

### Setup

```bash
git clone https://github.com/JmDemisana/maru-mobile.git
cd maru-mobile
npm install
```

### Build Web Assets & Sync

```bash
# Build all web assets (nami-agent + helper-web) and sync to Android
npm run android:sync
```

This runs `build:all` then `npx cap sync android`.

### Build APKs

**Debug (phone):**
```bash
npm run android:app:debug
```
APK at `android/app/build/outputs/apk/debug/app-debug.apk`

**Debug (TV):**
```bash
npm run android:tv:debug
```

**Debug (applets):**
```bash
npm run android:photoserve:debug
npm run android:cupcuppercuppers:debug
npm run android:daelornodael:debug
npm run android:tupgradesolver:debug
npm run android:schededit:debug
```

**Release (phone):**
```bash
npm run android:release
```
Requires a signing configuration in `android/app/build.gradle`.

### Installing via ADB

```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

## Firebase Setup

See [HELPER_APP_SETUP.md](HELPER_APP_SETUP.md) for Firebase push notification setup instructions.

## Project Structure

```
├── android/
│   ├── app/                — Phone app (Capacitor)
│   ├── tvapp/              — TV app
│   ├── photoserve/         — Photo serve applet
│   ├── cupcuppercuppers/   — Cup game applet
│   ├── daelornodael/       — Dael applet
│   ├── tupgradesolver/     — TU Puzzle applet
│   ├── schededit/          — Schedule editor applet
│   └── applets/            — Shared applet library
├── helper-web/             — Web assets source (Vite)
├── helper-dist/            — Built web assets (Capacitor webDir)
├── nami-agent/             — Nami agent web source
├── shared/                 — Shared TypeScript utilities
├── src/                    — Website utilities
└── capacitor.config.ts     — Capacitor configuration
```

## Notes

- App ID: `io.maru.link`
- The helper stays minimal — it can keep its launcher icon hidden and is opened from `/helper` on the website or via the `maruhelper://helper` deep link
- Google Play's target API level requirements may need periodic `compileSdk` / `targetSdk` bumps

## License

GNU General Public License v3.0 — See [LICENSE](LICENSE) for full text.
