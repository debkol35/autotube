# AutoTube

YouTube (and, optionally, Netflix and Prime Video) on the Android Auto screen, only while the car is parked. It's an unofficial app, not made by, endorsed by or affiliated with Google, YouTube, Netflix, Amazon or Android Auto.

**Install:** download the APK from [Releases](../../releases). Install it on the phone, open it, and tap **"Set up on this phone"**. On phones with the older installer, the app sends you to the free Shizuku app instead. A computer installer is also in the release zip.

- **Works with the original YouTube app and YouTube ReVanced.** The car screen plays YouTube's mobile website inside AutoTube. On the phone you pick videos in either app and tap Share → "Play in car (AutoTube)". If both are installed, the user chooses which one AutoTube opens.
- **Car-friendly controls:** a big button rail (Back, Home, Search, Play/Pause, Full screen, Apps) and its own big keyboard with YouTube search suggestions. Password fields are masked on the car screen.
- **Full screen:** shows only the video, using all of AutoTube's area on the car screen. Tap the screen for a small control bar.
- **Netflix and Prime Video (optional, experimental):** the user turns them on and signs in on the phone. They load in a desktop browser mode with Widevine DRM. Whether the picture shows on a given phone or car isn't guaranteed.
- **Fail-safe driving lock, on by default:** the picture shows only while the phone's GPS confirms the car is parked. It stays covered while moving, without location permission, with GPS off, or before the first GPS reading. The sound keeps playing. Passengers can switch it off in the phone app, after a warning.
- **Optional ad skipping:** works like ReVanced and can be switched off in Settings.
- Resumes the last video, and links sent from the phone wait until the car screen opens.
- **Car log:** start/stop, size changes and crashes go to a small log. Users can send it from the phone app's "Share car log" button.

Users: see [`installer/README.txt`](installer/README.txt). It ships inside the release zip.

## How it gets onto the car screen

- **Unofficial SDK:** it uses the unofficial Android Auto projection SDK (`CarActivityService` / `CarActivity`, `app/libs/aauto.aar`, the same build Fermata Auto uses).
- **The install check:** Android Auto 17.7+ only lists such apps when the install record says `installer = com.android.vending` and was made by `com.google.android.packageinstaller`.
- **Who may ask for it:** the phone's installer must be asked to record Play Store (`am start ... INSTALL_PACKAGE` with `EXTRA_INSTALLER_PACKAGE_NAME`). Which callers it accepts depends on its version:
  - **Classic installer (Android 14–16):** accepts the request only from callers with install rights, meaning `adb shell` or [Shizuku](https://shizuku.rikka.app/).
  - **Newer installer** (it logs "Use PIA V2"; seen on Android 17): rejects exactly those callers, logging `The given installer package name … is invalid`. It accepts the request from an ordinary app asking for itself. See `maybeRemoveInvalidInstallerPackageName` in AOSP's `InstallRepository.kt`.
  - **What AutoTube does:** "Set up on this phone" makes the request itself. The Shizuku route (`ShizukuSetup` in `Installer.kt`) and the computer scripts (`installer/`) send it as shell. When they see the rejection in the log, they hand over to the app.

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
