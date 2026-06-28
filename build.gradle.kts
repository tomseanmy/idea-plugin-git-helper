import org.jetbrains.intellij.platform.gradle.TestFrameworkType

// ---------- Plugins ----------
plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.2.20"
    id("org.jetbrains.intellij.platform") version "2.12.0"
}

// ---------- Metadata ----------
group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

// ---------- Repositories ----------
repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// ---------- Dependencies ----------
dependencies {
    intellijPlatform {
        // Target: IntelliJ IDEA 2025.3 (Community line merged into a single product in 2025.3)
        intellijIdea(providers.gradleProperty("platformVersion"))
        // Bundled plugin we depend on (Git support ships with IDEA)
        bundledPlugin("Git4Idea")
        // Tooling
        pluginVerifier()
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
}

// ---------- IntelliJ Platform configuration ----------
intellijPlatform {
    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = providers.gradleProperty("pluginUntilBuild")
        }
    }
    buildSearchableOptions = false
    pluginVerification {
        ides {
            recommended()
        }
    }
}

// ---------- Kotlin / Java ----------
kotlin {
    jvmToolchain(21)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
        options.encoding = "UTF-8"
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
            freeCompilerArgs.add("-Xjsr305=strict")
        }
    }
    test {
        useJUnit()
    }
}
