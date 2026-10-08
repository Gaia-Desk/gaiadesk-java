// The GaiaDesk SDK for Java and Kotlin: net.gaiadesk:gaiadesk.
//
// Compiled for Java 11 (`--release 11`) by whatever JDK runs Gradle (17+).
// The tests run on that JDK, or on another given as -PtestJavaHome=<jdk>
// (CI runs them on 11, 17 and 21).
//
// Publishing to Maven Central is configured but never run by the build:
//   ./gradlew publishMavenPublicationToCentralRepository \
//     -PcentralUsername=… -PcentralPassword=… -PsigningKey="$(cat key.asc)" -PsigningPassword=…
// (the Central Portal's OSSRH-compatible staging endpoint; see RELEASING in README).

import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    `java-library`
    `maven-publish`
    signing
}

group = "net.gaiadesk"
version = "0.1.2"
description = "The GaiaDesk SDK for Java and Kotlin: drive GaiaDesk desks through the hosted GaiaDesk API, a desk's local API or its LAN gateway."

java {
    withSourcesJar()
    withJavadocJar()
}

repositories {
    mavenCentral()
}

dependencies {
    // JSON: results, error envelopes, sealed frames (Apache-2.0).
    api("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    // Nullability annotations Kotlin reads as platform-type-free signatures (Apache-2.0).
    api("org.jspecify:jspecify:1.0.0")

    testImplementation(platform("org.junit:junit-bom:5.10.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(11)
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.jar {
    manifest {
        attributes(
            "Automatic-Module-Name" to "net.gaiadesk",
            "Implementation-Title" to "gaiadesk",
            "Implementation-Version" to project.version,
        )
    }
}

tasks.javadoc {
    (options as StandardJavadocDocletOptions).apply {
        encoding = "UTF-8"
        addBooleanOption("Xdoclint:all,-missing", true)
        addStringOption("Xmaxwarns", "1000")
        links("https://docs.oracle.com/en/java/javase/11/docs/api/")
        noTimestamp(true)
        windowTitle = "GaiaDesk SDK for Java ${project.version}"
    }
    isFailOnError = true
}

tasks.test {
    useJUnitPlatform()
    (findProperty("testJavaHome") as String?)?.let { home ->
        val exe = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
        executable = file("$home/bin/$exe").absolutePath
    }
    testLogging {
        events("failed", "skipped")
        exceptionFormat = TestExceptionFormat.FULL
    }
}

// Examples compile against the library (they are not part of the artifact).
val examples: SourceSet by sourceSets.creating {
    java.srcDir("examples")
    compileClasspath += sourceSets.main.get().output + configurations.runtimeClasspath.get()
}
tasks.named("check") { dependsOn(examples.classesTaskName) }

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "gaiadesk"
            from(components["java"])
            pom {
                name.set("GaiaDesk SDK for Java")
                description.set(project.description)
                url.set("https://github.com/Gaia-Desk/gaiadesk-java")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                developers {
                    developer {
                        id.set("gaiadesk")
                        name.set("GaiaDesk")
                        email.set("noreply@gaiadesk.net")
                    }
                }
                scm {
                    url.set("https://github.com/Gaia-Desk/gaiadesk-java")
                    connection.set("scm:git:https://github.com/Gaia-Desk/gaiadesk-java.git")
                    developerConnection.set("scm:git:ssh://git@github.com/Gaia-Desk/gaiadesk-java.git")
                }
            }
        }
    }
    repositories {
        maven {
            // Maven Central through the Central Portal's OSSRH-compatible staging API.
            name = "central"
            url = uri("https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/")
            credentials {
                username = findProperty("centralUsername") as String? ?: System.getenv("CENTRAL_USERNAME")
                password = findProperty("centralPassword") as String? ?: System.getenv("CENTRAL_PASSWORD")
            }
        }
        maven {
            // A local directory, to inspect what would be published: publishMavenPublicationToStagingRepository.
            name = "staging"
            url = uri(layout.buildDirectory.dir("staging-repo"))
        }
    }
}

signing {
    val key = findProperty("signingKey") as String? ?: System.getenv("SIGNING_KEY")
    val password = findProperty("signingPassword") as String? ?: System.getenv("SIGNING_PASSWORD")
    if (key != null) useInMemoryPgpKeys(key, password)
    // Central refuses unsigned artifacts: signing is required for it, optional elsewhere.
    setRequired({ gradle.taskGraph.allTasks.any { it.name.contains("ToCentralRepository") } })
    sign(publishing.publications["maven"])
}
