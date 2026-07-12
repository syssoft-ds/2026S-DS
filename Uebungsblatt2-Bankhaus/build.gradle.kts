plugins {
    java
    application
}

repositories {
    mavenCentral()
}

dependencies {
    // sim4da-S26: single dependency-free JAR from the framework repo
    implementation(files("lib/sim4da.jar"))

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    // sim4da.jar is built for Java 25
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

application {
    mainClass = "bankhaus.Bankhaus"
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        showStandardStreams = true // show snapshot output during test runs
    }
}
