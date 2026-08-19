pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        maven {
            name = "githubPackages"
            url = uri("https://maven.pkg.github.com/revanced/revanced-patches-gradle-plugin")
            credentials(PasswordCredentials::class)
        }
    }
}

plugins {
    id("app.revanced.patches") version "1.0.0-dev.10"
}

settings {
    extensions {
        defaultNamespace = "app.revanced.extension"
    }
}

rootProject.name = "expose-like-status-in-mediasession"