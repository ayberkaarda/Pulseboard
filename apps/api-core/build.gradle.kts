plugins {
    java
    checkstyle
    id("org.springframework.boot") version "4.1.2"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "io.pulseboard"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

checkstyle {
    toolVersion = "14.4.0"
    configFile = file("config/checkstyle/checkstyle.xml")
    isIgnoreFailures = false
    maxWarnings = 0
}

val mapstructVersion = "1.6.3"
val springdocVersion = "3.2.0"
val archunitVersion = "1.5.1"
val bouncycastleVersion = "1.86"
val jsonSchemaValidatorVersion = "3.0.9"
val wiremockVersion = "3.13.2"

dependencies {
    // Web (Spring MVC) and validation
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // Security with JWT (Nimbus JOSE via spring-security-oauth2-jose)
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    // Argon2id implementation used by Spring Security's Argon2PasswordEncoder
    runtimeOnly("org.bouncycastle:bcprov-jdk18on:$bouncycastleVersion")

    // Persistence: JPA (Hibernate), Flyway, PostgreSQL
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // Redis
    implementation("org.springframework.boot:spring-boot-starter-data-redis")

    // JSON Schema validation of ingestion batches (contracts/events/metric-batch.schema.json)
    implementation("com.networknt:json-schema-validator:$jsonSchemaValidatorVersion")

    // Webhook delivery: the connection is made to an address that was validated beforehand (see alert.webhook)
    implementation("org.apache.httpcomponents.client5:httpclient5")

    // API documentation
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:$springdocVersion")

    // Observability
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

    // DTO mapping
    implementation("org.mapstruct:mapstruct:$mapstructVersion")
    annotationProcessor("org.mapstruct:mapstruct-processor:$mapstructVersion")

    // Tests
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-resttestclient")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("com.tngtech.archunit:archunit-junit5:$archunitVersion")
    testImplementation("org.wiremock:wiremock-standalone:$wiremockVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(
        listOf(
            "-parameters",
            "-Amapstruct.defaultComponentModel=spring",
            "-Amapstruct.unmappedTargetPolicy=ERROR",
        ),
    )
}

// Contracts shared with the other components. The tests compare the generated OpenAPI document and the bundled
// ingestion schema with these files, so a drift fails the build.
val contractsDir = layout.projectDirectory.dir("../../contracts")
val openApiContract = contractsDir.file("api-core.openapi.yaml")
val metricBatchSchemaContract = contractsDir.file("events/metric-batch.schema.json")
val anomalyDetectedSchemaContract = contractsDir.file("events/anomaly-detected.schema.json")

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty("spring.profiles.active", "test")
    systemProperty("pulseboard.contracts.openapi", openApiContract.asFile.absolutePath)
    systemProperty("pulseboard.contracts.metric-batch-schema", metricBatchSchemaContract.asFile.absolutePath)
    systemProperty("pulseboard.contracts.anomaly-detected-schema", anomalyDetectedSchemaContract.asFile.absolutePath)
    inputs.files(openApiContract, metricBatchSchemaContract, anomalyDetectedSchemaContract).withPathSensitivity(PathSensitivity.NONE)
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// No default profile is baked into the application, so an unconfigured deployment never runs with the
// developer-oriented `local` settings. `bootRun` selects `local` unless SPRING_PROFILES_ACTIVE says otherwise.
tasks.bootRun {
    systemProperty("spring.profiles.active", System.getenv("SPRING_PROFILES_ACTIVE") ?: "local")
}

tasks.bootJar {
    archiveFileName = "api-core.jar"
}

tasks.jar {
    enabled = false
}

// Regenerates contracts/api-core.openapi.yaml from the running application (springdoc on a Testcontainers backed
// context). The regular test suite fails when the checked-in file differs from what this task writes.
tasks.register<Test>("generateOpenApiDocs") {
    description = "Writes the springdoc OpenAPI document to contracts/api-core.openapi.yaml."
    group = "documentation"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter {
        includeTestsMatching("io.pulseboard.core.api.OpenApiContractTest")
    }
    systemProperty("pulseboard.contracts.openapi.write", "true")
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}

// Fails when contracts/api-core.openapi.yaml differs from the document the application serves.
tasks.register<Test>("verifyOpenApiDocs") {
    description = "Checks that contracts/api-core.openapi.yaml matches the springdoc OpenAPI document."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter {
        includeTestsMatching("io.pulseboard.core.api.OpenApiContractTest")
    }
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}
