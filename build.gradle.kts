plugins {
    id("java")
    id("com.gradleup.shadow") version "9.4.2"
}

group = "com.baymc.whitelist"

val baseVersion = "1.0.0-SNAPSHOT"
val gitCommitShort = providers.gradleProperty("gitCommitShort").orElse("unknown")

version = providers.gradleProperty("artifactVersionOverride").orElse(baseVersion).get()

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.70-stable")

    implementation("com.zaxxer:HikariCP:7.1.0")
    implementation("com.mysql:mysql-connector-j:9.7.0")

    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.withType<Jar> {
    archiveBaseName.set("BayMcWhiteList")
    manifest {
        attributes(
            "Implementation-Title" to "BayMcWhiteList",
            "Implementation-Version" to project.version,
            "Git-Commit-Short" to gitCommitShort.get(),
        )
    }
}

tasks.processResources {
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.shadowJar {
    archiveClassifier.set("")
    mergeServiceFiles()
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.test {
    useJUnitPlatform()
}

tasks.register("printVersion") {
    group = "versioning"
    description = "Prints the base project version used for development release tags."
    doLast {
        println(baseVersion)
    }
}
