plugins {
    id("java")
}

group = "io.nexstudios.economy"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

tasks.named<Jar>("jar") {
    enabled = false
}