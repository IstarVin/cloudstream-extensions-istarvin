# Provider audit — 14 October 2026

Live HTML, player responses, and HLS manifests were inspected. FlareSolverr 3.5.0
on localhost:8191 supplied browser sessions where direct requests were challenged.
No video segments were downloaded. These checks do not replace device playback tests.

| Module | Version | Live findings | Changes |
| --- | --- | --- | --- |
| JavFc2 | 7 | Home: 30 cards; search: 25 cards; detail: one episode. Extracted playlist returned HTTP 200 and `#EXTM3U`. | Remove query parameters from subtitle code extraction; resolve relative subtitle URLs. |
| JavHdz | 11 | Old `.im` domain redirects to `.la`; old AJAX endpoint fails. Home: 18 cards; search: one matching card. All four current server responses returned successfully with browser headers. Direct HLS master includes 480p, 720p, and 1080p. | Update default domain; encode search terms; normalize subtitle codes; use numeric JSON player requests with Origin/referer; fall back to the detail page's first player; preserve cancellation. Set this module's JVM target to 11 for current Cloudstream inline APIs. |
| MissAV | 4 | Home, weekly, search, and detail selectors still match. Each sampled list returned 12 unique cards. Direct page requests received Cloudflare 403; FlareSolverr succeeded. Existing packed-source UUID extraction still matches. | Add a reusable CloudflareKiller interceptor to provider page requests. |
| SexTB | 7 | Browser session returned lists and search results. All six free-server AJAX calls returned HTTP 200 with decryptable embeds. Current authentication comes from page metadata; player tokens rotate after each request. | Replace the hardcoded Basic key with page metadata; request servers sequentially and rotate tokens; preserve full iframe queries/fragments; retain the default player; fix pagination with query parameters; add CloudflareKiller to page requests. |
| Extractors | 17 | SexTB's default F4 embed exposes `data-api`. Its native playback API returns a relative signed URL. Current AsnWish/Dhtpre packed scripts contain `master.txt` and relative `/stream/…/master.m3u8` URLs. AsnWish's relative playlist and Dhtpre's `master.txt` returned HTTP 200 and `#EXTM3U`. SubtitleCat search and English download selectors still match. | Register F4Stream/F4Scdn and resolve their API/playback URLs, respecting HLS/progressive response types. Recognize packed `master.txt` links and resolve relative playlists. Await subtitle requests, resolve download URLs, and filter matches before limiting results. |
| JavStream | 5 | Uncensored pages 1 and 2 each returned 30 cards, with no overlapping URLs. Search returned three cards; detail code and metadata selectors match. Javtiful's current search returned two cards. JavGG/SupJav search and player selectors match sampled pages. | Fix pagination before query parameters; encode search paths; add CloudflareKiller to catalogue requests; normalize movie codes and preserve cancellation. Update local Javtiful selectors/watch-config, local SexTB metadata authentication/sequential token rotation/full iframe URLs/default player, and local MissAV challenge handling. Match movie codes before selecting results. Set module JVM target to 11 for current Cloudstream JSON APIs. |

## Remaining limits

- MissAV's extracted Surrit media URL received HTTP 403; FlareSolverr also reported an upstream block. Playback from this IP was not verified.
- SexTB's custom browser challenge was solved for the audit with FlareSolverr. The new CloudflareKiller path has not been exercised in the Android runtime.
- The sampled F4 signed playback URL redirected to an upstream HTTP 404. RyderJet's sampled file reported that it had expired or been deleted. Extractors cannot restore unavailable upstream files.
- JavHD's `turbovidhls.com` alternate server did not resolve in this environment. The primary HLS server worked.
- AsnWish's sampled `master.txt` host returned HTTP 403, while its relative playlist worked. Dhtpre's alternate relative endpoint timed out.
- Packed playlist requests isolate failures so an unavailable host does not prevent trying the other extracted links.
- Extractors contains additional host aliases beyond the embeds reached from these providers. Those aliases were not exhaustively verified. Google translation and full device playback were not exercised.
- FlareSolverr was used for this audit; the plugins do not connect to localhost:8191 during normal use.
- JavStream's browser checks validate catalogue and source-page selectors. End-to-end Android playback, its CloudflareKiller runtime behavior, and all alternate source hosts remain unverified; the upstream playback limits above also apply to its local adapters.

## Validation and artifacts

All five modules passed `assembleDebug`. All five plugin packages passed:

```sh
./gradlew :JavFc2:make :JavHdz:make :MissAV:make :Extractors:make :SexTB:make --no-configuration-cache
```

After the final packed-link correction, `:Extractors:make` passed again.
`git diff --check` passed. An independent review checked the changed provider and
extractor logic; its F4 type and JavHD cancellation findings were corrected.
Package manifests contain versions 7, 11, 4, 17, and 7 respectively.

Installable files are in each module's `build/<Module>.cs3`. JavFc2 also produced
a cross-platform JAR and passed the no-Android-import compatibility check.
Unrelated concurrent edits to Javtiful were left untouched.

JavStream subsequently passed `:JavStream:assembleDebug :JavStream:make
--no-configuration-cache`. Its `JavStream/build/JavStream.cs3` manifest reports
version 5. Its local adapters use the current source contracts without depending
on the standalone provider modules.
Standalone JVM checks exercised the compiled card parser, movie-code matching,
JSON models, URL encoding round trips, and both coroutine cancellation helpers.
All passed. `git diff --check` passed after the JavStream changes.
