plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

group = "org.vdcapps.issuer"
version = "0.1.0"

application {
    mainClass.set("org.vdcapps.issuer.ApplicationKt")
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

val ktorVersion = "3.0.3"
val multipazVersion = "0.97.0"
val kotlinxSerializationVersion = "1.7.3"
val kotlinxDatetimeVersion = "0.6.1"
val kotlinxCoroutinesVersion = "1.9.0"

dependencies {
    // Multipaz: credential building and mdoc utilities
    implementation("org.multipaz:multipaz:$multipazVersion")
    implementation("org.multipaz:multipaz-doctypes:$multipazVersion")

    // Ktor server
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-auth:$ktorVersion")
    implementation("io.ktor:ktor-server-sessions:$ktorVersion")
    implementation("io.ktor:ktor-server-freemarker:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging:$ktorVersion")
    implementation("io.ktor:ktor-server-call-id:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-server-request-validation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")

    // Ktor client (Entra ID OAuth token exchange + MS Graph API)
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-client-auth:$ktorVersion")

    // Kotlinx
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinxSerializationVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:$kotlinxDatetimeVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinxCoroutinesVersion")

    // JWT handling for OID4VCI proof validation (holder proof-of-possession)
    implementation("com.nimbusds:nimbus-jose-jwt:9.47")

    // X.509 certificate generation for issuer signing key
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    // QR code generation for credential offer page
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.google.zxing:javase:3.5.3")

    // Redis client
    implementation("redis.clients:jedis:5.2.0")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.5.12")
    implementation("net.logstash.logback:logstash-logback-encoder:8.0")
    // logback.xml の <if> 条件分岐に必要
    runtimeOnly("org.codehaus.janino:janino:3.1.12")

    // Testing
    testImplementation(kotlin("test-junit5"))
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("io.mockk:mockk:1.13.12")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$kotlinxCoroutinesVersion")
}

tasks.test {
    useJUnitPlatform()
}
