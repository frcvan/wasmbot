# AGENTS.md

Repository-specific context for coding agents working in `wasmbot`. Read `TASK.md` for the active performance and shim-cleanup plan.

## What this repo is

**Wasmbot** (`org.teamvan.wasmbot`) is a Gradle plugin, source-bundled Java runtime/shadow layer, JSPI continuation engine, and browser simulator that compiles standard FRC WPILib (Commands V2, Commands V3, and AdvantageKit) robot programs to WebAssembly using **GraalVM Web Image** (`native-image --tool:svm-wasm`).

The same `Robot` class and `robotPeriodic()` lifecycle run unchanged on desktop/hardware and in the browser.

## Repository map

| Path | Purpose |
|---|---|
| `build.gradle` | Builds the `org.teamvan.wasmbot` Gradle plugin, runs `ascope/buildLite.mjs`, zips `src/runtime`, `src/smoke`, `shim`, `tools`, and AdvantageScope Lite into `library.zip` inside the plugin JAR |
| `src/main/groovy/org/teamvan/wasmbot/plugin/` | Gradle plugin implementation (`WasmbotBuildPlugin`, `JspiLoaderPatch`, `GenerateAutoLogOutputReflection`, `GenerateDeployIndex`, `PrepareWasmLibrary`, `WasmHarnessTask`, `WasmbotExtension`) |
| `src/runtime/java/org/teamvan/wasmbot/` | Core WASM bridge & lifecycle: `WasmApplication`, `WasmRuntime`, `JsBridge`, `JsBytes`, `JsTunables`, `DeployFiles`, and `Target_java_io_FileInputStream` |
| `src/runtime/java/org/wpilib/` | HAL-free WPILib shadow classes (`RobotBase`, `TimedRobot`, `IterativeRobotBase`, `OpModeRobot`, `Continuation`, `DriverStationBackend`, `NetworkTablesJNI`, `SimDeviceJNI`, `SimRegistry`, `EigenJNI`, `DCMotorSim`, `Watchdog`, `RobotController`, `Filesystem`, etc.) |
| `src/runtime/advantagekit/java/` | WASM replacements for AdvantageKit's `LoggedRobot` and `Logger` (activated only when `akit-java` vendordep is present) |
| `src/runtime/resources/META-INF/native-image/` | Base GraalVM reflection and resource configs bundled into consumer builds |
| `src/smoke/java/org/teamvan/wasmbot/testing/` | Native behavioral smoke suite compiled into the `smoke` WASM binary (`WasmSmoke`, `FrameworkSmoke`, `ModeSmoke`, `OpModeSmoke`, `ControllerSmoke`, `NetworkTablesSmoke`, `SimSmoke`, `TimerSmoke`, `WatchdogSmoke`) |
| `shim/continuations.js` | JSPI (`WebAssembly.Suspending` / `WebAssembly.promising`) continuation engine for Commands V3 coroutines and non-blocking `tick()` |
| `shim/networktables.js` | In-process NetworkTables topic store and pub/sub transport (`globalThis.wasmbot.nt`) |
| `shim/sim.js` | Browser Driver Station UI overlay, keyboard/gamepad mapper, opmode selector, and 20 ms simulation loop |
| `shim/errors.js` | Formats GraalVM Java throwables via `wasmbot.formatJavaError` |
| `ascope/NT4.ts`, `ascope/buildLite.mjs` | Drop-in in-process `NT4_Client` replacement for AdvantageScope Lite and its build script |
| `tools/wasm-debug/` | CLI harnesses and tests (`run-wasm.mjs`, `run-smoke.mjs`, `run-browser.mjs`, `nt4.test.ts`, `errors.test.mjs`) |
| `examples/` | Standalone consumer robot projects (`commands-v3`, `commands-v2`, `advantagekit`, `drivetrain`) |

## Toolchain & environment requirements

- **Java 25 GraalVM with Web Image** (`native-image --tool:svm-wasm` and `org.graalvm.webimage.api`).
- **Gradle 9.4.1** and **WPILib 2027 alpha-7** (`2027.0.0-alpha-7`).
- **Binaryen** (`wasm-opt` / `wasm-as`) on `PATH`.
- **Bun** (default `javascriptRuntime`) or a JSPI-capable Node.js binary supporting `WebAssembly.Suspending` and `WebAssembly.promising`.

## Key commands

```sh
# Format, build plugin, and publish to local Maven repo (required before building examples)
./gradlew check assemble publishToMavenLocal

# Run JS/TS unit tests for shims and NT4 adapter
bun test ./tools/wasm-debug/errors.test.mjs ./tools/wasm-debug/nt4.test.ts

# Run example builds & behavioral smoke suites
./gradlew -p examples/commands-v3 wasmRun wasmSmoke wasmSite
./gradlew -p examples/commands-v2 wasmRun wasmSite
./gradlew -p examples/advantagekit wasmRun
./gradlew -p examples/drivetrain wasmRun wasmSite
```

## Architecture & invariants

1. **Source-Bundled Runtime Isolation**:
   - `src/runtime/java` is **not** compiled by the root `wasmbot` Gradle build. Instead, `bundleWasmLibrary` packages it into `library.zip`, and each consuming robot project extracts and compiles it in the `wasmRuntime` source set against the consumer's own WPILib/vendor dependencies.
   - Classpath precedence for the WASM image: `wasm` (consumer overrides) → `wasmRuntime` (bundled shadows) → `main` (robot code + desktop dependencies).
   - Desktop `main` never gets `wasmRuntime` on its classpath. Shared robot code detects WASM via `Boolean.getBoolean("wasmbot")`.

2. **JSPI & Loader Patching (`JspiLoaderPatch.groovy` + `shim/continuations.js`)**:
   - GraalVM Web Image wraps Java objects in JS proxies that JSPI rejects at raw WASM boundaries.
   - `JspiLoaderPatch` patches the generated `<imageName>.js` loader to route `WebAssembly.instantiate` through `globalThis.wasmbot.continuations.instantiate(...)` and exposes Graal's internal `javaNative` symbol on `globalThis.wasmbot.javaNative`.
   - `continuations.js` unwraps `value[javaNative]` before passing receivers to `WebAssembly.promising` exports (`tick` and `wasmContinuationBody`).

3. **Single Global Namespace**:
   - All JS hooks and exports live on `globalThis.wasmbot` (`nowNanos`, `driverStation`, `setOpModes`, `setRumble`, `nt`, `continuations`, `start`, `tick`, `javaNative`, `formatJavaError`). NetworkTables traffic goes through `wasmbot.nt`; there is no separate get/put hook surface.

4. **Binary Data Across the GraalVM Boundary (`JsBytes.java`)**:
   - GraalVM 25.4's WASM backend does not coerce Java `byte[]` to JS `Uint8Array`.
   - `JsBytes` packs 2 bytes per UTF-16 `char` into a Java `String` and unpacks it in JS into a `Uint8Array` before passing it to `wasmbot.nt.put(...)`. Keep this carrier internal to the bridge; NT topics and `NT4.ts` always store and deliver `Uint8Array` snapshots.

5. **Formatting & Release Size Budget**:
   - `spotlessApply` runs automatically before `compileJava` (`googleJavaFormat()` for Java, `greclipse()` for Gradle, 2-space JSON).
   - The bundled plugin JAR must stay under **30 MB** (typically ~22 MB with AdvantageScope Lite included).
