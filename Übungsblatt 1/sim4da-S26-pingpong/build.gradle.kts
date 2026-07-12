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
    mainClass = "fireworks.FireworksKonsistenz"
}

tasks.named<JavaExec>("run") {
    // Forward CLI args: ./gradlew run --args="20"
    standardInput = System.`in`
}
