version = 1

cloudstream {
    authors = listOf("NropVox")
    language = "en"
    description =
        "Live sports and TV from ntv.st: Server Titan matches (NCAA, NFL, NBA, soccer, motorsport, WWE…) and the cdnlive / Falcon 24/7 channels. HLS; the Raptor, Phoenix and embedsports servers are not supported."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     **/
    status = 3 // will be 3 if unspecified
    tvTypes = listOf("Live")
    iconUrl = "https://www.google.com/s2/favicons?domain=ntv.st&sz=%size%"
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
