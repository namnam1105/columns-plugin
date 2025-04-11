plugins {
    kotlin("jvm") version "1.9.20"
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "ru.columns"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") // PaperMC repository
}

dependencies {
    // Kotlin standard library
    implementation(kotlin("stdlib"))

    // PaperMC API (change 1.21.1-R0.1-SNAPSHOT to match your server version)
    compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
}

tasks {
    compileKotlin {
        kotlinOptions.jvmTarget = "21"
    }

    processResources {
        filteringCharset = "UTF-8"
        filesMatching("plugin.yml") {
            expand(
                mapOf(
                    "name" to rootProject.name,
                    "version" to project.version,
                    "main" to "${project.group}.ColumnsPlugin"
                )
            )
        }
    }

    shadowJar {
        archiveBaseName.set(rootProject.name)
        archiveClassifier.set("")
        archiveVersion.set(project.version.toString())
    }

    build {
        dependsOn(shadowJar)
    }
}