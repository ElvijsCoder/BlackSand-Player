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
Each build is signed with a different throwaway key, so Android won't install
it over the previous one. Uninstall the old version first, then install the new one.
(Later, once the app stores playlists or stats, we'll add a fixed key so updates
keep your data.)

## 4. Test on the 3a
- Allow music access → songs list appears
- Tap a song → it plays, notification + lock screen controls show
- Headphone / Bluetooth buttons work; unplugging headphones pauses
- Swipe the app away while playing → music keeps going
