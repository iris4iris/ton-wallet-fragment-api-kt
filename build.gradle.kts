import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.1.21"
    kotlin("plugin.serialization") version "2.1.21"
    application
}

group = "iris.ton"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.ktor:ktor-client-cio:3.1.1")
    implementation("io.ktor:ktor-client-content-negotiation:3.1.1")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")

    implementation("org.ton.ton4j:smartcontract:2.1.0")
    implementation("org.ton.ton4j:mnemonic:2.1.0")
    implementation("org.ton.ton4j:utils:2.1.0")
    implementation("org.ton.ton4j:address:2.1.0")
    implementation("org.ton.ton4j:toncenter:2.1.0")
}

application {
    mainClass.set("iris.ton.fragment.example.MainKt")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_19)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_19
    targetCompatibility = JavaVersion.VERSION_19
}

// Kotlin-only module: don't probe a JDK 17 toolchain just to compile zero .java files.
tasks.named<JavaCompile>("compileJava") {
    isEnabled = false
}
tasks.named<Jar>("jar") {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
