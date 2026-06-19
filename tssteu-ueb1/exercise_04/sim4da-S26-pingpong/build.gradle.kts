plugins {
    application
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(files("lib/sim4da.jar"))
}

sourceSets {
    main {
        java.srcDirs("src")
    }
}

application {
    mainClass = "pingpong.PingPongSimulation"
}

tasks.named<JavaExec>("run") {
    // Forward CLI args: ./gradlew run --args="20"
    standardInput = System.`in`
}

tasks.register<JavaExec>("runTokenRing") {
    group = "application"
    description = "Startet die Token-Ring-Simulation"

    // Pfad zu deiner Hauptklasse
    mainClass.set("tokenring.TokenRingSimulation")

    // Nutzt den Runtime-Classpath inklusive sim4da.jar
    classpath = sourceSets["main"].runtimeClasspath

    // Ermöglicht es, Argumente via --args zu übergeben
    if (project.hasProperty("args")) {
        args(project.property("args").toString().split(" "))
    }
}