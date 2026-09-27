import java.io.File
import org.gradle.jvm.tasks.Jar

plugins {
    java
    id("net.fabricmc.fabric-loom") version "1.17.21"
}

group = "dev.chirana"
version = "1.3.0"

base {
    archivesName = "umbrellaz"
}

repositories {
    mavenCentral()
}

dependencies {
    minecraft("com.mojang:minecraft:26.3")

    implementation("net.fabricmc:fabric-loader:0.19.5")
    implementation("net.fabricmc.fabric-api:fabric-api:0.161.0+26.3")

    include(implementation("org.xerial:sqlite-jdbc:3.53.4.0")!!)

    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }

    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
    options.encoding = "UTF-8"
}

tasks.processResources {
    inputs.property("version", project.version)

    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}

tasks.test {
    useJUnitPlatform()
}

val releaseArtifact = tasks.named<Jar>("jar").flatMap { it.archiveFile }
val releaseTag = providers.gradleProperty("releaseTag")
    .orElse(providers.provider { "v${project.version}" })

tasks.register<Exec>("release") {
    group = "publishing"
    description = "Builds the project and publishes the main jar as a GitHub release."
    dependsOn(tasks.named("build"))
    outputs.upToDateWhen { false }
    isIgnoreExitValue = true

    doFirst {
        val artifact = releaseArtifact.get().asFile
        if (!artifact.isFile) {
            throw GradleException("Release artifact was not found: ${artifact.absolutePath}")
        }

        val ghAvailable = System.getenv("PATH")
            ?.split(File.pathSeparator)
            ?.any { pathEntry ->
                val executable = File(pathEntry, "gh")
                executable.isFile && executable.canExecute()
            } == true
        if (!ghAvailable) {
            throw GradleException(
                "GitHub CLI (gh) was not found on PATH. Install gh before running ./gradlew release."
            )
        }

        val tag = releaseTag.get()
        if (tag.isBlank()) {
            throw GradleException("The releaseTag Gradle property must not be blank.")
        }

        commandLine(
            "gh",
            "release",
            "create",
            tag,
            artifact.absolutePath,
            "--title",
            "Umbrellaz ${project.version}",
            "--generate-notes"
        )
    }

    doLast {
        if (executionResult.get().exitValue != 0) {
            throw GradleException(
                "GitHub release creation failed. Authenticate with `gh auth login` or set GH_TOKEN, " +
                    "and verify that the release tag does not already have a release."
            )
        }
    }
}
