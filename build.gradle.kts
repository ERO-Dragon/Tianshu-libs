import java.io.File

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

val nativeLibDir = layout.projectDirectory.dir("libs/jjml-all")
val generatedNativeDir = layout.buildDirectory.dir("generated/native-resources/windows-x86_64")

val generateNativeManifest = tasks.register("generateNativeManifest") {
    inputs.dir(nativeLibDir)
    outputs.dir(generatedNativeDir)
    doLast {
        val outDir = generatedNativeDir.get().asFile
        outDir.mkdirs()
        val dlls = nativeLibDir.asFile
            .listFiles()
            ?.filter { it.extension.equals("dll", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?: emptyList()
        File(outDir, "native-libs.txt").writeText(dlls.joinToString("\n") { it.name })
    }
}

tasks.shadowJar {
    dependsOn(generateNativeManifest)
    from("libs/jjml-all") {
        into("natives/windows-x86_64")
        include("*.dll")
        exclude("ggml-vulkan.dll")
    }
    from(generatedNativeDir) {
        into("natives/windows-x86_64")
        include("native-libs.txt")
    }
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
tasks.jar {
    enabled = false
}
