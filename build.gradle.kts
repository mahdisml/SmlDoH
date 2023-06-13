plugins {
    kotlin("jvm") version "1.8.21"
    application
}

group = "dev.mahdisml"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}



kotlin {
    jvmToolchain(11)
}

application {
    mainClass.set("MainKt")
}

dependencies{
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.1")
    implementation("io.ktor:ktor-client-okhttp:2.3.1")
    implementation("com.squareup.okhttp3:okhttp:4.11.0")
    implementation("dnsjava:dnsjava:3.5.2")
}