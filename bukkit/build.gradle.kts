plugins {
    id("java")
    id("com.gradleup.shadow") version "9.0.1"
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.19"
}

dependencies {
    paperweight.paperDevBundle("1.21.11-R0.1-SNAPSHOT")

    compileOnly("io.nexstudios:framework-paper:v1.0.2")
    compileOnly("io.nexstudios.itemservice:bukkit:v1.0.0")
    compileOnly("io.nexstudios.menuservice:bukkit:v1.0.1")
    compileOnly("io.nexstudios.configservice:platform:v1.0.0")
    compileOnly("io.nexstudios.languageservice:bukkit:v1.0.0")
    compileOnly("io.nexstudios.commandservice:bukkit:v1.0.0")

    compileOnly("io.nexstudios.nexlogic:nexlogic-bukkit:v1.0.0")

}
tasks.jar {
    enabled = false
}


tasks.processResources {
    filteringCharset = "UTF-8"
}

tasks.shadowJar {
    archiveClassifier.set("")
    archiveBaseName.set("NexEconomy")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.build {
    dependsOn(tasks.shadowJar)
}