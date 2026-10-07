# Black Sand: build with GitHub Actions

Every push to `main` builds an APK and publishes it as a GitHub release you can
open on the phone. No keys, no secrets, no Android Studio.

## 1. Create the repo
- New private GitHub repo, e.g. `black-sand`
- Push this folder with git or GitHub Desktop. Web drag-and-drop can skip the
  hidden `.github` folder, so check it's there after pushing.

## 2. Build
Actions tab → "Build APK" → Run workflow (or just push to `main`).
When it's green: Releases → newest "Build N" → tap the `.apk` on the phone.
First install: allow your browser to install unknown apps.

## 3. Updating
Builds are signed with a fixed key kept in the repo (`app/blacksand-debug.keystore`),
so each new APK installs over the previous one and keeps your data.
The very first build with this key needs one last uninstall of the old version.

## 4. Test on the 3a
- Allow music access → songs list appears
- Tap a song → it plays, notification + lock screen controls show
- Headphone / Bluetooth buttons work; unplugging headphones pauses
- Swipe the app away while playing → music keeps going
