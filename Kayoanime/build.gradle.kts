version = 6

cloudstream {
    authors = listOf("NropVox")
    language = "en"
    description = "Anime from kayoanime.com: HEVC 1080p Dual Audio / Eng Sub releases hosted on Google Drive. " +
        "Drive files are played through your own Google Drive proxy (Cloudflare Worker); set its API key in the plugin settings. " +
        "Posts hosted on the site's Drive index mirrors play without it. " +
        "Posts whose Drive folders are dead or only hold archives show no episodes."
    status = 1
    tvTypes = listOf("Anime", "AnimeMovie")
    iconUrl = "https://www.google.com/s2/favicons?domain=kayoanime.com&sz=%size%"

    requiresResources = true
    isCrossPlatform = false
}

dependencies {
    implementation("com.google.android.material:material:1.13.0")
}

// The settings sheet (OmniStream's design) is shared: src/main/res is a symlink to shared/settingsui/res.
android.sourceSets.getByName("main").kotlin.directories.add("../shared/settingsui")
