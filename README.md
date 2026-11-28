# Gramaton TV

Gramaton TV is a native Fire TV and Android TV client for Gramaton. This source checkout is version 0.52. The app uses Gramaton's website for account sign-in and catalogue data and uses Media3 for native video playback.

The sections below include historical development and device-test notes.

## Run

The debug-signed Fire TV APK is available as `Gramaton-Fire-TV-v0.49.apk` in this repository.

For developers rebuilding: use JDK 17, Android compile SDK 36, build tools 35.0.0, and the included Gradle 8.11.1 wrapper. Build with `./gradlew :app:assembleDebug :app:lintDebug`, or open this directory in Android Studio. Add `-PfireTv` to use the wide Fire TV launcher artwork. Compile SDK 36 does not require Android 16 on the TV; minimum Android API is 26. Amazon lists the Ember Artline (2026) as Fire OS 8 / Android API 30.

Sign in directly inside the app. The native library opens automatically. Press Left from the first poster in a row to open the navigation drawer, then use Up/Down and Select to choose a destination. Press Right to return to the catalogue. Open **Search** from the drawer and enter a movie or TV title using the on-screen keyboard. Pick a suggestion to fill the field, then press **Search** to look up titles across the catalogue. The query stays above the results grid. Select it to edit the search again. Movies, TV, Watchlist, Favorites, and Search results use vertically scrolling grids that automatically load additional pages when available. Movies and TV offer **Sort** and **Genre** above the posters. Home has horizontal carousels with a **View all** tile wherever the website provides a full listing. **Account** opens the website for account access.

To type on your phone, scan the QR code on the Search screen while the phone and TV are on the same network. Enter a title and tap **Send to TV** to fill the TV field. Press **Search** on the TV to see results.

Select a poster to open its details, then choose **Play fullscreen** or **Resume fullscreen**. Back returns to the listing and saves playback position. Backgrounding the app releases playback and saves position. Select Play on return. Each installation saves resume positions by movie path and shares those positions across Gramaton accounts on that device.

## Updates from GitHub Releases

Gramaton checks for a new version when the app opens. If an update is available for your TV, **Updates** appears above **Account** in the navigation drawer. Select **Updates**, choose **Download**, then confirm the install in the TV's package installer. Fire OS may ask you to allow Gramaton to install updates from this source. An in-place update retains the local Watchlist and resume history.

### Publishing an update

1. Increase both `versionCode` and `versionName` in `app/build.gradle`. Build the release APK using `./gradlew -PfireTv :app:assembleRelease` for Fire TV or omit `-PfireTv` for Android TV.
2. Sign the APK with the maintained release certificate. To preserve upgrades from the original Gramaton debug-signed build, use the established signing lineage with `--rotation-min-sdk-version 28`. The maintainer keeps the signing keys and passwords outside Git. Android 26 and 27 use the original signer in the lineage-compatible APK.
3. Publish a non-prerelease GitHub Release with a tag matching the app version, such as `v0.50`. Attach the signed build as `Gramaton-Fire-TV.apk` or `Gramaton-Android-TV.apk` as appropriate. The updater checks the GitHub asset digest, package, version, and signing certificate before handing the APK to the installer.

## Subtitles during playback

Open **CC** and choose **Find English subtitles**. Gramaton searches in the background and checks the downloaded dialogue language and cue timing on the device before displaying a matching track without restarting the video. Matches depend on provider coverage and may vary by cut. Searches send title metadata or IMDb IDs and episode numbers to the public subtitle and metadata services.

## Implemented in source, awaiting device verification

- A single native Play action opens a full-window Media3 PlayerView with system bars hidden and playback requested immediately. Original aspect ratio is preserved; letterboxing is expected.
- Reads only alternative-player links actually provided by the signed-in movie page. Recognizes numeric `720p`, `1080p`, and higher filenames; sorts descending. No 4K source was observed during inspection.
- Signed source URLs stay in memory. Passwords are entered in the website; this app has no password field or native credential store. WebView retains its ordinary session data. Native playback uses the website user agent and movie referrer; cookies are not exported to the native player. If cookies or other headers are required, playback may fail and that integration needs further investigation.
- Saves movie position every two seconds, on Back, and on backgrounding. Completed movies clear their position.
- After 12 seconds of buffering, tries the next listed lower-resolution MP4 at the current position. Decoder/network errors also try a lower source. HTTP rejection returns to the movie page for fresh links. No signed URL is logged or packaged.
- Media3 remote controls support play/pause and seeking. Native browsing supplies poster rows, visible focus, explicit D-pad navigation, movie details, full service-backed movie search, and previous/next page actions. Search invokes the website's own search control; it does not guess URLs or retain session tokens. The v0.3 Home screen preserves Gramaton's Featured, Watchlist, Popular This Week, Recently Added, Recent Episodes, Top-Grossing Movies and Favorites rows, including repeated titles in different sections. Home uses a single horizontal row per website section, landscape featured artwork, and native View all actions. TV series can open native details with an episode picker; resume is keyed by series and selected episode. Only sign-in/account screens remain interactive WebViews. Catalogue and detail pages are read in a background WebView.

Separate MP4 sources are not an adaptive manifest. Fallback may briefly interrupt playback and cannot guarantee synchronized encodes. There is no automatic upward switching, bandwidth prediction, codec capability preflight, or refresh/retry for an expired URL during playback. Starting a new session attempts the highest listed source again. HLS/DASH, episodes, subtitles, Cross-device resume is outside this prototype. Search shows movie and TV results offered by Gramaton. Episode metadata and source discovery have been checked against a live series page; end-to-end TV-episode playback still needs device verification.

## Evidence and acceptance gate

On October 22, 2026, authenticated browser inspection of one movie found a JW Player main player and Video.js/native alternatives listing 720p and 1080p MP4s with temporary access parameters. The 1080p-labelled alternative visibly played video, its timeline advanced, and clicking Fullscreen changed the control to Non-Fullscreen. This establishes browser playback only: actual decoded dimensions, automatic fullscreen, Android authentication, native rendering, quality fallback and resume have not been verified.

Before building a native catalogue interface, verify on the target TV:

1. Sign in; open a movie; press native Play once. Confirm video appears fullscreen and audio plays without a second confirmation.
2. Check Media3's actual decoded dimensions/selected format on-device for 1080p, plus a higher resolution only if offered and supported.
3. Seek several minutes; press Back; Play again. Confirm resume within a few seconds. Repeat after backgrounding and restarting the app.
4. Restrict network throughput; verify a lower listed source resumes at the same scene after sustained buffering.
5. Try an expired link, another server, an unsupported format, and an unavailable movie. Confirm errors return to browsing without endless retries or exposed tokens.
6. Verify D-pad navigation, media keys, Back, and absence of overlapping WebView audio.

Local build tools were downloaded from official sources after the user accepted the Android SDK license. JDK and Android platform/build-tool archives were checked against their publisher-provided checksums. The debug APK compiled successfully and passed Android lint with zero errors; remaining warnings concern prototype localization, WebView JavaScript, fixed TV orientation, backward-compatible attributes, backup configuration on newer Android, and newer build/target versions. Its APK signature and launcher metadata were verified. On-device checks on the Ember Artline verified native poster rendering, sign-in preservation during update, service-backed search for `star`, Right moving between posters, Down scrolling to the next row while preserving the column, and Select opening native movie details with an enabled Play action. A full native playback/resume test is still pending; successful compilation does not prove those playback requirements.

References: [Media3 setup](https://developer.android.com/media/media3/exoplayer/hello-world), [track selection](https://developer.android.com/media/media3/exoplayer/track-selection).

## Loading and branding in v0.3

The catalogue and detail loading state now shows a central animated blue spinner, stage text and elapsed seconds. After 15 seconds it explains that the service is taking longer and offers a remote-focusable Try again action. Errors replace the spinner with a retry message. Progress stages reflect WebView document progress without presenting a fabricated completion percentage. The launcher icon, Fire TV banner and in-app brand mark use a white G on blue; editable vector assets are included. On-device inspection verified the new landscape Featured row followed by Watchlist and an animated loading panel during a Home refresh. Homepage extraction preserves all seven source sections, including recent TV episodes, and duplicate titles across categories.

## v0.4 native TV redesign

A compact navigation bar adds a dedicated TV section. An artwork-backed title panel replaces the empty title block. Cards use a brief focus scale/elevation animation and keep their white focus outline. Page snapshots preserve selection and scroll position through list updates and details. Up to eight catalogue snapshots are kept in memory for immediate reuse; a small activity indicator accompanies background refresh. Artwork loads for visible and adjacent cards through a deduplicated, bounded shared cache rather than queuing every poster. Season-first episode selection and a cleaner details action row retain existing playback behavior. Homepage ordering, full-service search, loading/retry feedback, fullscreen playback and saved position remain in source. On-device v0.4 checks verified native homepage rendering, movie details, preserved selection on Back, and service-backed search for `star`. Episode playback and full picture/sound/resume acceptance remain pending. Search can briefly show previous catalogue content while the service responds. The hero panel was enlarged to accommodate long titles.


## v0.5 playback and selection fixes

Fullscreen PlayerView, its loading shutter, root and window use black backgrounds. Selected posters have a white title panel, bold dark title with an arrow, a thick blue outline and the existing focus scale animation. Resume checkpoints remain local to this TV, keyed by movie path or series/season/episode, and survive updates and app restarts. They save every two seconds, on pause or seek, and when leaving playback; reaching the end clears the checkpoint. The details button now displays the saved time and refreshes immediately after playback exits.

Device verification: the pre-existing Spider-Man checkpoint of 1:20:53 survived installation. Selecting Resume opened native fullscreen with its timeline at 1:20:55, and the updated 4,855,152 ms checkpoint persisted after pausing. Captured player background pixels were black; SurfaceView video itself was absent from the automated screenshot, so this capture does not verify picture or sound. The selected-poster white panel/blue border was visually checked. Build, lint and APK signature checks passed; no AndroidRuntime crash appeared during these tests. Checkpoints are local storage, not Gramaton account sync; clearing app data or uninstalling removes them.


## v0.6 logo

The launcher icon, Fire TV banner and in-app logo use the blue cursive g published by Gramaton at https://gramaton.io/android-chrome-512x512.png, placed on a light grey (#DADCE0) background. The source artwork is preserved in drawable-nodpi/gramaton_mark.png; native layer-list resources provide the grey background and padding. Build, lint and APK signature verification passed.


## v0.7 homepage scrolling

The hero artwork/title panel is hidden when browsing Home rows below Featured, giving the catalogue a clean opaque background. Scrolling content is clipped to its viewport so posters cannot bleed over the fixed header. The title panel still appears for Featured and movie details. Build, lint, signature verification and update installation passed. On-TV scrolling to Favorites confirmed lower rows display without the previous title artwork/text behind them.


## v0.8 simple loading

Loading displays only the spinner and the name of the section or title being loaded. The extra status messages, elapsed timer and delayed waiting/retry text were removed. The hero is hidden during loading. Actual errors retain their explanation and retry control. Build, lint, signature verification and TV installation passed.


## v0.9 launcher icon compatibility

Replaced layered XML launcher artwork with Android-rendered PNG assets: a 512×512 square app icon and 320×180 Fire TV banner, both with the blue cursive g on grey. Added the roundIcon reference. The artwork is unchanged; this avoids dependence on launcher handling of layered drawable resources. The temporary asset exporter was removed before the final build. Build, lint, signature and APK asset checks passed; the update installed successfully on the TV.


## v0.10 standard Fire TV launcher investigation

The regular Amazon home screen was directly verified to show a generic placeholder for Gramaton despite the installed PNG assets. Added explicit activity icon/banner metadata and a density-qualified mipmap icon. Build, lint, signature and update installation passed. The placeholder persisted after this update and a TV restart. Amazon protects its launcher against force-stop. A proposed package removal/reinstall using pm uninstall -k to preserve app data was blocked by automatic approval review because uninstall can lose data and requires explicit user authorization. That removal did not run. The regular-home-screen icon remains unresolved; previous icon packaging changes do not establish a verified Amazon launcher fix.


### Standard home icon resolved

After the user explicitly approved a keep-data reinstall, pm uninstall -k followed by reinstalling the same signed APK succeeded. The regular Amazon home screen now visibly displays the blue cursive g in Gramaton’s tile (between Disney+ and Fire TV Channels). The saved movie checkpoint of 4,855,152 ms was verified unchanged after reinstall. Screenshot: ../tv-home-icon-fixed.png. The earlier placeholder persisted through updates and reboot; the approved keep-data reinstall refreshed it successfully.


## v0.11 selection ring clipping

Poster rows now disable clipping to their inner padding so the enlarged focused card can draw its full blue outline and title panel into the existing gutter. The outer scrolling viewport remains clipped to keep posters below the header. Offline assembleDebug and lintDebug passed; APK signature verified. Installed on the Fire TV Stick 4K Max at the user’s request. Screenshots verified a complete ring on the first Popular This Week tile and a later Recently Added tile after navigation, including the bottom title panel and rounded corners.


## v0.12 free English subtitles

Movie details offer ordinary Play/Resume and Play with subtitles. The latter searches subtitles.website’s public guest API by title, filters to English SRT with matching title and compatible release year (and season/episode for series), downloads the highest-download match, converts UTF-16 BOM text to UTF-8, validates SRT timestamps, and caches it by playback key. No account, API key, Gramaton cookies or media URLs are sent to this provider. Daily guest quotas and provider coverage apply; missing matches and HTTP errors offer Retry, Cancel, or Play without subtitles. Subtitle settings lets users select another available release. Different video cuts can need different subtitle timing; automatic synchronization is not implemented.

The local SRT is attached through Media3 SubtitleConfiguration and DefaultDataSource (so file URIs work). Player CC offers None and English without rebuilding the media item. Quality fallback preserves the current subtitle enabled state. Normal Play starts with text tracks disabled. Subtitle lookup/download runs off the UI thread, bounded by timeouts and 2 MiB response limits; stale callbacks are discarded after navigating away or pausing.

Verification on October 28: offline Android build, lint and APK signature passed; 10 standalone matching/encoding/invalid-file checks passed. Installed on Nick’s Fire TV Stick 4K Max. The Matrix details visibly showed both playback buttons. Play with subtitles automatically downloaded a cached SRT and launched native playback; a screenshot showed an English subtitle cue at 1:01. CC visibly offered None and English. Selecting None removed the text. Re-enabling via the menu was not separately observed because local remote navigation resumed during verification. Full episode-provider coverage, subtitle timing across movie cuts, and audio synchronization are not established by this test.

Free provider documentation: https://www.subtitles.website/docs/


## v0.14 player redesign — November 23, 2026

The player now uses a native bottom overlay with title metadata, elapsed/remaining time, estimated finish time, a buffered timeline, 10-second skip buttons, and separate Audio, CC and Info popovers. Controls hide after four seconds of active playback and stay visible while paused, seeking or choosing options. Subtitle placement moves above the controls and returns when they hide. Browsing remains based on the prior build; this release focuses on playback.

Remote: Select toggles play/pause. Up reveals/focuses the timeline; Left/Right there stages a seek without moving the actual playback position. Holding accelerates from 10 to 30 to 60 seconds. Select commits and plays; Back cancels and restores the original playing/paused state. Down enters the button row, Left/Right moves between buttons, and Select activates. With the controls hidden, Left/Right skips 10 seconds immediately. Rewind/Fast Forward stage a seek; Play/Pause commits it. Back closes a menu, cancels a seek, hides controls, then exits playback on a subsequent press. Exiting saves position using existing app storage.

Audio and subtitle menus list supported tracks from the current video. CC Off disables text; selecting a text track enables it. When no subtitles are attached, Find English subtitles returns to title details and invokes the existing provider lookup, then resumes through the existing playback flow. This may require buffering again. Info offers restart and reports the current video format's dimensions when known. No thumbnail storyboard or scene metadata is supplied by the service adapter.

Validation: Android compilation and lint pass (zero lint errors; existing warnings remain). 17 standalone timeline tests cover seek clamping, unknown duration, overflow, acceleration, original-position preservation, paused-state preservation and time formatting. APK signature matches the November 2 APK. The interactive preview's seek, cancel, commit and subtitle controls were exercised in a browser. No Fire TV was connected; native rendering, physical-remote behavior, picture, sound and subtitle placement need device acceptance. The preview uses a static illustrative scene and simulated time/audio/subtitles; it is not a capture of the APK. Nothing was installed.

References: [Apple playback guide](https://support.apple.com/en-gu/guide/tv/atvb7944597f/tvos), [Media3 track selection](https://developer.android.com/media/media3/exoplayer/track-selection).
