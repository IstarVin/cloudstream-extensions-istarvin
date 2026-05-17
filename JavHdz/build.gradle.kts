version = 7

cloudstream {
    authors = listOf("IstarVin")
    language = "en"
    description =
        "Japanese Adult Video from Vietnam Server"

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     **/
    status = 1 // will be 3 if unspecified
    tvTypes = listOf("NSFW")
    iconUrl = "https://javhdz.men/favicon.ico"

    requiresResources = true
    isCrossPlatform = false
}

dependencies {
    implementation("com.google.android.material:material:1.13.0")
}
