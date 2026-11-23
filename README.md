# AutoTube

YouTube (and, optionally, Netflix and Prime Video) on the Android Auto screen, only while the car is parked. It's an unofficial app, not made by, endorsed by or affiliated with Google, YouTube, Netflix, Amazon or Android Auto.

**Install:** download the APK from [Releases](../../releases). Install it on the phone, open it, and tap **"Set up without a computer"**. That uses the free Shizuku app, so no computer is needed. A computer installer is also in the release zip.

- **Works with the original YouTube app and YouTube ReVanced.** The car screen plays YouTube's mobile website inside AutoTube. On the phone you pick videos in either app and tap Share → "Play in car (AutoTube)". If both are installed, the user chooses which one AutoTube opens.
- **Car-friendly controls:** a big button rail (Back, Home, Search, Play/Pause, Full screen, Apps) and its own big keyboard with YouTube search suggestions. Password fields are masked on the car screen.
- **Full screen:** shows only the video, using all of AutoTube's area on the car screen. Tap the screen for a small control bar.
- **Netflix and Prime Video (optional, experimental):** the user turns them on and signs in on the phone. They load in a desktop browser mode with Widevine DRM. Whether the picture shows on a given phone or car isn't guaranteed.
- **Fail-safe driving lock, always on:** the picture shows only while the phone's GPS confirms the car is parked. It stays covered while moving, without location permission, with GPS off, or before the first GPS reading. The sound keeps playing.
- **Optional ad skipping:** works like ReVanced and can be switched off in Settings.
- Resumes the last video, and links sent from the phone wait until the car screen opens.
- **Car log:** start/stop, size changes and crashes go to a small log. Users can send it from the phone app's "Share car log" button.

Users: see [`installer/README.txt`](installer/README.txt). It ships inside the release zip.

## How it gets onto the car screen

- **Unofficial SDK:** it uses the unofficial Android Auto projection SDK (`CarActivityService` / `CarActivity`, `app/libs/aauto.aar`, the same build Fermata Auto uses).
- **The install check:** Android Auto 17.7+ only lists such apps when the install record says `installer = com.android.vending` and was made by `com.google.android.packageinstaller`.
- **Why shell rights are needed:** Android 14+ ignores that request when an app makes it, but accepts it from the shell user. AutoTube runs the same one `am start ... INSTALL_PACKAGE` command in either of two ways:
  - **On the phone:** through [Shizuku](https://shizuku.rikka.app/), started with Wireless debugging (`ShizukuSetup` in `Installer.kt`).
  - **From a computer:** through `adb shell`, using the scripts in `installer/`.

  Every install and update needs one of the two.

## Build

```
./gradlew assembleRelease
```

Signing reads `keystore/keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`). That file and the keystore are git-ignored.

**Keep the key private and backed up.** Updates must be signed with the same key, or users have to uninstall first.

The build output goes to `~/.autotube-build` (see the root `build.gradle.kts`).

## Make a release

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`, then build.
2. Make a folder `AutoTube-vX.Y` containing:
   - the APK, renamed `AutoTube-vX.Y.apk`
   - everything in `installer/`
3. Zip that folder and attach the zip to the release.

## Notes before publishing

- **Not for Play Store:** Google doesn't accept third-party projection or video apps for Android Auto, so distribute the APK yourself (GitHub Releases, for example).
- **YouTube's terms:** skipping ads goes against them. The feature is a user setting and the app shows a notice on first run.
- **The SDK is not open source:** `aauto.aar` is Google's unreleased SDK as redistributed by other projects, and its license is unclear.
- **The name:** "AutoTube" and any use of "YouTube" can draw trademark complaints. Keep YouTube's logo out of the app icon and store listings, and consider a distinctive name.
