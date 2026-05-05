version = 2

cloudstream {
    authors     = listOf("IstarVin")
    language    = "fil"
    description = "Sulasok"

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
    **/
    status  = 1 // will be 3 if unspecified
    tvTypes = listOf("Movie")
    iconUrl = "https://t3.gstatic.com/faviconV2?client=SOCIAL&type=FAVICON&fallback_opts=TYPE,SIZE,URL&url=https://pinoymoviepedia.ru&size=16"

    isCrossPlatform = true
}
