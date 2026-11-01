The series mock was copied from `mkvdrama-bypass/postman/mocks/series`. It models
the captured seed/gate/pass protocol with invented keys, cookies, and destinations.
It does not model Cloudflare or prove live shortener completion. No workspace,
login, or deployment is needed.

```sh
postman simulation run MkvDrama/postman/simulations/mkvdrama.sim.yaml --port 4947 --no-history --output ndjson
MKVDRAMA_SIMULATION_URL=http://127.0.0.1:4947 ./gradlew :MkvDrama:testDebugUnitTest
```

The Kotlin test runs the production HTTP session and decoder against this mock,
checking session continuity, both authenticated decryptions, same-origin paths,
verification rejection, ciphertext tampering, and stopping at a file-host redirect.
It also checks HTML catalog/detail parsing through the production HTTP session
and reusing a fetched metadata page for the download handshake. The HTML routes
reject JSON requests with a key error, modeling the observed JSON search
restriction; this restriction is not claimed for every catalog/detail endpoint.
Captured HTML parser fixtures cover movies, ongoing dramas, filtered catalogs,
and paginated search separately from the simulation's small representative pages.
The HTTP test skips when `MKVDRAMA_SIMULATION_URL` is unset; parser tests always run.
