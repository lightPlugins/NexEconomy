plugins {
    id("java")
    kotlin("jvm") version "1.8.0" // Ensure you have the Kotlin plugin applied
    id("io.freefair.lombok") version "8.11"
    id("com.gradleup.shadow") version "8.3.5"
    id("maven-publish")
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.17"
}

group = "io.nexstudios.economy"
version = "1.0-SNAPSHOT"

base {
    archivesName.set("NexEconomy")
}

repositories {
    mavenCentral()
    maven("https://jitpack.io")
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
    maven("https://mvn.lumine.io/repository/maven-public/")
}

dependencies {
    paperweight.paperDevBundle("1.21.8-R0.1-SNAPSHOT")
    compileOnly(files("C:/Users/phili/IdeaProjects/Nexus/build/libs/Nexus-1.0.0-all.jar"))
    compileOnly("com.github.MilkBowl:VaultAPI:1.7") {
        exclude(group = "org.bukkit", module = "bukkit")
    }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks {
    processResources {
        from(sourceSets.main.get().resources.srcDirs()) {
            filesMatching("plugin.yml") {
                expand(
                    "name" to "NexEconomy",
                    "version" to version
                )

            }
            duplicatesStrategy = DuplicatesStrategy.INCLUDE
        }
    }

    compileJava {
        options.encoding = "UTF-8"
    }

    build {
        dependsOn(shadowJar)
    }

    shadowJar {
        archiveClassifier.set("")
        archiveBaseName.set("NexDrops")
        destinationDirectory.set(rootProject.layout.buildDirectory.dir("libs"))
        relocate("co.aikar.commands", "io.nexstudios.nexus.libs.commands")
        relocate("co.aikar.locales", "io.nexstudios.nexus.libs.locales")
    }
}