# Rexling Android

Native Kotlin/Compose UI, Canvas terminal rendering and Android input/lifecycle
integration for the unofficial Rexling client. Open this directory in Android
Studio. The Rust workspace is next door in `../rexling-core/`; Gradle builds it
automatically. No separate old rex-probe or rex-android checkout is needed.

See [root README](../README.md) and [BUILDING.md](../BUILDING.md) for complete
toolchain requirements, reproducible commands and licensing.

From this directory:

```sh
./scripts/gradle.sh :app:assembleRelease
./scripts/gradle.sh :app:testDebugUnitTest :app:lintRelease
python3 scripts/test_jni.py
python3 scripts/verify_apk.py --apk app/build/outputs/apk/release/app-release.apk --require-release
```

The release APK is `app/build/outputs/apk/release/app-release.apk` (arm64, API 28+).
Run `./scripts/gradle.sh :app:shareRelease` for a copy in `dist/` named
`Rexling-<date>-build<code>-<commit>-arm64-release.apk`, with a SHA-256 checksum.
Its identity matches **⋮ → About Rexling**; local edits add `-modified`.
The package remains `dev.shulhi.rex`, preserving existing installs and settings.
Local releases retain the development signing identity; configure production
signing separately before publishing. SDK paths and caches remain ignored.

## Usage

- Add an authenticated HTTPS or SSH endpoint under **⋮ → Servers**. Rexling
  can keep several servers connected at once. [Connection setup](../docs/connections.md)
- Tap the header title to browse sessions or hold it to return to the last
  session. In the full header, swipe the program icon up or down to cycle
  through sessions. In the session list, open a row’s **⋮ → Rename session** to
  rename it on the server. Under **⋮ → Preferences → Display**, set
  **Compact top bar** to **Always** or **With keyboard**.
- Swipe the terminal to switch panes, wrapping at the ends. Choose bottom or
  sidebar tabs under **⋮ → Preferences → Tabs**. Use **+** in the sidebar or on
  medium and wide bottom layouts to create a tab. Closing a tab or pane
  requires confirmation. On Rex servers with Undo Close (API revision 23 or newer),
  tap **Undo** shortly after closing to restore the terminal and its running programs.
- Choose **Multiple panes** under **⋮ → Preferences → Panes** to view up to four
  tiled panes at once on supported full-screen foldables and tablets.
- Tap the floating pane dock to restore a pane or fan out a group. Tap the
  active pane’s icon or entry to minimize it. Hold and drag the dock to move
  it. A blue dot marks unread activity on the dock and the corresponding
  entry in the fan.
- A newly selected ready pane is automatically claimed once and resized to the
  phone. Switching away/backgrounding releases it. Manual Take/Release live in
  **⋮**. Another owner's dimensions appear in a contextual takeover notice.
- The Activity eye opens reports grouped by server. Unseen attention reports
  produce a dot; **Mark seen** acknowledges a report locally. Activity does not
  take ownership merely to scan reports.
- The terminal streams snapshot plus incremental VT, with real Ghostty
  scrollback, copy selection, OSC 8/printed links, Nerd Fonts and mouse-aware
  full-screen scrolling. Shared PTY dimensions affect other attached clients.
- Typing uses Rex’s ordered key-event stream with up to eight requests in flight.
  Delayed acknowledgments no longer stop terminal output or rendering. Adjacent
  queued plain text can be combined without delaying the first keystroke.
- The one-row keyboard dock offers modifiers, a hold-and-slide arrow joystick, a dedicated Enter key,
  hold/repeat, Ctrl shortcuts and an expandable panel with F1–F12.
- **⋮ → Preferences → Appearance** saves independent light/dark presets, including the
  Rexling defaults. Font size changes in 1-sp steps. Theme requests are sent
  while owning the pane; programs may override their own colorscheme.
- Reconnect notices expose input-paused state and Retry now. Network changes
  trigger retry; uncertain input is never automatically resent.

Protocol findings and historical check results remain in [PROTOCOL.md](PROTOCOL.md),
[TESTING.md](TESTING.md), and [VALIDATION.md](VALIDATION.md). Older entries use
the paths from before consolidation; current commands are in the root build guide.

Project code: [Apache 2.0](../LICENSE). Bundled third-party notices are retained in
`app/src/main/assets/licenses/` and listed in [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md).

Multi-pane program icons fan out sideways with a subtle counterclockwise front
tile and opposing back tilts. The active program stays in front; single-pane
icons remain upright. Both header and tab icons use the same treatment. The
header reserves the same width for one, two or three tiles and centers the
visible stack, keeping the title and directory aligned when pane counts change.

## File and image uploads

Use **⋮ → Attach file** to upload a file or **⋮ → Image** to pick a photo. You
can also paste an image from the clipboard or a supported keyboard. Uploads use
the connected Rex server and require Rex API revision 11 or newer. Rex inserts
the uploaded path into the pane where you started the upload. [Upload validation](../BUILDING.md#native-upload-checks)
