plugins {
    base
}

repositories {
    mavenCentral()
}

// Resolve each coordinate separately: the legacy harnesses intentionally exercise
// both old and current coroutine versions rather than silently upgrading them.
val harnessCoordinates = listOf(
    "org.jetbrains.kotlin:kotlin-compiler-embeddable:2.2.21",
    "org.jetbrains.kotlin:kotlin-stdlib:2.2.21",
    "org.jetbrains.kotlin:kotlin-script-runtime:2.2.21",
    "org.jetbrains.kotlin:kotlin-reflect:1.6.10",
    "org.jetbrains.kotlin:kotlin-daemon-embeddable:2.2.21",
    "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.8.0",
    "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2",
    "org.jetbrains:annotations:13.0",
    "com.squareup.okhttp3:okhttp-jvm:5.3.2",
    "com.squareup.okhttp3:mockwebserver3:5.3.2",
    "com.squareup.okio:okio-jvm:3.16.4",
    "com.squareup.okhttp3:okhttp:4.12.0",
    "com.squareup.okio:okio-jvm:3.6.0",
    "org.json:json:20240303",
    "org.junit.platform:junit-platform-console-standalone:1.10.2",
)

val harnessDependencies = harnessCoordinates.map { coordinate ->
    configurations.detachedConfiguration(dependencies.create("$coordinate@jar")).apply {
        isTransitive = false
        if (coordinate.startsWith("org.junit.platform:junit-platform-console-standalone:")) {
            attributes {
                attribute(
                    org.gradle.api.attributes.Bundling.BUNDLING_ATTRIBUTE,
                    objects.named(org.gradle.api.attributes.Bundling.SHADOWED),
                )
            }
        }
    }
}

tasks.register<Copy>("prepareHarnessDependencies") {
    from(harnessDependencies)
    into(layout.buildDirectory.dir("deps"))
}
