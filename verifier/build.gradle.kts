plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
    jacoco
}

group = "org.vdcapps.verifier"
version = "0.1.0"

application {
    mainClass.set("org.vdcapps.verifier.ApplicationKt")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

tasks.withType<JavaCompile>().configureEach {
    sourceCompatibility = "21"
    targetCompatibility = "21"
}

val ktorVersion = "3.4.1"
val micrometerVersion = "1.13.9"
val multipazVersion = "0.97.1"
val kotlinxSerializationVersion = "1.7.3"
val kotlinxCoroutinesVersion = "1.10.2"

dependencies {
    // Multipaz: mdoc verification utilities
    implementation("org.multipaz:multipaz:$multipazVersion")

    // Ktor server
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-freemarker:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging:$ktorVersion")
    implementation("io.ktor:ktor-server-call-id:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")

    // Kotlinx
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinxSerializationVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinxCoroutinesVersion")

    // QR code generation for OID4VP request page
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.google.zxing:javase:3.5.3")

    // Metrics (Micrometer + Prometheus)
    implementation("io.ktor:ktor-server-metrics-micrometer:$ktorVersion")
    implementation("io.micrometer:micrometer-registry-prometheus:$micrometerVersion")

    // Redis client
    implementation("redis.clients:jedis:5.2.0")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.5.12")
    implementation("net.logstash.logback:logstash-logback-encoder:9.0")
    // logback.xml の <if> 条件分岐に必要
    runtimeOnly("org.codehaus.janino:janino:3.1.12")

    // Testing
    testImplementation(kotlin("test-junit5"))
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("io.mockk:mockk:1.14.9")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$kotlinxCoroutinesVersion")
    testImplementation("org.bouncycastle:bcpkix-jdk18on:1.83")
    testImplementation("org.bouncycastle:bcprov-jdk18on:1.83")
    testImplementation("org.multipaz:multipaz-doctypes:$multipazVersion")
}

tasks.test {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}
