pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven {
            url = uri(
                providers.gradleProperty("jlibghosttyMavenRepo")
                    .orElse("../jlibghostty/result/maven")
                    .get()
            )
        }
    }
}

rootProject.name = "jprototerm"
