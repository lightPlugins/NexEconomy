plugins {
    id("java")
    id("io.freefair.lombok") version "8.13.1" apply false
}

allprojects {
    group = "io.nexstudios.nexeconomy"
    version = "v1.0.0"

    repositories {
        mavenLocal()
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://jitpack.io")
        maven("https://repo.codemc.io/repository/creatorfromhell/")
    }
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "io.freefair.lombok")

    dependencies {
        compileOnly("org.jetbrains:annotations:26.0.2")
        testCompileOnly("org.jetbrains:annotations:26.0.2")
    }

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(21)
    }
}