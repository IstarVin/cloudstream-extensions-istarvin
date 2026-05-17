import java.util.Properties

version = 6

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}

android {
    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        buildConfigField(
            "String",
            "GOOGLE_TRANSLATE_API_KEY",
            "\"${localProperties.getProperty("GOOGLE_TRANSLATE_API_KEY", "")}\""
        )
    }
}

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
}
