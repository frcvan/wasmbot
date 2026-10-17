package org.teamvan.wasmbot.build

import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault

/** Runs a bundled JavaScript harness against one compiled native image. */
@DisableCachingByDefault(because = 'Executes native image behavioral assertions on every invocation')
abstract class WasmHarnessTask extends DefaultTask {
    @Input
    abstract Property<String> getJavascriptRuntime()

    @Input
    abstract Property<String> getImageName()

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract RegularFileProperty getHarness()

    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract DirectoryProperty getNativeDirectory()

    @Internal
    abstract DirectoryProperty getWorkingDirectory()

    @Inject
    protected abstract ExecOperations getExecOperations()

    @TaskAction
    void runHarness() {
        List<String> command = [
            javascriptRuntime.get(),
            harness.get().asFile.absolutePath,
            nativeDirectory.get().asFile.absolutePath,
            imageName.get()
        ]
        File directory = workingDirectory.get().asFile
        execOperations.exec {
            workingDir(directory)
            commandLine(command)
        }.assertNormalExitValue()
    }
}
