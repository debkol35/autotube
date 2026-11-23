AutoTube - YouTube (and optionally Prime Video) on the Android Auto screen, when parked
======================================================================================

Unofficial app. Not made by, endorsed by or affiliated with Google, YouTube, Amazon or Android Auto.

What it does
  Shows YouTube on your car's Android Auto display, with big buttons on the left (Back, Home,
  Search, Play/Pause, Full screen, Apps) and its own big keyboard with search suggestions.
  Works whether your phone has the original YouTube app or YouTube ReVanced (or neither): the car
  screen plays YouTube's mobile website inside AutoTube. On the phone you pick videos in either
  app and tap Share > "Play in car (AutoTube)". Prime Video can be switched on too.

Safety
  Driving lock (on by default): the picture is shown only while the phone's GPS says the car is
  parked. While driving it is covered and the sound keeps playing. Without location permission or
  GPS, the picture stays covered. Passengers can turn it off in AutoTube on the phone (step 3).
  Never watch video while driving, and follow your local laws.

You need
  - An Android phone with Android 10 or newer and Android Auto. (Tested so far on a Samsung
    Galaxy with Android 17 / Android Auto 18.0, in an aftermarket head unit.)
  - Usually nothing else. Some phones need the free Shizuku app (and Wi-Fi) or a computer.

Install - option A: phone only (no computer)
  Why the extra step: Android Auto only lists full-screen apps that its Play Store check
  accepts. AutoTube makes the phone's own installer record that, by reinstalling itself.

  1. Download AutoTube-vX.Y.apk on the phone and install it (allow your browser / file manager
     to install apps if asked).
  2. Open AutoTube, read the notice, tap "I understand".
  3. Step 1 in AutoTube: tap "Set up on this phone". The first time it asks you to allow AutoTube
     to install apps (only used for this): allow it, come back, tap the button again.
     Tap "Update" on the installer screen. If Play Protect asks: "More details" >
     "Install without scanning" (or "Install anyway").
  4. Open AutoTube again: step 1 must show a green "Ready". Done.
  If step 1 says the phone ignored the request (phones with the older installer accept it only
  from "shell"), use "Set up with Shizuku" in AutoTube instead. It walks you through:
     a. installing Shizuku from Play Store,
     b. starting Shizuku: open Shizuku > "Start via Wireless debugging" and follow its pairing
        guide (needs Wi-Fi; Developer options must be on: Settings > About phone > tap
        "Build number" 7 times),
     c. back in AutoTube, tap the button again, allow AutoTube in Shizuku's prompt, tap "Update".
  For every AutoTube update: install the new APK, then repeat step 3.

Install - option B: with a computer
  1. On the phone: Settings > About phone > tap "Build number" 7 times, then
     Settings > Developer options > USB debugging.
  2. Unzip this folder on the computer. Plug in the phone and unlock it.
  3. Windows: double-click Install-AutoTube-Windows.bat
     macOS / Linux: open a terminal in this folder and run:  bash install-autotube.sh
     - If adb (Google's Android platform-tools) isn't on the computer, it offers to download it
       from dl.google.com (about 8 MB).
     - On the phone, tap "Allow" on "Allow USB debugging?" if asked.
  4. When the phone shows the installer, tap "Update" (Play Protect: as above).
  5. The installer must end with "Done: Android Auto will accept AutoTube." (On phones whose
     installer only accepts the request from AutoTube itself, the installer notices and opens
     AutoTube to make it; you still just tap "Update".)
  Repeat for every AutoTube update.

Set up on the phone (once)
  1. "Allow location" > choose "Allow all the time" (in the car the phone counts AutoTube as in
     the background; without "all the time" the picture stays covered).
  2. Settings > Connected devices > Android Auto > Customize launcher > turn on AutoTube.
     (AutoTube has a button "Open Android Auto settings"; the menu path differs a little by phone.)
  3. In the car: open the Android Auto app grid and tap AutoTube.

Using it
  - Search: the magnifier on the left (or the site's own search box) opens a big keyboard.
  - Full screen: the corner-arrows button shows only the video, filling AutoTube's whole area.
    Tap the screen for a small bar (leave full screen, play/pause); Back also leaves it.
    (Android Auto's own taskbar and side cards stay: no app can cover those.)
  - From the phone: in YouTube or YouTube ReVanced tap Share > "Play in car (AutoTube)", or use
    AutoTube's "Send to the car" box. If both apps are installed, AutoTube's
    "Use a different app" button chooses which one its "Pick a video" button opens.
  - Ads: video ads are muted, fast-forwarded and skipped and ad cards are hidden. Switch this
    off in AutoTube > Settings. (Skipping ads may be against YouTube's terms; your choice.)
  - Prime Video (optional, experimental): turn it on in AutoTube on the phone and sign in there;
    the car screen then gets an Apps button. It protects its videos (DRM), so on some phones or
    cars it may refuse to play, play in lower quality or show a black picture.
  - Netflix is not offered: Netflix refuses to play in in-app browsers and blocks screen
    mirroring. If your car has an Android head unit, use the Netflix app on the head unit.
  - It remembers the last video and resumes there next time.

If something goes wrong in the car
  AutoTube keeps a small log of what Android Auto does with its screen (start, stop, size
  changes, crashes). On the phone: AutoTube > Settings > "Share car log", and send it with your
  problem report.

If AutoTube is missing from Android Auto
  - Open AutoTube on the phone: step 1 must show a green "Ready". If not, redo option A or B.
  - Any update installed without option A/B afterwards makes Android Auto hide AutoTube again.
  - Force-stop Android Auto (Settings > Apps > Android Auto > Force stop), reconnect, check
    Customize launcher again.
  - Phones whose own installer isn't Google's (the installer prints this) may not be accepted.
  - Older Android Auto (before 17.7) can also show it via Android Auto's developer settings >
    "Unknown sources".

Privacy
  No AutoTube account, no analytics. Location stays on the phone (only used for the parked
  check). The sites (YouTube, Prime Video) and YouTube's search suggestions are loaded
  directly from those services, as in any browser.

No warranty. Use at your own risk.
