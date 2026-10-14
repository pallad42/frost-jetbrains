import org.jetbrains.changelog.markdownToHTML

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.19.0"
    id("org.jetbrains.changelog") version "2.5.0" apply false // only for markdownToHTML (the description)
}

group = "com.github.pallad42"
version = "0.2.1"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

/** Your installed IntelliJ IDEA when there is one (faster, no download); otherwise (CI) the version from gradle.properties. */
val localIde: File? = providers.gradleProperty("ideaPath").orNull?.let(::file)?.takeIf { it.exists() }

dependencies {
    intellijPlatform {
        if (localIde != null) local(localIde) else intellijIdea(providers.gradleProperty("platformVersion").get())
    }
}

kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    // Keep the sandbox IDE under build/ so opening this project in the sandbox does not index it.
    sandboxContainer = layout.buildDirectory.dir("idea-sandbox")
    pluginConfiguration {
        // The Marketplace description is the README part between these markers.
        description = providers.fileContents(layout.projectDirectory.file("README.md")).asText.map {
            val start = "<!-- Plugin description -->"
            val end = "<!-- Plugin description end -->"
            val lines = it.lines()
            if (start !in lines || end !in lines) throw GradleException("README.md has no $start ... $end section")
            markdownToHTML(lines.subList(lines.indexOf(start) + 1, lines.indexOf(end)).joinToString("\n"))
        }
        ideaVersion {
            sinceBuild = "262"
            untilBuild = provider { null }
        }
    }
    buildSearchableOptions = false
    instrumentCode = false

    pluginVerification {
        ides {
            current()
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
    // ./gradlew runIde -Pfrost.driver=<dir>: execute commands from <dir>/cmd.txt (see FrostDriver).
    providers.gradleProperty("frost.driver").orNull?.let { jvmArgs("-Dfrost.driver=$it") }
}
