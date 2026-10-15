plugins {
    id("magisk.android.library")
    // The hook process reads preferences from this module, so it must carry the
    // distribution flavors (smscode.android.common) the app decides with.
    id("smscode.android.common")
    id(libs.plugins.kotlin.serialization.get().pluginId)
    id(libs.plugins.kotlin.parcelize.get().pluginId)
    id("magisk.android.room")
}
val relayDownloadUrl = "https://github.com/magisk3171/xinyi-relay"


android {
    namespace = "io.github.magisk317.smscode.runtime"

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "META-INF/NOTICE.md"
            excludes += "META-INF/LICENSE.md"
        }
    }

    defaultConfig {
        buildConfigField("String", "LOG_TAG", "\"smscode\"")
        buildConfigField("String", "APPLICATION_ID", "\"com.github.tianma8023.xposed.smscode\"")
        buildConfigField("String", "B_DOWNLOAD_URL", "\"$relayDownloadUrl\"")
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            buildConfigField("int", "LOG_LEVEL", "4")
            buildConfigField("boolean", "LOG_TO_XPOSED", "true")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            buildConfigField("int", "LOG_LEVEL", "2")
            buildConfigField("boolean", "LOG_TO_XPOSED", "true")
        }
    }
    // Sources live under src/<name>/kotlin. AGP compiles src/<name>/java by default, so
    // each source set is pointed at the kotlin directory explicitly.
    sourceSets {
        listOf("main", "test", "github", "fdroid", "play").forEach { name ->
            findByName(name)?.kotlin?.directories?.add("src/$name/kotlin")
        }
    }
}

dependencies {
    implementation(project(":magisk-xposed-kit"))
    implementation(project(":magisk-xposed-kit:logging"))
    implementation(project(":smscode-core:contract"))
    implementation(project(":smscode-core:verification"))
    implementation(project(":smscode-core:hook"))
    implementation(project(":smscode-core:domain"))
    implementation(project(":smscode-core:rule"))
    implementation(project(":smscode-core:runtime"))
    implementation(project(":smscode-core:db"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)
    // Networking (needed by GithubUpdateChecker)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)
    // Database (Room)
    implementation(libs.timber)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.mockk)
}

val verifyNoComposeUiLeak = tasks.register("verifyNoComposeUiLeak") {
    group = "verification"
    description = "Ensure the runtime module does not pick up Compose UI dependencies."

    val sourceRoot = layout.projectDirectory.dir("src/main/kotlin")
    val projectRoot = layout.projectDirectory.asFile
    val bannedRegexes = listOf(
        Regex("""^\s*import\s+androidx\.compose\."""),
        Regex("""@\s*Composable\b"""),
        Regex("""\bMaterialTheme\b"""),
        Regex("""\bModifier\b"""),
    )

    inputs.dir(sourceRoot)

    doLast {
        val violations = sourceRoot
            .asFileTree
            .matching { include("**/*.kt") }
            .files
            .flatMap { source ->
                source.readLines().mapIndexedNotNull { index, line ->
                    if (bannedRegexes.any { regex -> regex.containsMatchIn(line) }) {
                        "${source.relativeTo(projectRoot)}:${index + 1}: ${line.trim()}"
                    } else {
                        null
                    }
                }
            }

        if (violations.isNotEmpty()) {
            error(
                buildString {
                    appendLine("Compose UI APIs are not allowed in the runtime module:")
                    violations.forEach { appendLine(it) }
                },
            )
        }
    }
}

tasks.named("check").configure {
    dependsOn(verifyNoComposeUiLeak)
}
