package com.rheinmetal.compat

import groovy.json.JsonOutput
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile

abstract class CompatMatrixTask extends DefaultTask {
    @InputFile
    abstract RegularFileProperty getUniversalJar()

    @Input
    abstract ListProperty<String> getTargets()

    @Input
    abstract MapProperty<String, String> getTargetDescriptors()

    @Input
    abstract MapProperty<String, List<String>> getRequiredEntries()

    @OutputDirectory
    abstract DirectoryProperty getStagingDirectory()

    @TaskAction
    void verify() {
        def stagingDir = stagingDirectory.get().asFile
        stagingDir.mkdirs()

        def jarFile = universalJar.get().asFile
        if (!jarFile.exists()) {
            throw new IllegalStateException("Missing jar: ${jarFile}")
        }

        def zip = new ZipFile(jarFile)
        try {
            def index = [:]
            targets.get().each { target ->
                def missingEntries = requiredEntries.get().getOrDefault(target, List.of())
                        .findAll { zip.getEntry(it) == null }
                if (!missingEntries.isEmpty()) {
                    throw new IllegalStateException("${jarFile.name} is missing entries for ${target}: ${missingEntries.join(', ')}")
                }

                def targetDir = new File(stagingDir, target)
                if (targetDir.exists() && !targetDir.deleteDir()) {
                    throw new IllegalStateException("Failed to clean staged target directory: ${targetDir}")
                }
                def modsDir = new File(targetDir, "mods")
                modsDir.mkdirs()

                def stagedJar = new File(modsDir, jarFile.name)
                Files.copy(jarFile.toPath(), stagedJar.toPath(), StandardCopyOption.REPLACE_EXISTING)

                def descriptor = targetDescriptors.get().getOrDefault(target, "{}")
                new File(targetDir, "target.json").text = descriptor + System.lineSeparator()
                index[target] = new groovy.json.JsonSlurper().parseText(descriptor)
            }

            new File(stagingDir, "index.json").text = JsonOutput.prettyPrint(JsonOutput.toJson(index)) + System.lineSeparator()
        } finally {
            zip.close()
        }
    }
}
