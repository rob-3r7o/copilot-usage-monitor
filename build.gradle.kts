plugins {
    kotlin("jvm") version "2.4.0"
    id("org.jetbrains.intellij.platform") version "2.5.0"
}

group = "io.github.rpiu"
version = "0.0.3"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    testImplementation(kotlin("test-junit5"))

    intellijPlatform {
        local("/home/rpiu/tools/clion-last")
    }
}

kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "262"
        }
    }

    pluginVerification {
        ides {
            local(file("/home/rpiu/tools/clion-last"))
        }
    }
}

tasks.named("buildSearchableOptions") {
    enabled = false
}

tasks.named("jarSearchableOptions") {
    enabled = false
}

tasks.test {
    useJUnitPlatform()
}
