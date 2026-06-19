plugins {
    application
}

java {
    toolchain {
        // Java 17 = installierte Runtime, die das Launcher-Script via `java` aufruft.
        // Reines java.net (DatagramSocket/MulticastSocket) braucht nichts Neueres.
        languageVersion = JavaLanguageVersion.of(17)
    }
}

repositories {
    mavenCentral()
}

// Aufgabe 1 nutzt bewusst KEINE externen Bibliotheken (nur JDK).

sourceSets {
    main {
        java.srcDirs("src")
    }
}

application {
    mainClass = "firework.RingNode"
}
