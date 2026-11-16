plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.google.services) apply false
}

// The unit tests run on a plain JVM, where Android's org.json is only an empty stub. The code-update checks
// read JSON, so this puts the real org.json on the TEST classpath only; nothing is added to the app.
// It is declared here and not in app/build.gradle.kts on purpose: that file feeds STABLE_API, so editing it
// would change the build number and phones would stop accepting code updates built for the old one.
subprojects {
    plugins.withId("com.android.application") {
        dependencies.add("testImplementation", "org.json:json:20240303")
    }
}
