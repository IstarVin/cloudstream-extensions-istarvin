# strmfree.pk (StreamFree) recon, 2026-11-14

Live sports only (basketball, soccer, football, hockey, baseball, combat, racing, tennis, cricket) plus a few 24/7 channels
(`skyf1`, `willow`, `skysportstennis`, `skysportscricket`). Client-rendered; mirrors `streamfree.top`, `strmfree.st`, `strmfree.link`.
Samples used by the tests are in `StrmFree/src/test/resources/`.

## Catalog (no auth, `access-control-allow-origin: *`)

| Request | Gives |
|---|---|
| `GET /api/v1/streams` | every listed stream: `name`, `category`, `league`, `stream_key`, `match_timestamp` (unix s), `viewers`, absolute `thumbnail_url` (16:9 WebP), `sources[]`, `team1/2` |
| `GET /api/v1/streams/{key}` | one stream; 404 `{"error":"Stream not found"}` when it is not listed |
| `GET /api/v1/sources/{key}` | `sources[]` only: `{quality: SD/HD/FHD/4K, url: embed page, live, broadcast}`; empty until the event goes up |
| `GET /api/v1/categories` | category ids |
| `GET /streams`, `/fixtures/all` | the home page's own feeds (site-relative thumbnails, fixtures with team logos) |

`sources` is empty for events that have not started, and 24/7 channels keep a stale `match_timestamp`, so "live" = any source with `live: true`.
Up to 5 sources per stream; the variant is the embed URL's last segment (`<key>1080p`, `<key>1080p2` …).

## Playback

1. `GET /embed/{category}/{variant}` (the same page on every mirror) holds, in an inline script:
   `const VARIANT = '<key>' + QUALITY + SOURCE_SUFFIX;`, `const IS_LIVE`, and
   `const _0x = {"1080p": {"_t": …, "_e": <expiry, ~8 h ahead>, "_n": …}, "720p": …}`, a per-quality signature.
2. `GET /get-stream-key/{key}` → `{server_name: "externalcdn"|"origin"|…, is_external, external_url}`.
3. Playlist = `/{prefix}/{variant}/index.m3u8?_t=…&_e=…&_n=…` with prefix `live-externalcdn` (externalcdn), `live` (origin) or `live-cdn` (other).
   Without the signature it is a 403.
4. The media playlist is plain HLS (3.8-5.6 s segments, no ENDLIST). Segments are absolute `ttcdn-us.com/obj/tos-…` URLs with an
   `#sf=<own-cdn url>` fragment (the site's player falls back to it). They answer `image/png` with MPEG-TS starting 64 bytes in,
   so they need no headers; ExoPlayer's TS sniffer scans the first 188 bytes for sync, so the playlist can be handed over as is.

## Caveat seen while probing from a cloud container

Playlist requests with a valid fresh signature succeeded only about 10-30% of the time, on every prefix and with or without
a Referer, and the embed page returns the same token to each request. That looks like the signature being bound to the
client IP while the container's egress IP rotates; it was not confirmed. The plugin therefore tries all three prefixes (twice)
and, if none answers, still emits the predicted URL. Playback has not been verified on a phone.
