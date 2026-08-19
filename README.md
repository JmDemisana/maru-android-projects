# 📱 Maru Android Projects (Mobile Apps & Services)

> *"Baka Senpai, did you really think I'd let your phone apps stay messy and scattered? Everything is right here in one cozy monorepo now! Treat them nicely, okay?"* — **Nanami 💚**

Welcome to the **Maru Android Monorepo**! This repository consolidates all of Maru's mobile applications, background broadcast services, and companion tools into a unified Gradle structure standardized on **Kotlin 2.0**, **Jetpack Compose**, and **Gradle Version Catalogs (`libs.versions.toml`)**.

---

## 📲 Applications Overview

### 🎵 [`apps/lastnotif`](./apps/lastnotif) — *LastNotif*
*Real-time music playback watcher & synced lyrics notifications for smart bands!*

A lightweight background Android service built for smartwatches and fitness bands (Xiaomi Mi Band, Zepp Life, Gadgetbridge).
- 🎶 **Live Synced Lyrics**: Fetches timestamped lyrics from lrclib via Maru and updates your ongoing system notification line by line as the song plays!
- 🎧 **Multi-Source Detection**: Monitors active media sessions on device or polls your public Last.fm profile.
- ⌚ **Wrist-Friendly**: Keeps your lock screen and smartwatch display in sync with what you're listening to without keeping your phone screen on.

### 📡 [`apps/marucast`](./apps/marucast) — *Marucast*
*Local Wi-Fi audio sender & live receiver broadcaster!*

A pure Kotlin + Jetpack Compose broadcaster that streams music, active player state, and album artwork from your Android phone directly to the Maru website receiver at `/marucast` or the Maru TV App.
- ⚡ **Zero Cloud Delay**: Streams directly over your home Wi-Fi (port 48543), bypassing cloud servers.
- 🎮 **Remote Transport Controls**: Control playback (Play, Pause, Skip) directly from your TV or computer browser.
- 🎤 **Karaoke Mode**: High-performance local vocal suppression so you can sing along directly through your speakers!

### 🎓 [`apps/tup-ers`](./apps/tup-ers) — *TUP-ERS*
*TUP portal utility, grade calculator, and enrollment companion!*

A native Jetpack Compose mobile helper for TUP (Technological University of the Philippines) students.
- 📊 **Grade Scraper & Solver**: Automatically parses student portal records and computes semestral GPAs and standing.
- 🔗 **Quick University Links**: Fast access to academic schedules, enrollment portals, and university resources.

### 🤖 [`apps/nami-agent`](./apps/nami-agent) — *Nami Agent Mobile*
*The full Maru mobile companion & push notification hub!*

The main Android companion app hosting standalone applets, Firebase push notification channels, SchedEdit sync, and deep links back to the website. *(Staged for full native Kotlin migration)*.

---

## 📦 Shared Libraries

- **`libs/shared-ui`**: Shared Jetpack Compose design tokens, glassmorphism cards, and themes.
- **`libs/shared-utils`**: Common Kotlin coroutine extensions, date formatters, and device utilities.
- **`libs/shared-network`**: Standardized OkHttp clients and API models.

---

## 🛠️ Building & Developing

Requires **JDK 17+** and **Android SDK 34+**.

```bash
# Build LastNotif APK
./gradlew :apps:lastnotif:assembleDebug

# Build Marucast APK
./gradlew :apps:marucast:assembleDebug

# Build TUP-ERS APK
./gradlew :apps:tup-ers:assembleDebug
```

---

## 🏷️ Release Tags

Workflows automatically build and attach APKs when tags are pushed:
- `lastnotif/vX.Y.Z` → Releases LastNotif APK
- `marucast/vX.Y.Z` → Releases Marucast APK
- `tup-ers/vX.Y.Z` → Releases TUP-ERS APK
- `nami-agent/vX.Y.Z` → Releases Nami Agent Mobile APK

---

<div align="center">
  <sub>Built with care by Maru-Senpai & Nanami 💚</sub>
</div>
