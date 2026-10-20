package org.teamvan.wasmbot.plugin

import groovy.json.JsonOutput
import java.nio.file.Files
import java.util.jar.JarFile
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.RecordComponentVisitor
import org.objectweb.asm.Type

/** Registers reflective output access and the fields used by AK's recursive object discovery. */
@CacheableTask
abstract class GenerateAutoLogOutputReflection extends DefaultTask {
    @Classpath
    abstract ConfigurableFileCollection getClasspath()

    @Classpath
    abstract ConfigurableFileCollection getApplicationClasses()

    @OutputFile
    abstract RegularFileProperty getOutputFile()

    @TaskAction
    void generate() {
        Map<String, Map> classes = new TreeMap<>()
        Set<File> applicationRoots = applicationClasses.files
        classpath.each { File entry ->
            boolean application = applicationRoots.contains(entry)
            if (entry.isDirectory()) {
                Files.walk(entry.toPath()).withCloseable { paths ->
                    paths.filter { Files.isRegularFile(it) && it.toString().endsWith('.class') }
                            .sorted().forEach { path ->
                                Files.newInputStream(path).withCloseable {
                                    GenerateAutoLogOutputReflection.inspect(it, application, classes)
                                }
                            }
                }
            } else if (entry.isFile() && entry.name.endsWith('.jar')) {
                new JarFile(entry).withCloseable { jar ->
                    jar.entries().each { member ->
                        if (!member.directory && member.name.endsWith('.class') &&
                                !member.name.startsWith('META-INF/')) {
                            jar.getInputStream(member).withCloseable {
                                GenerateAutoLogOutputReflection.inspect(it, application, classes)
                            }
                        }
                    }
                }
            }
        }
        Set<String> outputPackages = classes.findAll { name, info -> info.output }.keySet().collect {
            it.contains('/') ? it.substring(0, it.lastIndexOf('/') + 1) : ''
        } as Set
        // A dependency may hold annotated objects in unannotated containers in another package.
        Set<String> holders = new HashSet<>(classes.findAll { name, info -> info.output }.keySet())
        boolean changed = !holders.isEmpty()
        while (changed) {
            changed = false
            classes.each { name, info ->
                if (holders.contains(name)) {
                    ([info.parent] + info.interfaces).findAll { it != null && !it.startsWith('java/') }.each {
                        if (classes.containsKey(it)) changed |= holders.add(it)
                    }
                }
                if (holders.contains(info.parent) || info.references.any { holders.contains(it) }) {
                    changed |= holders.add(name)
                }
            }
        }
        Set<String> traversal = new TreeSet<>()
        if (!outputPackages.isEmpty()) {
            classes.each { name, info ->
                boolean inputsOnly = !info.output && (info.autoLog ||
                        (name.endsWith('AutoLogged') && classes[info.parent]?.autoLog))
                if (!inputsOnly && (info.application || holders.contains(name) ||
                        outputPackages.any { name.startsWith(it) })) {
                    traversal.add(name)
                }
            }
        }
        // AK also enumerates superclasses, including inherited annotations and key-template fields.
        List<String> pending = new ArrayList<>(traversal)
        for (int i = 0; i < pending.size(); i++) {
            String parent = classes[pending[i]]?.parent
            if (parent != null && classes.containsKey(parent) && traversal.add(parent)) {
                pending.add(parent)
            }
        }
        Map<String, Map> metadata = new TreeMap<>()
        traversal.each { name ->
            Map info = classes[name]
            if (!info.fields.isEmpty() || info.output) {
                Map registration = [name: name.replace('/', '.')]
                if (!info.fields.isEmpty()) registration.allDeclaredFields = true
                if (info.output) registration.queryAllDeclaredMethods = true
                if (!info.methods.isEmpty()) registration.methods = info.methods
                metadata[name] = registration
            }
        }
        // Serialization has independent reflection needs, not caused by @AutoLog itself.
        Set<String> serialized = new HashSet<>()
        List<String> valueTypes = classes.values().findAll { it.output || it.autoLog }.collectMany { info ->
            (info.valueTypes + (info.autoLog ? info.references : [])) as List
        }
        for (int i = 0; i < valueTypes.size(); i++) {
            String name = valueTypes[i]
            Map valueType = classes[name]
            if (valueType == null || !serialized.add(name)) continue
            if (valueType.parent == 'java/lang/Record') {
                Map registration = metadata.computeIfAbsent(name) { [name: name.replace('/', '.')] }
                registration.allDeclaredConstructors = true
                registration.allDeclaredMethods = true
                valueTypes.addAll(valueType.components)
            } else if (valueType.isEnum) {
                Map registration = metadata.computeIfAbsent(name) { [name: name.replace('/', '.')] }
                registration.allDeclaredMethods = true
            } else {
                List fields = ['struct', 'proto'].findAll { valueType.fields.contains(it) }
                        .collect { [name: it] }
                if (!fields.isEmpty() && !metadata.containsKey(name)) {
                    metadata[name] = [name: name.replace('/', '.'), fields: fields]
                }
            }
        }
        File destination = outputFile.get().asFile
        destination.parentFile.mkdirs()
        destination.setText(JsonOutput.prettyPrint(JsonOutput.toJson(metadata.values())) + '\n', 'UTF-8')
    }

    private static void inspect(InputStream input, boolean application, Map<String, Map> classes) {
        ClassReader reader = new ClassReader(input)
        if (classes.containsKey(reader.className)) return // Preserve effective classpath precedence.
        Map info = [parent: reader.superName, application: application, autoLog: false,
                    output: false, methods: [], fields: new HashSet<>(), valueTypes: new HashSet<>(),
                    components: new HashSet<>(), isEnum: (reader.access & Opcodes.ACC_ENUM) != 0,
                    references: new HashSet<>(), interfaces: reader.interfaces.toList()]
        classes[reader.className] = info
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                if (descriptor == 'Lorg/littletonrobotics/junction/AutoLog;') info.autoLog = true
                return null
            }

            @Override
            RecordComponentVisitor visitRecordComponent(String name, String descriptor, String signature) {
                Type type = Type.getType(descriptor)
                if (type.sort == Type.OBJECT) info.components.add(type.internalName)
                return null
            }

            @Override
            FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                info.fields.add(name)
                Type fieldType = Type.getType(descriptor)
                if (fieldType.sort == Type.ARRAY) fieldType = fieldType.elementType
                if (fieldType.sort == Type.OBJECT) info.references.add(fieldType.internalName)
                return new FieldVisitor(Opcodes.ASM9) {
                    @Override
                    AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
                        if (annotation == 'Lorg/littletonrobotics/junction/AutoLogOutput;') {
                            info.output = true
                            GenerateAutoLogOutputReflection.addValueType(info, Type.getType(descriptor))
                        }
                        return null
                    }
                }
            }

            @Override
            MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
                        if (annotation == 'Lorg/littletonrobotics/junction/AutoLogOutput;') {
                            info.output = true
                            if (Type.getArgumentTypes(descriptor).length == 0 &&
                                    Type.getReturnType(descriptor).sort != Type.VOID &&
                                    (exceptions == null || exceptions.length == 0)) {
                                info.methods.add([name: name, parameterTypes: []])
                                GenerateAutoLogOutputReflection.addValueType(info, Type.getReturnType(descriptor))
                            }
                        }
                        return null
                    }
                }
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES)
    }

    private static void addValueType(Map info, Type type) {
        if (type.sort == Type.ARRAY) type = type.elementType
        if (type.sort == Type.OBJECT) info.valueTypes.add(type.internalName)
    }
}
