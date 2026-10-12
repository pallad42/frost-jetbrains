plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "com.github.pallad42"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        local(providers.gradleProperty("ideaPath").get())
    }
}

kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    // Keep the sandbox IDE under build/ so opening this project in the sandbox does not index it.
    sandboxContainer = layout.buildDirectory.dir("idea-sandbox")
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "262"
            untilBuild = provider { null }
        }
    }
    buildSearchableOptions = false
    instrumentCode = false

    pluginVerification {
        ides {
            local(file(providers.gradleProperty("ideaPath").get()))
        }
    }

    // Marketplace release: ./gradlew signPlugin publishPlugin with these environment variables set.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}

/** Installs the freshly built plugin straight into your everyday IntelliJ (takes effect after an IDE restart). */
val installPlugin by tasks.registering(Sync::class) {
    group = "intellij platform"
    description = "Builds the plugin and installs it into the IntelliJ configured by ideaConfigDir"

    val configDir = file(
        providers.gradleProperty("ideaConfigDir")
            .orElse(System.getProperty("user.home") + "/Library/Application Support/JetBrains/IntelliJIdea2026.2")
            .get(),
    )
    val disabledPlugins = configDir.resolve("disabled_plugins.txt")
    val pluginId = "com.github.pallad42.frost"

    val pluginZip = tasks.named<Zip>("buildPlugin").flatMap { it.archiveFile }
    dependsOn("buildPlugin")
    from(zipTree(pluginZip)) {
        // The zip contains a top-level "frost/" folder; strip it.
        eachFile { relativePath = RelativePath(true, *relativePath.segments.drop(1).toTypedArray()) }
        includeEmptyDirs = false
    }
    into(configDir.resolve("plugins/frost"))

    doLast {
        if (disabledPlugins.exists()) {
            disabledPlugins.writeText(disabledPlugins.readLines().filter { it.trim() != pluginId }.joinToString("\n", postfix = "\n"))
        }
        println("Installed into ${configDir.resolve("plugins/frost")} - restart IntelliJ to load it.")
    }
}


tasks.named<JavaExec>("runIde") {
    // Frost's own debug logging (native window state, timings) in the sandbox IDE's idea.log.
    jvmArgs("-Didea.log.debug.categories=#com.github.pallad42.frost")
    // ./gradlew runIde -Pfrost.selftest: show every kind of overlay in turn (see FrostSelfTest).
    if (providers.gradleProperty("frost.selftest").isPresent) jvmArgs("-Dfrost.selftest=true")
    // ./gradlew runIde -Pfrost.perftest: scroll a maximised editor with and without the effect (see FrostPerfTest).
    if (providers.gradleProperty("frost.perftest").isPresent) jvmArgs("-Dfrost.perftest=true")
}
