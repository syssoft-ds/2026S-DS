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
    // sim4da-Simulator (In-Process); aus dem pingpong-Starter uebernommen.
    implementation(files("lib/sim4da.jar"))
}

sourceSets {
    main {
        java.srcDirs("src")
    }
}

application {
    mainClass = "firework.RingSimulation"
}

tasks.named<JavaExec>("run") {
    // CLI-Args weiterreichen: ./gradlew run --args="4 0.5 3"
    standardInput = System.`in`
}
