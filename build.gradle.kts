plugins {
    id("java")
    id("com.gradleup.shadow") version "9.3.2"
}

group = "com.rheinmetal"
version = "v1.0.1"

repositories {
    mavenCentral()
}

dependencies {
    implementation(files("libs/org.argeo.jjml-2.1.2.0006-7f18908.jar"))
    implementation("io.javalin:javalin:5.6.3")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.slf4j:slf4j-api:2.0.9")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.9")
    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.shadowJar {
    // exclude("com/google/gson/**")
    mergeServiceFiles()
    archiveBaseName.set("JavaLlamaServer")
    archiveClassifier.set("all")
    manifest {
        attributes["Main-Class"] = "com.javallamaserver.core.ServerApp"
    }
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
