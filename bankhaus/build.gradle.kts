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
    // Aufgabe 1 + 2: ein Lauf mit vollstaendiger Ausgabe des globalen Zustands.
    mainClass = "bankhaus.BankhausSimulation"
}

tasks.named<JavaExec>("run") {
    // ./gradlew run --args="8"   -> 8 Kontoprozesse
    standardInput = System.`in`
}

// Aufgabe 3: Messreihe ueber n und Ueberweisungsfrequenz, schreibt results/schnappschuesse.csv
tasks.register<JavaExec>("experiments") {
    group = "application"
    description = "Konsistenz-Experimente (Aufgabe 3)"
    mainClass = "bankhaus.ConsistencyExperiments"
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = projectDir
}
