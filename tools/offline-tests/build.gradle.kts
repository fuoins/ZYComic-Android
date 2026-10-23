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
    "org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.6.1",
    "org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.6.1",
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

// MockK's own POM/Gradle metadata leaves kotlinx-coroutines-core and kotlin-reflect without
// versions (supplied through a coroutines BOM and a constraint), so a single transitive
// detached configuration fails to resolve (kotlinx-coroutines-core:). Pin every JVM runtime
// jar explicitly with @jar and transitive resolution disabled, matching the harness above.
// stdlib 2.2.21 and coroutines are already provided by the main harness classpath.
val mockkCoordinates = listOf(
    "io.mockk:mockk-jvm:1.14.11",
    "io.mockk:mockk-dsl-jvm:1.14.11",
    "io.mockk:mockk-agent-jvm:1.14.11",
    "io.mockk:mockk-agent-api-jvm:1.14.11",
    "io.mockk:mockk-core-jvm:1.14.11",
    "net.bytebuddy:byte-buddy:1.18.2",
    "net.bytebuddy:byte-buddy-agent:1.18.2",
    "org.objenesis:objenesis:3.4",
    "org.jetbrains.kotlin:kotlin-reflect:2.2.21",
)

val mockkDependencies = mockkCoordinates.map { coordinate ->
    configurations.detachedConfiguration(dependencies.create("$coordinate@jar")).apply {
        isTransitive = false
    }
}

tasks.register<Copy>("prepareHarnessDependencies") {
    from(harnessDependencies)
    from(mockkDependencies) {
        into("mockk")
    }
    into(layout.buildDirectory.dir("deps"))
}
