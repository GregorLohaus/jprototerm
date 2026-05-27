plugins {
    application
    id("org.openjfx.javafxplugin") version "0.1.0"
    id("com.gluonhq.gluonfx-gradle-plugin") version "1.0.28"
}

group = "com.gregor"
version = "0.1.0"

dependencies {
    implementation("dev.jlibghostty:jlibghostty:0.1.0-SNAPSHOT")
    implementation("io.github.wasabithumb:jtoml:1.5.2")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

application {
    mainClass.set("com.gregor.jprototerm.Main")
}

javafx {
    version = "25"
    modules = listOf("javafx.controls", "javafx.graphics")
}

gluonfx {
    mainClassName = "com.gregor.jprototerm.Main"
}
