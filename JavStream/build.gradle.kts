version = 5

cloudstream {
    authors = listOf("NropVox")
    language = "en"
    description = "#1 best extention for JAV"
    status = 1 // will be 3 if unspecified
    tvTypes = listOf("NSFW")
    iconUrl =
        "https://t3.gstatic.com/faviconV2?client=SOCIAL&type=FAVICON&fallback_opts=TYPE,SIZE,URL&url=https://sextb.net/&size=128"
}

// Cloudstream's inline JSON APIs use Java 11 bytecode.
android {
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
}
