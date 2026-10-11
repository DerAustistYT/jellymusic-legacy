# JellyMusic Legacy

A Jellyfin music client for Android 4.0.3 and later (API 15+).

Features include Jellyfin Quick Connect sign-in, browsing music, background playback, notification controls, and offline storage.

## Screenshots

| Album library | Now Playing | App menu |
| --- | --- | --- |
| ![Album library](docs/screenshots/album-library.png) | ![Now Playing](docs/screenshots/now-playing.png) | ![App menu](docs/screenshots/app-menu.png) |

## Build

Open this folder in Android Studio with JDK 17 and Android SDK 35 installed. Let Gradle sync, then build the debug APK. Alternatively, use Gradle 8.9 with `gradle assembleDebug`.

Conscrypt 2.5.2 is downloaded from Maven Central for TLS support. Dependencies retain their respective licenses.

## Privacy

Enter your own Jellyfin server address in the app. No server credentials or signing keys are included in this repository.

The publication copy uses application ID `org.jellymusic.legacy`; it installs separately from earlier builds using a different application ID.

## AI assistance

OpenAI Codex and Anthropic Claude assisted with development and preparation of this project.
