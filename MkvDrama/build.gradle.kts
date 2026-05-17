version = 1

cloudstream {
    authors = listOf("IstarVin")
    language = "en"
    description =
        "Download Korean, Chinese, Thai & Japanese dramas, movies & shows in HD with subs. Enjoy free, fast access to the latest content at MkvDrama.net."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     **/
    status = 0 // will be 3 if unspecified
    tvTypes = listOf("AsianDrama")
    iconUrl = "https://mkvdrama.org/static/assets/favicon/apple-touch-icon.png"

    isCrossPlatform = true
}
