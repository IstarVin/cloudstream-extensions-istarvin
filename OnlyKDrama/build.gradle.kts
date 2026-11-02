version = 6

cloudstream {
    authors = listOf("NropVox")
    language = "en"
    description = "Korean, Chinese, Japanese and Thai dramas and movies from OnlyKDrama, many dubbed in Hindi. Plays the site's HubCloud, XCloud, GDFlix and worker download links (MKV, 480p-2160p); TMBCloud and FilePress need a captcha and are skipped."
    status = 2
    tvTypes = listOf("AsianDrama", "Movie")
    iconUrl = "https://www.google.com/s2/favicons?domain=onlykdrama.shop&sz=%size%"

    requiresResources = true
}

dependencies {
    implementation("com.google.android.material:material:1.13.0")
}

// The HubCloud and Pixeldrain resolvers are shared with Vegamovies, XPrimeHub and 4KHDHub; the site's requests and
// link resolving with OmniStream. The settings sheet (OmniStream's design) is shared: src/main/res is a symlink to
// shared/settingsui/res.
listOf("nexdrive", "onlykdramasource", "settingsui").forEach { android.sourceSets.getByName("main").kotlin.srcDir("../shared/$it") }
