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
    // Publishing to JetBrains Marketplace. The token is read from the
    // PUBLISH_TOKEN env var (set in GitHub Actions secrets); it is never
    // hard-coded. `publishPlugin` signs then uploads the built zip.
    publishing {
        // The upload token for JetBrains Marketplace. Read from the PUBLISH_TOKEN
        // env var (GitHub Actions secret) or a Gradle property; never hard-coded.
        token = providers.environmentVariable("PUBLISH_TOKEN")
            .orElse(providers.gradleProperty("publishToken"))
    }
    // Plugin signing. The signing certificate is issued by JetBrains AFTER your
    // first plugin upload is approved — so it is NOT available on the first
    // release. We therefore make signing optional: the providers resolve to the
    // env/gradle values when present, and the signPlugin task no-ops when they
    // are absent. (Unsigned plugins still upload fine, just with an IDE warning.)
    signing {
        certificateChain = providers.environmentVariable("SIGNING_CERT_CHAIN")
            .orElse(providers.gradleProperty("signingCertificateChain"))
        privateKey = providers.environmentVariable("SIGNING_PRIVATE_KEY")
            .orElse(providers.gradleProperty("signingPrivateKey"))
        password = providers.environmentVariable("SIGNING_PASSWORD")
            .orElse(providers.gradleProperty("signingPassword"))
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
