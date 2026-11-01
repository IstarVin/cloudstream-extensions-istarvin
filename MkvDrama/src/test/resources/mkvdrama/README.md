Public HTML captured 2026-11-01 from `https://mkvdrama.net`:

| Fixture | URL | Trim |
| --- | --- | --- |
| catalog.html | `/titles/` | First two cards and pagination |
| movies-page-2.html | `/titles/?type=movie&order=latest&page=2` | First two cards and pagination |
| search.html | `/search?q=genius&page=1` | First two cards and pagination |
| movie.html | `/bad-genius` | `.bigcontent` and `.bixbox.synp`, account controls removed |
| drama.html | `/khemjira-forever-kezqbv65` | `.bigcontent` and `.bixbox.synp`, account controls removed |

All returned HTTP 200 HTML in a browser-backed session. Direct requests without
Cloudflare clearance returned a challenge. The HTML fixtures contain no cookies,
API keys, download tokens, or executable page scripts. Capture trimming used
BeautifulSoup to serialize the selected public elements.

The movie's Aired year is 2017 even though its site post was published in 2026.
The ongoing drama advertises four planned episodes; the provider continues to
derive available episodes from the download panel rather than that count.
