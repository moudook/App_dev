plugins {
    application
    kotlin("jvm")
    alias(libs.plugins.kotlin.serialization)
}

group = "com.example.smarty.server"
version = "1.0.0"

application {
    mainClass.set("com.example.smarty.server.ApplicationKt")

    val isDevelopment: Boolean = project.ext.has("development")
    applicationDefaultJvmArgs = listOf("-Dio.ktor.development=$isDevelopment")
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

dependencies {
    // Shared protocol (AgentCommand, etc.)
    implementation(project(":common"))

    // Ktor Server
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.sse)
    implementation(libs.ktor.server.call.id)
    implementation(libs.ktor.server.rate.limit)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.call.logging)

    // Ktor Client (for external APIs)
    implementation(libs.ktor.client.okhttp)

    // PDF Processing
    implementation("org.apache.pdfbox:pdfbox:3.0.1")
    implementation("org.apache.pdfbox:pdfbox-tools:3.0.1")

    // KOOG Framework (for future agent hosting)
    implementation(libs.koog.agents)
    implementation(libs.koog.agents.planner)

    // Kotlinx
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    // Logging
    implementation(libs.logback.classic)
    implementation(libs.logstash.logback.encoder)

    // Metrics
    implementation(libs.micrometer.registry.prometheus)
    implementation(libs.ktor.server.metrics.micrometer)

    // Database (PostgreSQL + pgvector)
    implementation(libs.postgresql)
    implementation(libs.pgvector)
    implementation(libs.hikaricp)

    // Testing
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

// Fat JAR task for deployment
tasks.register<Jar>("fatJar") {
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = "com.example.smarty.server.ApplicationKt"
    }
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    with(tasks.jar.get())
}
