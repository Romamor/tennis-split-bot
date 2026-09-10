plugins {
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.serialization") version "2.2.21"
    application
}

repositories { mavenCentral() }

kotlin { jvmToolchain(21) }

dependencies {
    implementation("org.xerial:sqlite-jdbc:3.53.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    runtimeOnly("org.slf4j:slf4j-nop:2.0.17")
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")
}

application { mainClass.set("ru.movereon.tennis.DemoKt") }

tasks.test {
    useJUnitPlatform { excludeTags("storage-simulation") }
    testLogging { events("failed", "skipped") }
}

tasks.register<Test>("storageSimulation") {
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("storage-simulation") }
    systemProperty("storage.dir", providers.gradleProperty("storageDir").getOrElse("build/storage-simulation"))
    systemProperty("storage.trainings", providers.gradleProperty("storageTrainings").getOrElse("24"))
    systemProperty("storage.members", providers.gradleProperty("storageMembers").getOrElse("27"))
    systemProperty("storage.players", providers.gradleProperty("storagePlayers").getOrElse("8,16,27"))
    outputs.upToDateWhen { false }
}
