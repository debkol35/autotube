AutoTube - YouTube on the Android Auto screen (when parked)
===========================================================

Unofficial app. Not made by, endorsed by or affiliated with Google, YouTube or Android Auto.

What it does
  Shows YouTube full screen on your car's Android Auto display, with big buttons on the left
  (Back, Home, Search, Play/Pause, Fit video) and its own big keyboard with search suggestions.
  Works whether your phone has the original YouTube app or YouTube ReVanced (or neither): the car
  screen plays YouTube's mobile website inside AutoTube. On the phone you pick videos in either
  app and tap Share > "Play in car (AutoTube)".

Safety
  The picture is shown only while the phone's GPS says the car is parked. While driving it is
  covered and the sound keeps playing. Without location permission or GPS, the picture stays
  covered. Never watch video while driving, and follow your local laws.

You need
  - An Android phone with Android 10 or newer and Android Auto. (So far tested: installing and
    appearing in Android Auto's launcher on a Samsung Galaxy with Android 17 / Android Auto 18.0.)
  - A computer (Windows, macOS or Linux) and a USB data cable, once per install or update.
  - USB debugging on the phone: Settings > About phone > tap "Build number" 7 times, then
    Settings > Developer options > USB debugging.

Install (and every update)
  Why a computer: Android Auto only lists full-screen apps that its Play Store check accepts.
  The installer makes the phone's own installer record AutoTube that way. Android 14+ only allows
  this from a computer (USB debugging). Nothing is sent anywhere except Google's adb download.

  1. Unzip this folder on the computer. Plug in the phone and unlock it.
  2. Windows: double-click Install-AutoTube-Windows.bat
     macOS / Linux: open a terminal in this folder and run:  bash install-autotube.sh
     - If adb (Google's Android platform-tools) isn't on the computer, it offers to download it
       from dl.google.com (about 8 MB).
     - On the phone, tap "Allow" on "Allow USB debugging?" if asked.
  3. When the phone shows the installer, tap "Update". If Play Protect asks: "More details" >
     "Install without scanning" (or "Install anyway").
  4. The installer must end with "Done: Android Auto will accept AutoTube."

Set up on the phone (once)
  1. Open AutoTube, read the short notice, tap "I understand".
  2. "Allow location" > choose "Allow all the time" (in the car the phone counts AutoTube as in
     the background; without "all the time" the picture stays covered).
  3. Settings > Connected devices > Android Auto > Customize launcher > turn on AutoTube.
     (AutoTube has a button "Open Android Auto settings"; the menu path differs a little by phone.)
  4. In the car: open the Android Auto app grid and tap AutoTube.

Using it
  - Search: the magnifier on the left (or YouTube's own search box) opens a big keyboard.
  - Fit video: the corner-arrows button shows only the video, as big as the screen. Back undoes it.
  - From the phone: in YouTube or YouTube ReVanced tap Share > "Play in car (AutoTube)", or use
    AutoTube's "Send to the car" box. If both apps are installed, AutoTube's
    "Use a different app" button chooses which one its "Pick a video" button opens.
  - Ads: video ads are muted, fast-forwarded and skipped and ad cards are hidden. Switch this
    off in AutoTube > Settings. (Skipping ads may be against YouTube's terms; your choice.)
  - It remembers the last video and resumes there next time.

If AutoTube is missing from Android Auto
  - Open AutoTube on the phone: step 1 must show a green "Ready". If not, run the installer again.
  - Any update installed another way (tapping the APK, a file manager) makes Android Auto hide
    AutoTube again: always update with the installer.
  - Force-stop Android Auto (Settings > Apps > Android Auto > Force stop), reconnect, check
    Customize launcher again.
  - Phones whose own installer isn't Google's (the installer prints this) may not be accepted.
  - Older Android Auto (before 17.7) can also show it via Android Auto's developer settings >
    "Unknown sources".

Privacy
  No account, no analytics. Location stays on the phone (only used for the parked check).
  YouTube and its search suggestions are loaded from Google, as in any browser.

No warranty. Use at your own risk.
