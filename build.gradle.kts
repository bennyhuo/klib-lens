import java.util.Properties
import java.io.FileInputStream

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.10"
    id("org.jetbrains.intellij.platform") version "2.14.0"
}

val localProperties = Properties()
val localPropertiesFile = project.rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    FileInputStream(localPropertiesFile).use { localProperties.load(it) }
}

group = "com.bennyhuo.kotlin.kliblens"
version = "1.1.1"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        val intellijLocalPath = localProperties.getProperty("intellij.localPath")
        if (!intellijLocalPath.isNullOrBlank() && file(intellijLocalPath).exists()) {
            local(intellijLocalPath)
        } else {
            val intellijVersion = localProperties.getProperty("intellij.version") ?: "2026.1"
            intellijIdea(intellijVersion)
        }
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.kotlin")
    }
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(17)
}

intellijPlatform {
    sandboxContainer = layout.buildDirectory.dir("idea-sandbox")
    pluginConfiguration {
        id = "com.bennyhuo.kotlin.kliblens"
        name = "Klib Lens"
        vendor {
            name = "Benny Huo"
        }
        ideaVersion {
            sinceBuild = "251"
            untilBuild = "262.*"
        }
        
        changeNotes = """
            <p>Initial Release 1.0.0</p>
            <ul>
                <li>Beautify Klib/Knm decompiled output by stripping redundant fully qualified names and compiler comments.</li>
                <li>Correctly reconstruct annotation values and Kotlin standard type imports.</li>
                <li>Clean up redundant modifiers (<code>public</code>, <code>final</code>, etc.) for a cleaner structural view.</li>
                <li>Support K1 and K2 Kotlin compiler modes.</li>
            </ul>
        """.trimIndent()
    }
    
    pluginVerification { 
        ides { 
            recommended()
        }
    }
    
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        channels = listOf("default") 
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")

        if (!providers.environmentVariable("CERTIFICATE_CHAIN").isPresent) {
            val certFile = project.layout.projectDirectory.file("certs/certificate.pem")
            if (certFile.asFile.exists()) {
                certificateChainFile = certFile
            }
        }
        if (!providers.environmentVariable("PRIVATE_KEY").isPresent) {
            val keyFile = project.layout.projectDirectory.file("certs/private.pem")
            if (keyFile.asFile.exists()) {
                privateKeyFile = keyFile
            }
        }
    }
}
