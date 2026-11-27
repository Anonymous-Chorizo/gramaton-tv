# Native TV experience review — v0.4

The largest improvement is predictable behavior: keep a person's place, show usable content promptly, and make remote focus unmistakable. The visual direction uses cinematic artwork, a compact navigation bar, a dark blue palette and restrained motion. It preserves Gramaton's homepage section ordering and the existing blue-and-white G identity.

## Findings

| Area | Finding in v0.3 | Change in v0.4 |
| --- | --- | --- |
| Screen hierarchy | Large menu boxes and a mostly empty title area compete with the catalogue. | Compact navigation, an artwork-backed title panel, shorter metadata, and less instructional clutter. |
| Focus | Background catalogue changes rebuild every card and can move selection back to the start. | Focus is identified by section and title URL; page snapshots remember the selected title and scroll positions. |
| Remote navigation | Returning down from navigation starts at the first card; View all is awkwardly separated from its row. | Navigation returns to the remembered card. View all becomes a trailing row action. The active tab and keyboard focus have distinct appearances. |
| Artwork | Every poster is queued, including rows outside the viewport. | Request visible artwork and nearby posters, deduplicate simultaneous requests, and share a bounded 16 MiB image cache across cards and the title panel. |
| Catalogue loading | Revisited sections are fetched again; extraction can run fourteen times after a navigation. | Keep up to eight catalogue snapshots in memory, show them immediately on revisit, and stop extraction after consecutive stable reads. A fresh, already-loaded page can be reused for sixty seconds. |
| Loading feedback | A full loading screen is used for repeated visits even when content is already known. | Keep the explicit spinner/stage/elapsed/retry screen for cold loads. Use a small navigation activity indicator while refreshing cached content. |
| Details | Stacked large controls and a long episode list make actions slower to find. | Compact action row, a prominent Play/Resume action, a dedicated TV tab, and season-first episode selection. |

## Design basis

Apple's focus guidance emphasizes visible focus states, room for enlarged focused content, and predictable transitions. On Fire TV, this implementation uses native Android views and D-pad handling to express those principles with a clear white focus outline and a short scale/elevation animation. It does not attempt to run tvOS frameworks. [Apple: Focus and selection](https://developer.apple.com/design/human-interface-guidelines/focus-and-selection/)

Apple recommends displaying available content promptly and letting people continue using an app during loading. Cached catalogue screens therefore remain available during refresh; a first visit still shows explicit loading feedback. [Apple: Loading](https://developer.apple.com/design/human-interface-guidelines/loading)

Amazon emphasizes low information density, remote navigation, readable text, and a safe area around screen edges. The new layout reserves horizontal and vertical margins, reduces decorative controls and keeps focused cards from clipping into their neighbors. [Amazon: TV design guidelines](https://developer.amazon.com/docs/fire-tv/design-and-user-experience-guidelines.html)

## Engineering details and retained behavior

- Gramaton's seven homepage sections, their ordering, and repeated titles across different sections remain intact. Search, Watchlist, Favorites, series browsing and episode selection remain available.
- Media3 playback, fullscreen launch, source selection, lower-quality fallback and saved playback positions are retained. This update does not establish a new provider API or promise an unavailable video resolution.
- The catalogue cache contains website-provided title metadata, not saved playback access URLs. It is memory-only, bounded and cleared when entering account management or encountering a sign-in page. The image loader bounds response size, uses background workers and checks that a result still belongs to its target view.
- The artwork header uses images actually supplied by Gramaton. It does not invent backdrop image identifiers. Portrait artwork is cropped and shaded when a landscape asset is not supplied.
- Native focus state is retained across catalogue refreshes and detail navigation. Duplicate detail readiness callbacks no longer intentionally force focus back to Play.

## Verification and limits

The v0.4 source compiles successfully and Android lint reports zero errors. Remaining warnings include prototype localization, newer Android/build versions, the programmatic view constructor, fixed TV orientation and existing WebView JavaScript use.

Before this update, a diagnostic sample on the Ember Artline reported 586 attached views and a 24 ms median / 57 ms 95th-percentile frame duration. That sample includes prior activity and startup and is not a controlled before/after benchmark. It motivates reducing background work; it does not establish a measured v0.4 frame-rate improvement.

The final v0.4 APK was installed successfully on the Ember Artline after reconnecting to the TV. Device checks verified the artwork-backed homepage, loading feedback, remote poster selection, enabled movie Play action, and service-backed search for `star` returning matching movie and TV titles. Back preserved the selected California Schemin' poster after a background refresh. Testing exposed changing featured titles on return; the fix retains the displayed catalogue while refreshing the website context. No AndroidRuntime crash was reported during these checks. Season-dialog navigation, end-to-end episode playback, sound, actual decoded resolution, fallback, and resume still need acceptance testing. Search can briefly display old catalogue content while the service finishes its response. A final layout adjustment enlarged the hero panel to allow two-line titles and their descriptions.

The provider still supplies authentication, catalogue and playback data through a WebView adapter. Cold loads still depend on its response time. System voice integration, cross-device resume, and a fully virtualized RecyclerView/Leanback catalogue are separate future work. Virtualization should be evaluated with controlled measurements if catalogue sizes or on-device profiling justify it; this update retains the working navigation structure while reducing unnecessary image and refresh work.

## Device acceptance checks

1. Browse horizontally and vertically; verify focus remains visible and no card edge is clipped.
2. Open a title several rows down and press Back; verify the same title and row position are restored.
3. Revisit Home, Movies, TV and Watchlist; verify cached content appears while refresh proceeds without resetting selection.
4. Search for a title; verify full service results, keyboard dismissal and Back behavior.
5. Open a series; choose a season and episode, then verify Play/Resume and return navigation.
6. Verify fullscreen picture, sound, media keys, pause/resume and saved position on the target TV.
7. Compare rendering metrics over the same scripted navigation sequence with a fresh statistics window before making a performance claim.
