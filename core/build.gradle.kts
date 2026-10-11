// Pure JVM module with the agent engine: LLM client, tool loop, workspace sandbox.
// Kept free of Android APIs so it can be unit-tested on any JVM.
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

tasks.test {
    useJUnitPlatform()
}
