package org.teamvan.wasmbot.plugin

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Writes the deploy-relative names of the files bundled as native-image resources.
 *
 * <p>The WASM runtime cannot enumerate classpath resources, so it reads this index at startup to
 * copy each bundled deploy file into the in-memory file system the robot's file APIs use.
 */
@CacheableTask
abstract class GenerateDeployIndex extends DefaultTask {
    @Internal
    abstract DirectoryProperty getDeployDirectory()

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract ConfigurableFileCollection getDeployFiles()

    @OutputFile
    abstract RegularFileProperty getIndexFile()

    @TaskAction
    void generate() {
        Path root = deployDirectory.get().asFile.toPath()
        List<String> names = deployFiles.files.collect { file ->
            root.relativize(file.toPath()).toString().replace(File.separator, '/')
        }
        names.sort()
        File index = indexFile.get().asFile
        index.parentFile.mkdirs()
        index.setText(names.isEmpty() ? '' : names.join('\n') + '\n', StandardCharsets.UTF_8.name())
    }
}
