plugins {
    id("java")
    id("com.gradleup.shadow") version "9.4.2"
}

group = "com.baymc.whitelist"

// 本地构建和开发版移动标签使用的基础版本号
val baseVersion = "1.0.0-SNAPSHOT"

// 持续集成会传入该值, 让构建出的插件包在清单中记录来源提交
val gitCommitShort = providers.gradleProperty("gitCommitShort").orElse("unknown")
val artifactVersion = providers.gradleProperty("artifactVersionOverride").orElse(baseVersion).get()

// 本地构建使用基础版本; 自动构建只覆盖构建产物版本号
version = artifactVersion

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.70-stable")

    implementation("com.zaxxer:HikariCP:7.1.0")
    implementation("com.mysql:mysql-connector-j:9.7.0")
    implementation("org.xerial:sqlite-jdbc:3.53.2.0")
    implementation("org.bstats:bstats-bukkit:3.2.1")
    implementation("com.google.code.gson:gson:2.13.2")

    testImplementation("io.papermc.paper:paper-api:26.1.2.build.70-stable")
    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.mockito:mockito-core:5.20.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.20.0")
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

tasks.withType<Javadoc> {
    (options as org.gradle.external.javadoc.StandardJavadocDocletOptions)
        .addBooleanOption("Xdoclint:all,-missing", true)
}

tasks.withType<Jar> {
    // 保持普通插件包和依赖打包插件包的基础文件名与工作流一致
    archiveBaseName.set("BayMcWhiteList")
    manifest {
        attributes(
            "Implementation-Title" to "BayMcWhiteList",
            "Implementation-Version" to artifactVersion,
            "Git-Commit-Short" to gitCommitShort.get(),
        )
    }
}

tasks.processResources {
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        // plugin.yml 写入与插件包文件名一致的版本号
        expand("version" to artifactVersion)
    }
}

tasks.shadowJar {
    // 可部署的插件产物是已经打包依赖的插件包
    archiveClassifier.set("")
    mergeServiceFiles()
    // 重定位 bStats 包名, 避免与其他同样使用 bStats 的插件冲突
    relocate("org.bstats", "com.baymc.whitelist.bstats")
    relocate("com.google.gson", "com.baymc.whitelist.libs.gson")
}

tasks.build {
    // 确保自动构建和本地构建都会生成可部署的插件包
    dependsOn(tasks.shadowJar)
}

tasks.test {
    useJUnitPlatform()
}

tasks.register("printVersion") {
    group = "versioning"
    description = "输出开发版发布标签使用的基础项目版本"
    doLast {
        // 工作流先读取该版本作为发布标签, 再为构建产物追加短提交后缀
        println(baseVersion)
    }
}
