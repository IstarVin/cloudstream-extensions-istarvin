version = 1

cloudstream {
    authors = listOf("NropVox")
    language = "en"
    description =
        "Live sports schedule from sportsbite.org: football, basketball, NFL, fighting, motorsport and more. HLS servers appear once a match is assigned streams; the 24/7 channels are not included."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     **/
    status = 3
    tvTypes = listOf("Live")
    iconUrl = "https://www.google.com/s2/favicons?domain=sportsbite.org&sz=%size%"
}

android {
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
