# Nanami Anime 2.1.2

Nanami Anime is an Android anime player and AniList companion.

[Download the latest signed Android APK](https://github.com/JmDemisana/maru-android-projects/releases/download/nanami-anime-stable/nanami-anime-stable.apk)

## What's new in 2.1.2

- **Smarter source lookup.** Nanami tries romanized, English, and Japanese titles, punctuation variants, and shortened search terms. Playback still checks the episode and edition. An unconfirmed match, a failed provider request, and an empty search now have separate explanations.
- **Broader device support.** The torrent engine includes all four Android architectures, with 16 KB page alignment on the 64-bit builds. Android 8 and 9 prompt for Gallery permission when exporting clips.
- **AniList profiles and cast.** Followers and Following load independently with advanced pages and retry. The Cast tab activates when cast data is available.
- **Updates and diagnostics.** Check for stable updates and copy a device and service report from **Settings → Private Offline Vault & About**.

## What’s new in 2.1.1

- **One download at a time.** Episodes transfer in queue order. You can queue the next five unwatched episodes, all unwatched episodes, or a custom number.
- **More reliable episode matching.** Source lookup uses AniList’s romaji, English, and native titles, preserves source-sensitive punctuation such as the apostrophe in *Rune’s Pharmacy*, and verifies the adult series and episode before offering a stream. The native fallback supports both episodes of the original *Oni Chichi* (*Demon Father*) and episode 1 of *Otome Domain The Animation*. Older adult downloads without verification are skipped during online playback.
- **Account controls for adult content.** Go to **Settings → Account → NSFW Content** to hide titles, skip AniList tracking, or select a separate tracking account. Guest Mode always hides adult titles. The app also respects the signed-in account’s AniList visibility settings.

## Playback and library

- **Native player.** Seek with the progress bar or double taps, use brightness and volume gestures, adjust subtitle size and background, and cast with Chromecast. The player remembers playback speed and offers an optional persistent progress bar.
- **AniList or Guest Mode.** Switch between saved accounts, manage lists, and set the percentage at which episodes count as watched. AniList updates can queue offline and sync when you reconnect. Guest Mode saves watchlist activity on the device.
- **Browse and pick something to watch.** Search the catalog, filter for dubs, sort My Lists, or use **Random** and **Random Dub**.
- **Offline viewing.** Supported downloads are saved in the app’s private storage. Download availability depends on the source and format.

## Installation

Open the APK on your Android device. Android may ask you to allow installation from the browser or file manager you used to open it. The release includes [a SHA-256 checksum](https://github.com/JmDemisana/maru-android-projects/releases/download/nanami-anime-stable/SHA256SUMS.txt).

## Updates and diagnostics

In Settings, go to **Private Offline Vault & About**. **Check for Updates** finds official Nanami Anime releases and hands a verified APK to Android's installer. **Copy Log Dump to Clipboard** checks the app's online services and tries to render the first frame of sample episodes. The report stays on your device until you share it.

- Stable releases have their own `nanami-anime-vX.Y.Z` tags and publish a signed APK, checksum, and `update.json`.
- The `nanami-anime-stable` download link tracks the latest stable APK.
- Preview releases have their own `nanami-anime-preview-vX.Y.Z.N` tags. The `nanami-anime-latest` prerelease continues to serve the rolling preview APK. The app updater follows stable tags.
