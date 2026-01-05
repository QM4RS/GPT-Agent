plugins {
    java
    application
    id("org.openjfx.javafxplugin") version "0.1.0"
    id("com.gradleup.shadow") version "9.3.0"
}

group = "com.QM4RS"
version = "0.0.1"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(24))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.github.mkpaz:atlantafx-base:2.1.0")
    implementation("com.openai:openai-java:4.13.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    implementation("org.fxmisc.richtext:richtextfx:0.11.2")

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

application {
    mainClass.set("com.QM4RS.agent.Launcher")
}

javafx {
    version = "21.0.4"
    modules("javafx.controls", "javafx.graphics", "javafx.web")
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveClassifier.set("win-x64")
    mergeServiceFiles()

    manifest {
        attributes(
            mapOf(
                "Main-Class" to application.mainClass.get()
            )
        )
    }
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.test {
    useJUnitPlatform()
}
