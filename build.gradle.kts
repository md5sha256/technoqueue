plugins {
    id("java-library")
    alias(libs.plugins.run.velocity)
}

version = "1.0.1"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.spongepowered.org/repository/maven-public/")
}

dependencies {
    compileOnly(libs.velocity.api)
    compileOnly(libs.luckperms.api)
    implementation(libs.configurate.yaml)

    testImplementation(libs.velocity.api)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
    withSourcesJar()
}

tasks {
    runVelocity {
        velocityVersion(libs.versions.velocity.api.get())
    }

    processResources {
        val props = mapOf("version" to version)
        filesMatching("velocity-plugin.json") {
            expand(props)
        }
    }

    test {
        useJUnitPlatform()
    }
}
