plugins {
    java
    alias(libs.plugins.spotless)
    alias(libs.plugins.spring.boot)
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
    implementation(platform(libs.spring.boot.bom))
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.hardwood.core)
    runtimeOnly(libs.snappy.java)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.duckdb.jdbc)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.test {
    useJUnitPlatform()
}

// Run the API from the repo root so the default pricer.data-dir "data/pricer" is the same
// folder data-prep publishes to (spec 006, decision 4).
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    workingDir = rootDir.parentFile
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
