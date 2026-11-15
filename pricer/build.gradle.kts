plugins {
    java
    alias(libs.plugins.spotless)
}

group = "com.weatherderivatives"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.test {
    useJUnitPlatform()
}

spotless {
    lineEndings = com.diffplug.spotless.LineEnding.UNIX
    java {
        googleJavaFormat(libs.versions.googleJavaFormat.get())
        target("src/**/*.java")
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint()
    }
}
