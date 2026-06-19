plugins {
    application
}

java {
    toolchain {
        // Java 17 = installierte Runtime (PC) und Termux/OpenJDK-17 (Handy). Reines java.net
        // (DatagramSocket/MulticastSocket) braucht nichts Neueres; Bytecode laeuft portabel.
        languageVersion = JavaLanguageVersion.of(17)
    }
}

repositories {
    mavenCentral()
}

// Aufgabe 2 nutzt – wie Aufgabe 1 – bewusst KEINE externen Bibliotheken (nur JDK).

sourceSets {
    main {
        java.srcDirs("src")
    }
}

application {
    mainClass = "firework.RingNode"
}

// `./gradlew jar` -> build/libs/udp-fireworks-distributed.jar. Main-Class im Manifest, damit auf
// dem Handy sowohl `java -jar udp-fireworks-distributed.jar <args>` als auch
// `java -cp udp-fireworks-distributed.jar firework.RingNode <args>` funktioniert.
tasks.jar {
    manifest {
        attributes["Main-Class"] = "firework.RingNode"
    }
}
