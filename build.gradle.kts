plugins {
    id("java")
    id("org.jetbrains.intellij.platform")
}

group = "com.ysh.studiotool"
version = "1.0.0"

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    intellijPlatform {
        intellijIdea("2025.3")

        // Add plugin dependencies for compilation here:

        bundledPlugin("com.intellij.java")
        bundledPlugin("com.intellij.modules.json")
        bundledPlugin("org.jetbrains.plugins.gradle")
    }

//    implementation("org.freemarker:freemarker:2.3.32")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "253.28294"
        }

        changeNotes = """
            changeNotes XXX
        """.trimIndent()
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }
}

tasks.buildSearchableOptions {
    enabled = false
}
