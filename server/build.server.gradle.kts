// Server-only build config - standalone, no version catalog
// Uses the same versions as settings.gradle.kts libs.versions.toml for consistency
plugins {
    application
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "com.example.smarty.server"
version = "1.0.0"

application {
    mainClass.set("com.example.smarty.server.ApplicationKt")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// All dependency versions MUST match those in gradle/libs.versions.toml
// to avoid binary incompatibilities between transitive dependencies
val ktorVersion = "3.1.0"
val exposedVersion = "0.59.0"
val kotlinxSerializationVersion = "1.8.0"
val kotlinxCoroutinesVersion = "1.10.1"
val logbackVersion = "1.4.14"
val logstashVersion = "7.4"
val micrometerVersion = "1.12.2"
val postgresqlVersion = "42.7.1"
val hikaricpVersion = "5.1.0"
val firebaseAdminVersion = "9.4.3"
val pdfboxVersion = "3.0.1"

dependencies {
    implementation(project(":common"))

    // Ktor Server
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-sse:$ktorVersion")
    implementation("io.ktor:ktor-server-call-id:$ktorVersion")
    implementation("io.ktor:ktor-server-rate-limit:$ktorVersion")
    implementation("io.ktor:ktor-server-cors:$ktorVersion")
    implementation("io.ktor:ktor-server-auth:$ktorVersion")
    implementation("io.ktor:ktor-server-metrics-micrometer:$ktorVersion")

    // Ktor Client
    implementation("io.ktor:ktor-client-okhttp:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")

    // PDF Processing
    implementation("org.apache.pdfbox:pdfbox:$pdfboxVersion")

    // Kotlinx
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinxSerializationVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinxCoroutinesVersion")

    // Logging
    implementation("ch.qos.logback:logback-classic:$logbackVersion")
    implementation("net.logstash.logback:logstash-logback-encoder:$logstashVersion")

    // Metrics
    implementation("io.micrometer:micrometer-registry-prometheus:$micrometerVersion")

    // Database
    implementation("org.postgresql:postgresql:$postgresqlVersion")
    implementation("com.zaxxer:HikariCP:$hikaricpVersion")
    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")

    // Firebase Admin
    implementation("com.google.firebase:firebase-admin:$firebaseAdminVersion")

    // Testing
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveClassifier.set("all")
    manifest {
        attributes["Main-Class"] = "com.example.smarty.server.ApplicationKt"
    }
    mergeServiceFiles()
}

tasks.build {
    dependsOn(tasks.named("shadowJar"))
}
