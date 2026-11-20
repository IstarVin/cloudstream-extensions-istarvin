version = 1

cloudstream {
    authors = listOf("NropVox")
    language = "en"
    description =
        "Live sports events (soccer, NHL, NBA, NFL, MMA, motorsport…), 24/7 TV channels and replays from tim-streams.com. " +
        "Live links open the site's player in a hidden WebView to find the stream (a few seconds), then play through a local unwrap proxy because the site hides its HLS segments inside PNG images."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     **/
    status = 3
    tvTypes = listOf("Live")
    iconUrl = "https://www.google.com/s2/favicons?domain=tim-streams.com&sz=%size%"
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
