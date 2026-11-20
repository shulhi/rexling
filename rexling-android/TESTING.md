# Rexling testing APK

Share `dist/Rexling-<date>-build<code>-<commit>-arm64-release.apk`. It supports ARM64 Android devices running Android 9 or newer.

Open the APK on the device and allow installation from that source when prompted. This optimized, non-debuggable test release uses the existing local development signing identity.

Fresh installs start with no preset server. Enter an authorized Rex server URL and connect through Tailscale, or choose **Try preview** to explore offline. No personal connections, credentials or settings are bundled in the APK.

Long-press a tab to close it. Close a pane using its X in the pane picker or **Close pane** in the three-dot menu. Confirmation is required because closing ends the running program; closing a tab ends every program in that tab.

Opening a pane, switching tabs/sessions/servers or selecting an Activity entry automatically takes control once its snapshot and the phone dimensions are ready. The shared PTY resizes for the phone, so other clients see that grid too. The previous pane is released when you leave it; backgrounding the app releases control. The pointer button can explicitly release the current pane into viewing mode until you navigate again. Ownership lost to another client is not repeatedly reclaimed. Navigation itself does not explicitly open the soft keyboard.

The default keyboard accessory is one row. Esc/Ctrl/Alt/Shift/Tab are separate symbol buttons with gaps between them, followed by a shared arrow group and the keyboard toggle. Swipe the arrow group sideways to reveal clipped arrows, then tap to send. Arrow scrolling leaves the modifier buttons fixed. Tap Shift for the next key or hold it while tapping several keys. Scrolling sends no input. Reveal the chevron before Esc to expand F1–F12 and Home/End, paging, Insert and Delete keys.

The header's pointer button takes or releases terminal control. Its outlined state means viewing; its blue-filled state means control. Long-press it for the action label. Tap the eye at the far right of the bottom tab bar to open Activity. The pane button beside it offers Switch pane, New pane and New tab.

Visible `http://`, `https://` and `www.` URLs are underlined. Tap one to open Android's link handler without taking terminal control; long-press still selects text. Printed URLs can span rows that Ghostty marks as genuine soft wraps; fragments outside the available snapshot are not guessed. OSC 8 labeled web links use their stored destination and work across wrapped rows. Non-web destinations are not opened. Localhost links open as written on the phone; addresses are not rewritten.

While controlling a mouse-enabled terminal application (such as Neovim or Lazygit), tap its content to send a left-button press/release pair at that cell. Taps do not open the soft keyboard in mouse mode; use the keyboard button. Viewing-mode taps keep their existing take-control behavior. Long-press selects text, web links open normally, and drags keep scrolling/switching panes. Taps started before a navigation, reconnect, grid resize, mouse-mode change or loss of control are canceled. The application must enable terminal mouse reporting.

The key row keeps its contrast while pane navigation temporarily disables input. The header control circle is 32dp with a 16dp pointer.

Long-press a web link for Open link, Copy link address and Share link (some actions may be in the floating toolbar overflow). Copy and Select all still operate on terminal text. Link actions use the frozen destination even if live output changes. Share opens Android's chooser; you select the recipient/app.

While controlling the terminal, quickly tap Ctrl to toggle it for the next key. Hold Ctrl to show the nine label-only shortcuts (^C, ^D, ^Z, ^R, ^L, ^A, ^E, ^W, ^J), slide onto one and release to send it once. Releasing outside cancels. The popup shows no shortcut descriptions.

Hold an arrow, Delete or Backspace to repeat. Sliding the arrow strip scrolls it without typing. Releasing, changing panes, losing control or leaving the app cancels repeat.

Activity groups reported program states by connected server. Filter Needs you, Working or Finished; tap an entry to open and take control of its exact session and pane. It refreshes while open and stops scanning when dismissed or backgrounded. Programs must report Rex status; missing reports are not inferred from output.

The hold-Ctrl popup uses a three-by-three grid. ^J sends line feed (byte 0x0A).

Open **⋮ → Appearance** for System/Dark/Light mode and separate light/dark preset choices. Each list starts with **Rexling Default**, which keeps the original blue app and terminal colors. The 41 presets from the installed Rex catalog (27 dark, 14 light) recolor panels, controls, menus and terminal together. Choices are saved locally on the phone; changing the Mac's selected theme does not change these selections. Taking control applies the chosen terminal palette to the shared pane. Programs using explicit RGB colors retain those colors.

Open **⋮ → Font size** for a preview, slider and −/+ controls from 8 to 20 sp in 1 sp steps. The chosen size is saved across restarts.


Activity/reconnect follow-up (2026-11-02): run the standard JVM/native/Clippy/lint checks plus Android `ConnectionChromeTest`, `ActivitySheetTest`, and `TerminalControlButtonTest`. The live Activity fixture verifies closed-sheet attention from another session, local acknowledgment, changed-report re-alerting, live titles, exact-pane read-only navigation and unchanged PTY size. Run it on each explicitly owned server with `REX_TEST_SERVER=<server> ... cargo test --locked --manifest-path native/Cargo.toml --workspace live_activity_ -- --ignored`. The existing lifecycle fixture covers pause/resume and typed input restoration. `NetworkRouteTrackerTest` covers offline/recovery, route/VPN transport changes, duplicate callbacks and validation-only changes. The host `activity_metadata_publish_benchmark` measures changed-title publication with 256 cached reports; it does not measure S22+ UI frame times.


Circular navigation checks (from the monorepo root):

```sh
ANDROID_SERIAL=emulator-5554 ./rexling-android/scripts/gradle.sh :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.shulhi.rex.SessionHeaderLoopTest,dev.shulhi.rex.PaneChromeTest
```

Use a dedicated emulator. The nine checks cover both wrap directions, two-item
rings without duplicate navigation, catalog changes, single/empty lists, correct
pane indicator identity during handoff, and cancellation at a wrap boundary.
The header test also writes cache/loop-header.png for visual review of the stack.


Opt-in host allocation check (from the monorepo root):

```sh
REXLINK_RENDER_BENCHMARK=1 REXLINK_RENDER_BENCHMARK_OUTPUT=/tmp/rexlink-render-benchmark.json \
  ./rexling-android/scripts/gradle.sh :app:testDebugUnitTest \
  --tests dev.shulhi.rex.RenderWorkBenchmarkTest --rerun-tasks --no-daemon
```

This uses ThreadMXBean on the build JDK and includes test-loop overhead. It is
an allocation/work check, not a phone latency or frame-pacing benchmark. Normal
unit runs skip it. Device fade/gesture/link/cache regressions include
PaneCompositingTest, PaneChromeTest, TerminalLinksTest and PaneFramesTest.

### Kitty video responsiveness (Android)

Use `-PrenderBench=true` and install the debug and androidTest APKs **only** as
`dev.shulhi.rex.renderbench` and its test package. `KittyVideoPerfTest#changing720p`
reports per-stage p50/p95 milliseconds for 60 changing 1280×720 opaque assets
after 12 warmups. It uses a software Canvas, not a display FPS counter.

For live navigation, use an isolated Rex server and an Android-reachable endpoint.
Create two tabs in one scratch session: one running a finite `mpv --no-config
--no-audio --vo=kitty --vo-kitty-width=1280 --vo-kitty-height=720 --frames=1100
av://lavfi:testsrc2=size=1280x720:rate=30`, another printing `VIDEO_SWITCH_OK` and
remaining open. Run the following while mpv is still playing. The test draws eight
distinct live image assets on the main thread, requests a block switch there,
and requires the target tab's terminal text within two seconds. This measures
command-to-decoded-content latency, not a tap-to-display or network FPS benchmark.

```sh
adb shell am instrument -w -r -e class 'dev.shulhi.rex.KittyVideoPerfTest#switchAwayDuringLiveVideo' \
  -e video_server "$endpoint" -e video_session "$session" -e video_block "$video" -e text_block "$text" \
  dev.shulhi.rex.renderbench.test/androidx.test.runner.AndroidJUnitRunner
```
