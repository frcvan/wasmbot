package org.teamvan.wasmbot.plugin

import groovy.json.JsonSlurper
import org.graalvm.buildtools.gradle.dsl.GraalVMExtension
import org.graalvm.buildtools.gradle.tasks.BuildNativeImageTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.compile.JavaCompile

/** Source-bundled WASM support that never adds runtime shadows to desktop source sets. */
class WasmbotBuildPlugin implements Plugin<Project> {
    @Override
    void apply(Project project) {
        project.pluginManager.apply('java')
        project.pluginManager.apply('org.graalvm.buildtools.native')

        def extension = project.extensions.create('wasmbot', WasmbotExtension)
        def prepare = project.tasks.register('prepareWasmLibrary', PrepareWasmLibrary) {
            group = 'WASM'
            description = 'Extracts the plugin-bundled runtime, smoke sources, frontend, and tools.'
            outputDirectory.convention(project.layout.buildDirectory.dir('wasm/library'))
        }

        SourceSetContainer sourceSets = project.extensions.getByType(SourceSetContainer)
        SourceSet main = sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME)
        boolean hasAdvantageKit = project.fileTree('vendordeps') { include '*.json' }.files.any { file ->
            def vendor = new JsonSlurper().parse(file)
            vendor.javaDependencies?.any {
                it.groupId == 'org.littletonrobotics.akit' && it.artifactId == 'akit-java'
            }
        }
        // Only an installed vendordep enables support; use its resolved consumer version.
        def advantageKitVersion = project.providers.provider {
            if (!hasAdvantageKit) return null
            project.configurations.getByName(main.compileClasspathConfigurationName)
                    .incoming.resolutionResult.allComponents.find {
                        it.moduleVersion?.group == 'org.littletonrobotics.akit' &&
                                it.moduleVersion?.name == 'akit-java'
                    }?.moduleVersion?.version
        }
        SourceSet runtime = sourceSets.create('wasmRuntime') {
            java.setSrcDirs([prepare.flatMap { it.outputDirectory.dir('runtime/java') }])
            resources.setSrcDirs([prepare.flatMap { it.outputDirectory.dir('runtime/resources') }])
        }
        if (hasAdvantageKit) {
            runtime.java.srcDir(prepare.flatMap { it.outputDirectory.dir('advantagekit/java') })
        }
        // Compile bundled shadows independently: robot classes cannot mask the library's own types.
        runtime.compileClasspath = main.compileClasspath
        runtime.runtimeClasspath = runtime.output + main.runtimeClasspath

        SourceSet wasm = sourceSets.create('wasm') {
            java.setSrcDirs(['src/wasm/java'])
        }
        wasm.compileClasspath = runtime.output + main.output + main.compileClasspath + wasm.compileClasspath
        // Consumer WASM overrides win over bundled shadows, which win over desktop/vendor types.
        wasm.runtimeClasspath = wasm.output + runtime.output + main.output + main.runtimeClasspath +
                project.configurations.getByName(wasm.runtimeClasspathConfigurationName)

        SourceSet smoke = sourceSets.create('wasmTest') {
            java.setSrcDirs([prepare.flatMap { it.outputDirectory.dir('tests/java') }])
        }
        smoke.compileClasspath = wasm.output + runtime.output + main.output + main.compileClasspath +
                smoke.compileClasspath
        smoke.runtimeClasspath = smoke.output + wasm.runtimeClasspath +
                project.configurations.getByName(smoke.runtimeClasspathConfigurationName)
        if (hasAdvantageKit) {
            [main, wasm, smoke].each { source ->
                project.configurations.getByName(source.annotationProcessorConfigurationName).dependencies.addAllLater(
                        advantageKitVersion.map { version ->
                            [project.dependencies.create("org.littletonrobotics.akit:akit-autolog:${version}")]
                        }.orElse([]))
            }
        }

        project.tasks.named(runtime.compileJavaTaskName) { dependsOn(prepare) }
        project.tasks.named(runtime.processResourcesTaskName) { dependsOn(prepare) }
        project.tasks.named(smoke.compileJavaTaskName) { dependsOn(prepare) }
        project.tasks.named(wasm.processResourcesTaskName) {
            from(project.layout.projectDirectory.dir('src/main/deploy')) {
                into 'wasmbot/deploy'
            }
        }
        project.tasks.withType(JavaCompile).configureEach {
            options.compilerArgs.addAll(['-parameters', '-XDstringConcat=inline'])
        }

        GraalVMExtension nativeExtension = project.extensions.getByType(GraalVMExtension)
        nativeExtension.binaries.named('main') {
            imageName.set(extension.imageName)
            mainClass.set(extension.mainClass)
            classpath.setFrom(wasm.runtimeClasspath)
            buildArgs.addAll('--tool:svm-wasm', '-H:+UnlockExperimentalVMOptions', '-H:-AutoRunVM')
        }
        nativeExtension.binaries.create('smoke') {
            imageName.set(extension.imageName)
            mainClass.set('org.teamvan.wasmbot.testing.WasmSmoke')
            classpath.setFrom(smoke.runtimeClasspath)
            buildArgs.addAll('--tool:svm-wasm', '-H:+UnlockExperimentalVMOptions', '-H:-AutoRunVM', '-H:+DebugNames')
        }

        def nativeCompile = project.tasks.named('nativeCompile', BuildNativeImageTask)
        def nativeSmokeCompile = project.tasks.named('nativeSmokeCompile', BuildNativeImageTask)
        if (hasAdvantageKit) {
            def reflectionFile = project.layout.buildDirectory.file('wasm/reflection/main/reflect-config.json')
            def smokeReflectionFile = project.layout.buildDirectory.file('wasm/reflection/smoke/reflect-config.json')
            def reflection = project.tasks.register('generateWasmAutoLogOutputReflection', GenerateAutoLogOutputReflection) {
                group = 'WASM'
                description = 'Generates reflection metadata for annotated outputs and object discovery.'
                classpath.from(wasm.runtimeClasspath)
                applicationClasses.from(wasm.output.classesDirs, main.output.classesDirs)
                outputFile.set(reflectionFile)
            }
            def smokeReflection = project.tasks.register('generateWasmSmokeAutoLogOutputReflection', GenerateAutoLogOutputReflection) {
                group = 'WASM'
                description = 'Generates AutoLogOutput reflection metadata for the smoke image.'
                classpath.from(smoke.runtimeClasspath)
                applicationClasses.from(smoke.output.classesDirs, wasm.output.classesDirs, main.output.classesDirs)
                outputFile.set(smokeReflectionFile)
            }
            [[nativeCompile, reflection, reflectionFile],
             [nativeSmokeCompile, smokeReflection, smokeReflectionFile]].each { pair ->
                pair[0].configure {
                    dependsOn(pair[1])
                    inputs.file(pair[1].flatMap { it.outputFile })
                    options.get().buildArgs.add(pair[2].map {
                        '-H:ReflectionConfigurationFiles=' + it.asFile.absolutePath
                    })
                }
            }
        }
        nativeCompile.configure { dependsOn(wasm.classesTaskName) }
        nativeSmokeCompile.configure { dependsOn(smoke.classesTaskName) }
        // Keep native-image outputs sensitive to changes in the bundled runtime shadows.
        nativeCompile.configure { inputs.files(runtime.java, wasm.java) }
        nativeSmokeCompile.configure { inputs.files(runtime.java, smoke.java) }
        [nativeCompile, nativeSmokeCompile].each { compile ->
            compile.configure {
                inputs.property('wasmbotJspiInstantiate', JspiLoaderPatch.INSTANTIATE)
                inputs.property('wasmbotJspiInstantiateReplacement', JspiLoaderPatch.INSTANTIATE_REPLACEMENT)
                inputs.property('wasmbotJspiJavaNative', JspiLoaderPatch.JAVA_NATIVE)
                inputs.property('wasmbotJspiJavaNativeReplacement', JspiLoaderPatch.JAVA_NATIVE_REPLACEMENT)
                doLast(new JspiLoaderPatch())
            }
        }

        def mainImageName = nativeCompile.flatMap { it.options.flatMap { it.imageName } }
        def smokeImageName = nativeSmokeCompile.flatMap { it.options.flatMap { it.imageName } }
        def mainDirectory = nativeCompile.flatMap { it.outputDirectory }
        def smokeDirectory = nativeSmokeCompile.flatMap { it.outputDirectory }
        def javascript = mainDirectory.zip(mainImageName) { directory, name -> directory.file("${name}.js") }
        def webAssembly = mainDirectory.zip(mainImageName) { directory, name -> directory.file("${name}.js.wasm") }

        project.tasks.register('wasmSite', Sync) {
            group = 'WASM'
            description = 'Stages the WASM browser frontend and native image under build/wasm/site/wasm.'
            dependsOn(prepare, nativeCompile)
            into(project.layout.buildDirectory.dir('wasm/site/wasm'))
            from(prepare.flatMap { it.outputDirectory.dir('shim') })
            from(javascript) { rename { 'wasm.js' } }
            from(webAssembly) { rename { 'wasm.js.wasm' } }
        }

        project.tasks.register('advantagescope', Copy) {
            group = 'WASM'
            description = 'Stages the bundled AdvantageScope Lite frontend under build/wasm/site.'
            dependsOn(prepare)
            into(project.layout.buildDirectory.dir('wasm/site'))
            from(prepare.flatMap { it.outputDirectory.dir('web/advantagescope') })
        }

        project.tasks.register('wasmRun', WasmHarnessTask) {
            group = 'WASM'
            description = 'Builds and runs the production image with the bundled native harness.'
            dependsOn(prepare, nativeCompile)
            javascriptRuntime.set(extension.javascriptRuntime)
            imageName.set(mainImageName)
            harness.set(prepare.flatMap { it.outputDirectory.file('tools/wasm-debug/run-wasm.mjs') })
            nativeDirectory.set(mainDirectory)
            workingDirectory.set(project.layout.projectDirectory)
        }
        project.tasks.register('wasmSmoke', WasmHarnessTask) {
            group = 'WASM'
            description = 'Builds and runs the bundled generic native behavioral smoke suite.'
            dependsOn(prepare, nativeSmokeCompile)
            javascriptRuntime.set(extension.javascriptRuntime)
            imageName.set(smokeImageName)
            harness.set(prepare.flatMap { it.outputDirectory.file('tools/wasm-debug/run-smoke.mjs') })
            nativeDirectory.set(smokeDirectory)
            workingDirectory.set(project.layout.projectDirectory)
        }
    }
}
