<div align="center">

```
╭──────────────────────────────────────────────╮
│ ●                                          ● │
│   ╭──────────────────────────────────────╮   │
│   │  A   BLACK SAND ───────────────────  │   │
│   │      your music ─────────────────    │   │
│   │    ╭────────────────────────────╮    │   │
│   │    │   (@)    ░░░░░░░░░░   (@)  │    │   │
│   │    ╰────────────────────────────╯    │   │
│   │ ▓▓ TYPE I · NORMAL ▓▓▓▓▓▓▓▓▓▓▓ 01/12 │   │
│   ╰──────────────────────────────────────╯   │
│ ●       /   o    [] [__] []    o   \       ● │
╰──────────────────────────────────────────────╯
```

# B L A C K &nbsp; S A N D

**A cassette deck that lives in your phone.**

`STEREO CASSETTE PLAYER · AUTO STOP · TYPE I`

**Black Sand × Claude** · Designed by Elvijs S.

</div>

---

## ▶ What is this

Black Sand is a music player for Android that pretends really hard to be a tape deck. You get your local music library, but it plays on a cassette: the reels turn, the PLAY key stays pressed in while music plays, a red light glows, and the counter rolls over like little number wheels. Holding FF or REW winds the tape, with motor whirr and all.

It's a personal side project, built for (and tested on) a **Nothing Phone (3a)**. It's dark, minimal and dotty to sit nicely next to Nothing OS, with a bit of 80s hi-fi warmth mixed in.

No accounts, no streaming, no ads. Just the music on your phone, played like it's 1986.

---

## ◉ Inspiration

The whole thing started with a little moodboard:

- **A black sand beach in black and white.** Waves breaking white on a dark shore. That's the palette: near-black, white as the only light, film grain, and a progress bar that's literally a tide line with foam on the edge.
- **A small white tape recorder render** with chunky keys and one orange record button. That's where the mechanical keys, the reel window and the "one colour accent" idea came from (the accent ended up red).
- **Nothing OS.** Dot-matrix type, monochrome icons and the general calm. The dot fonts, the dot-matrix covers and the dotty stats chart all come from here.
- **VoidVinyl** on Nothing Playground, a spinning vinyl widget. It proved a widget could actually spin, so ours got turning reels.
- **Real cassette decks and portable players.** Keys that latch, a maker's nameplate on the front, C60 tapes, mixtapes with Side A and Side B, and the satisfying clunk of PLAY.

(A late-90s console boot screen made the first moodboard too. It didn't survive the edit, but it was there in spirit.)

---

## ⏏ What's on the tape

### The deck
- **The cassette:** Black shell, screws, a hand-ruled paper label with a big **A**, the tape window, and the guide holes at the bottom.
- **Reels that turn:** They spin while playing and coast to a stop when you pause. Tape moves from the left reel to the right as the song plays, and the full reel turns slower, like real tape.
- **Deck keys:** Chunky 3D piano keys (REW · PLAY · FF) that sink in when pressed and spring back with a tiny bounce. PLAY stays down while music plays, and its red LED lights up.
- **Hold to wind:** Hold REW or FF and the tape winds fast with the reels whirring.
- **Cassette swaps:** Skip a song and the old cassette slides out while the next one slides in.
- **Rolling counter:** The timer is a mechanical tape counter, with digits that roll over one by one.
- **Tide-line seek bar:** White foam creeping along the black. Tap or drag to seek.
- **Nameplate:** A brushed-metal BLACK SAND badge across the top of the deck.
- **Dot-matrix covers:** Album art printed on the label as a halftone of little dots (or a greyscale photo, if you prefer).

### The library
- **Browse:** Songs, albums, artists and folders, plus search across titles, artists and albums.
- **The ⋯ menu** on every song: Play next, Add to queue, Add to playlist.
- **Queue:** See what's coming, jump around, reorder and remove.
- **Playlists:** Make them, rename them, or import your old `.m3u` files.
- **Stats:** Listening time this week with a dot-matrix bar chart, your most played songs and your top artists.

### Mixtapes
- **Pick a tape:** C46, C60 or C90. Songs fill **Side A**, then **Side B**, and if a song doesn't fit, it doesn't fit. Curate like it's a real tape.
- **Watch it flip:** When playback reaches Side B, the cassette flips over and the label letter changes to **B**.

### Sound
- **Tape mode:** Warm cassette sound with softer highs, a little hiss and the gentle wobble of a tape motor. Light or Worn.
- **Equalizer:** Mixing-desk faders, plus bass boost.
- **Even out volume:** Uses ReplayGain tags so loud and quiet songs sit at a similar level.
- **Fade between songs:** 2, 4 or 6 seconds. Albums stay gapless.
- **Deck sounds:** Key clicks, the PLAY latch and the motor whirr while winding.
- **Sleep timer:** 15, 30 or 60 minutes, or end of song, with a soft fade-out.

### The widget
- **Two sizes:** The same cassette on your home screen, in 4x2 or 2x2.
- **Live deck:** The reels spin, the PLAY key latches, the red light glows, the counter ticks and the label follows the tape side.
- **Works from closed:** The keys work even when the app is closed. PLAY picks up where you left off.

### Little things
- **Tape loading:** A short "loading tape" moment when the app opens.
- **Flip to pause:** Put the phone face down to pause, and flip it back to carry on.
- **Resume:** Remembers your queue and spot, even if Android closes the app.
- **Shortcuts:** Long-press the icon for *Shuffle all* and *Resume*.
- **Hidden folders:** Hide folders like voice notes or WhatsApp audio from the library.
- **Reduce motion:** If Android's "Remove animations" is on, everything calms down to simple fades and still reels.

---

## ⌕ Where to find things

| Looking for… | It's here |
|---|---|
| The cassette deck | Tap any song, or the mini player at the bottom |
| Winding | Hold **REW** or **FF** on the deck |
| Shuffle · Repeat · Sleep | The little switches under the song title on the deck |
| Queue | **QUEUE**, top-right on the deck |
| Play next / add to playlist | **⋯** on any song |
| Mixtapes | Library → **PLAYLISTS** → **+ MIXTAPE** |
| Stats | Library → **STATS** |
| EQ, tape mode, deck sounds, flip to pause, dot covers, folders | **SETTINGS** in the library header |
| Shortcuts | Long-press the app icon |
| The widget | Home screen → Widgets → **Black Sand cassette** |

---

## ⚙ Under the hood

- **Language and UI:** Kotlin + Jetpack Compose. All the visuals are drawn by hand, mostly on `Canvas`.
- **Playback:** Media3 / ExoPlayer, with a `MediaSessionService` behind it. That handles the notification, the lock screen and headphone buttons.
- **Tape mode:** A custom audio processor sitting in the playback pipeline.
- **Widget:** Classic `RemoteViews`. The spinning reels are a spinner-image trick, so they turn without draining the battery.
- **Storage:** Plain JSON files and SharedPreferences. No database, nothing leaves the phone.

```
app/src/main/java/com/blacksand/player/
├─ MainActivity.kt        app root, loading moment, shortcuts
├─ PlayerViewModel.kt     the brains between screens and the player
├─ playback/              PlaybackService (player, sleep, EQ, stats, widget feed), TapeProcessor
├─ data/                  library scan, playlists & mixtapes, stats, settings, dot-matrix art
├─ ui/                    the deck, library, settings, stats, keys, dialogs, deck sounds
└─ widget/                the home screen cassette
```

---

## ⬇ Getting it on your phone

No Android Studio needed. GitHub builds it.

1. **Push** to `main`. GitHub Actions builds the APK by itself.
2. **Download:** Open **Releases** on your phone and grab the newest `BlackSand-N.apk`.
3. **Install:** Allow your browser to install apps the first time.

Every build is signed with the same key (`app/blacksand-debug.keystore`), so new versions install right over the old one and keep your playlists, stats and settings.

---

## ⏱ Tuning knobs

Want it to feel a bit different? These are the dials:

| What | Where |
|---|---|
| Grain strength / coarseness | `GRAIN_ALPHA`, `GRAIN_CLUMP` in `ui/Theme.kt` |
| How deep the keys look | `height * 0.17f` in `ui/Controls.kt` |
| How long to hold before winding | `HOLD_MS` in `ui/Controls.kt` |
| Winding speed | `SCAN_STEP_MS` in `PlayerViewModel.kt` |
| Tape mode wobble and hiss | the wow / flutter / hiss numbers in `playback/TapeProcessor.kt` |

---

## ✦ Credits

- **Design & direction:** Elvijs S.
- **Built with:** Claude, one conversation, a lot of cassette talk.
- **Fonts:** [Doto](https://fonts.google.com/specimen/Doto), [Space Grotesk](https://fonts.google.com/specimen/Space+Grotesk) and [Space Mono](https://fonts.google.com/specimen/Space+Mono), all under the SIL Open Font License (see `OFL-fonts.txt`). They're stand-ins for Nothing's own dot and grotesk fonts.
- **Sounds:** The key clicks, PLAY latch and motor whirr were synthesized from scratch for this project.
- **Icon:** The reel hub, drawn as a vector.

Black Sand is a personal project and isn't affiliated with Nothing or any tape-deck maker. Their stuff was just very inspiring.

<div align="center">

<br>

`● REC` &nbsp; made for long walks and black sand beaches

**Black Sand × Claude** · Designed by Elvijs S.

</div>
