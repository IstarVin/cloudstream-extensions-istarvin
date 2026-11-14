version = 1

cloudstream {
    authors = listOf("NropVox")
    language = "en"
    description =
        "Live sports from strmfree.pk (StreamFree): NBA, NFL/NCAA, soccer, hockey, baseball, UFC/combat, F1, tennis and cricket events plus 24/7 sports channels. HLS, up to 5 sources per event with the broadcaster named. Events appear a few minutes before kick-off."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     **/
    status = 3 // will be 3 if unspecified
    tvTypes = listOf("Live")
    iconUrl = "https://www.google.com/s2/favicons?domain=strmfree.pk&sz=%size%"
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
