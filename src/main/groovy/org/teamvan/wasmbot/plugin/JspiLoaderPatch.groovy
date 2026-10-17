package org.teamvan.wasmbot.plugin

import org.graalvm.buildtools.gradle.tasks.BuildNativeImageTask
import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.Task

/** Installs JSPI before instantiation and publishes Graal's raw-reference symbol. */
final class JspiLoaderPatch implements Action<Task>, Serializable {
    static final String INSTANTIATE = 'await WebAssembly.instantiate(file, wasmImports)'
    static final String INSTANTIATE_REPLACEMENT = 'await globalThis.wasmbot.continuations.instantiate(file, wasmImports)'
    static final String JAVA_NATIVE = 'javaNative: Symbol("__java_native__"),'
    static final String JAVA_NATIVE_REPLACEMENT = 'javaNative: ((globalThis.wasmbot || (globalThis.wasmbot = {})).javaNative = Symbol("__java_native__")),'

    @Override
    void execute(Task task) {
        BuildNativeImageTask nativeTask = (BuildNativeImageTask) task
        String imageName = nativeTask.options.get().imageName.get()
        File script = new File(nativeTask.outputDirectory.get().asFile, "${imageName}.js")
        String source = script.getText('UTF-8')
        source = replaceOnce(source, INSTANTIATE, INSTANTIATE_REPLACEMENT, 'GraalVM Web Image instantiate hook changed; update JSPI integration.')
        source = replaceOnce(source, JAVA_NATIVE, JAVA_NATIVE_REPLACEMENT, 'GraalVM javaNative symbol changed; update JSPI integration.')
        script.setText(source, 'UTF-8')
    }

    private static String replaceOnce(String source, String original, String replacement, String failure) {
        int first = source.indexOf(original)
        if (first < 0 || first != source.lastIndexOf(original)) {
            throw new GradleException(failure)
        }
        return source.replace(original, replacement)
    }
}
