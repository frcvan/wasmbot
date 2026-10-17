package org.teamvan.wasmbot.plugin

import org.gradle.api.provider.Property

/** Consumer configuration for the WASM application and its native harness. */
abstract class WasmbotExtension {
    /** Java entry point that starts the robot with RobotBase.startRobot. */
    abstract Property<String> getMainClass()

    /** Native image basename, without the generated .js or .js.wasm suffix. */
    abstract Property<String> getImageName()

    /** JSPI-capable executable on PATH, or an absolute executable path. */
    abstract Property<String> getJavascriptRuntime()

    WasmbotExtension() {
        imageName.convention('wasm')
        javascriptRuntime.convention('bun')
    }
}
