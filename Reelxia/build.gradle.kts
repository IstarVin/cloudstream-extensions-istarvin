version = 1

cloudstream {
    authors = listOf("IstarVin")
    language = "en"
    description =
        "Stream short dramas and mini series online with no signup required. Discover addictive stories in bite-sized episodes."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     **/
    status = 1 // will be 3 if unspecified
    tvTypes = listOf("AsianDrama")

    isCrossPlatform = true
}
