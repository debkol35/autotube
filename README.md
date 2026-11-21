# AutoTube

YouTube on the Android Auto screen, only while the car is parked. It's an unofficial app, not made by, endorsed by or affiliated with Google, YouTube or Android Auto.

- **Works with the original YouTube app and YouTube ReVanced.** The car screen plays YouTube's mobile website inside AutoTube. On the phone you pick videos in either app and tap Share → "Play in car (AutoTube)". If both are installed, the user chooses which one AutoTube opens.
- **Car-friendly controls:** a big button rail (Back, Home, Search, Play/Pause, Fit video), its own big keyboard with YouTube search suggestions, and a fit mode that shows only the video.
- **Fail-safe driving lock, always on:** the picture shows only while the phone's GPS confirms the car is parked. It stays covered while moving, without location permission, with GPS off, or before the first GPS reading. The sound keeps playing.
- **Optional ad skipping:** works like ReVanced and can be switched off in Settings.
- Resumes the last video, and links sent from the phone wait until the car screen opens.

Users: see [`installer/README.txt`](installer/README.txt). It ships inside the release zip.

## How it gets onto the car screen

- **Unofficial SDK:** it uses the unofficial Android Auto projection SDK (`CarActivityService` / `CarActivity`, `app/libs/aauto.aar`, the same build Fermata Auto uses).
- **The install check:** Android Auto 17.7+ only lists such apps when the install record says `installer = com.android.vending` and was made by `com.google.android.packageinstaller`.
- **Why a computer is needed:** Android 14+ ignores that request from an app. The installer scripts send it from `adb shell` instead, which the system installer accepts. So every install and update goes through the script.

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
