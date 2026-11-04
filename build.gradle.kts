plugins {
    java
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

group = "com.cheonsa"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.runServer {
    minecraftVersion("1.21.11")
    downloadPlugins {
        modrinth("viaversion", "5.12.2")
    }
}
