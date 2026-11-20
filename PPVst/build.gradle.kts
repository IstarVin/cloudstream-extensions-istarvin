version = 1

cloudstream {
    authors = listOf("NropVox")
    language = "en"
    description =
        "Live sports events and 24/7 channels from ppv.st (NFL, NBA, soccer, motorsport, WWE...). Each event offers several feeds (HD/SD, other broadcasters). Links come from the embed player in a hidden browser, so they can take 5-15 s to appear."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     **/
    status = 3 // will be 3 if unspecified
    tvTypes = listOf("Live")
    iconUrl = "https://www.google.com/s2/favicons?domain=ppv.st&sz=%size%"
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
