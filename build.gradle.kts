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

// Experimental algorithms are absent from the bot's runtime and distribution.
val settlementBenchmark by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
configurations[settlementBenchmark.implementationConfigurationName].extendsFrom(configurations.implementation.get())
configurations[settlementBenchmark.runtimeOnlyConfigurationName].extendsFrom(configurations.runtimeOnly.get())
val solverPlatform = when {
    System.getProperty("os.name").startsWith("Mac") -> "darwin"
    System.getProperty("os.name").startsWith("Windows") -> "win32"
    else -> "linux"
} + "-" + if (System.getProperty("os.arch") in setOf("aarch64", "arm64")) "aarch64" else "x86-64"
dependencies {
    add(settlementBenchmark.implementationConfigurationName, "com.google.ortools:ortools-java:9.15.6755") {
        for (platform in listOf("linux-x86-64", "linux-aarch64", "darwin-x86-64", "darwin-aarch64", "win32-x86-64"))
            exclude(group = "com.google.ortools", module = "ortools-$platform")
    }
    add(settlementBenchmark.runtimeOnlyConfigurationName, "com.google.ortools:ortools-$solverPlatform:9.15.6755")
}
tasks.register<JavaExec>("settlementBenchmark") {
    dependsOn(settlementBenchmark.classesTaskName)
    classpath = settlementBenchmark.runtimeClasspath
    mainClass.set("ru.movereon.tennis.experiment.SettlementBenchmark")
    maxHeapSize = "96m"
    doFirst {
        val target = layout.buildDirectory.file("settlement-benchmark/classpath.txt").get().asFile
        target.parentFile.mkdirs()
        target.writeText(classpath.asPath)
    }
    args(providers.gradleProperty("benchmarkMode").getOrElse("all"), providers.gradleProperty("benchmarkDir").getOrElse("build/settlement-benchmark"))
}
