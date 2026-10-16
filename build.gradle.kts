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

    implementation("io.modelcontextprotocol.sdk:mcp:2.0.1")
    implementation("org.eclipse.jetty:jetty-server:12.0.40")
    implementation("org.eclipse.jetty.ee10:jetty-ee10-servlet:12.0.40")
}

intellijPlatform {
    // 关闭 searchable options 生成（构建提速）；必须用扩展属性关闭，
    // 若只 disable buildSearchableOptions 任务，下游 prepareJarSearchableOptions 的 onlyIf 仍读到 true 而报输入目录缺失
    buildSearchableOptions = false

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
