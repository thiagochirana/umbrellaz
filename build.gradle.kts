import java.io.File
import org.gradle.jvm.tasks.Jar
import org.gradle.api.tasks.bundling.Zip

plugins {
    java
    id("net.fabricmc.fabric-loom") version "1.17.21"
}

loom {
    splitEnvironmentSourceSets()

    mods {
        create("umbrellaz") {
            sourceSet(sourceSets.main.get())
            sourceSet(sourceSets.getByName("client"))
        }
    }
}

group = "dev.chirana"
version = "1.4.5"

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
    exclude("resourcepack/**")

    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}

tasks.test {
    useJUnitPlatform()
}

val resourcePackZip = tasks.register<Zip>("resourcePackZip") {
    group = "distribution"
    description = "Builds the Umbrellaz client resource pack."
    archiveFileName.set("umbrellaz-resource-pack-${project.version}.zip")
    destinationDirectory.set(layout.buildDirectory.dir("libs"))
    from("src/main/resources/resourcepack")
    includeEmptyDirs = false
    isReproducibleFileOrder = true
    isPreserveFileTimestamps = false
}

val resourcePackArtifact = resourcePackZip.flatMap { it.archiveFile }

tasks.named("build") {
    dependsOn(resourcePackZip)
}

val releaseArtifact = tasks.named<Jar>("jar").flatMap { it.archiveFile }
val releaseTag = providers.gradleProperty("releaseTag")
    .orElse(providers.provider { "v${project.version}" })

tasks.register<Exec>("release") {
    group = "publishing"
    description = "Builds the project and publishes the main jar as a GitHub release."
    dependsOn(tasks.named("build"), resourcePackZip)
    outputs.upToDateWhen { false }
    isIgnoreExitValue = true

    doFirst {
        val artifact = releaseArtifact.get().asFile
        if (!artifact.isFile) {
            throw GradleException("Release artifact was not found: ${artifact.absolutePath}")
        }
        val resourcePack = resourcePackArtifact.get().asFile
        if (!resourcePack.isFile) {
            throw GradleException("Resource-pack artifact was not found: ${resourcePack.absolutePath}")
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
            resourcePack.absolutePath,
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
