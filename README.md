# Wasmbot

`wasmbot` builds ordinary FRC WPILib Commands V2 and Commands V3 robot programs with GraalVM Web Image. The runtime package is **`org.teamvan.wasmbot`**. The same `Robot` class and its `robotPeriodic()` method run on desktop/hardware and under WASM; there is no separate simulation lifecycle adapter.

The plugin owns the runtime/shadows, JSPI continuation engine, native build configuration, generic browser telemetry viewer, and native smoke suite. It contains no project-specific drivetrain acceptance tests or Pose-channel assumptions. Struct/protobuf publishing delivers raw `Uint8Array` snapshots to the host and frontend.

## LLM usage

LLMs were used to generate code for this project. All generated output was reviewed and tested.

## Requirements

- Gradle 9.4.1, Java 25, and WPILib 2027 alpha-7 with either its Commands V2 or Commands V3 vendordep are the supported baseline.
- A GraalVM JDK containing Web Image (`native-image --tool:svm-wasm` and `org.graalvm.webimage.api`). A normal Java 25 JDK alone cannot compile the WASM runtime.
- Binaryen on `PATH` and a GraalVM-supported native compiler.
- A JSPI-capable JavaScript runtime for CLI smokes, and a JSPI-capable browser for the viewer: both `WebAssembly.Suspending` and `WebAssembly.promising` must exist. Bun is the default CLI runtime.

On Windows, use a **VS Code x64 developer terminal**. For the Downloads JDK used here:

```powershell
$env:JAVA_HOME = Join-Path $env:USERPROFILE 'Downloads\graalvm-25.4.4.1.1+1.1'
$env:GRAALVM_HOME = $env:JAVA_HOME
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
java -version
$env:VSCMD_ARG_HOST_ARCH
$env:VSCMD_ARG_TGT_ARCH
```

Both architecture values must be `x64`. Adjust the JDK path for another installation, and ensure Gradle's JVM/toolchain configuration does not select a different compiler. Keep the consumer's ordinary WPILib and vendor Java dependencies, including its chosen commands vendordep; Commands V2 and V3 cannot coexist.

## Install the plugin

### Included source build

Put this standalone build at `wasmbot/` inside the robot project, or use a corresponding relative path. Add the following **inside the consumer's existing `pluginManagement` block** in `settings.gradle`:

```groovy
includeBuild('wasmbot')
```

Keep the normal generated WPILib repository configuration. Add this entry to the consumer's existing `plugins` block:

```groovy
id 'org.teamvan.wasmbot'
```

The plugin JAR bundles runtime Java sources, metadata, browser assets, tools, and smoke sources. Each consumer compiles the runtime against its own WPILib dependency classpath. The included build tracks source and resource changes automatically.

### Published artifact

The published plugin resolves from the `gradlePluginPortal()` repository that WPILib's generated `pluginManagement` block already declares, so no extra repository entry is needed. Apply the published version in `build.gradle`:

```groovy
id 'org.teamvan.wasmbot' version '0.1.0'
```

The implementation coordinate is `org.teamvan.wasmbot:wasmbot:0.1.0`, resolved through the `org.teamvan.wasmbot` Gradle plugin marker. Both are published to the Gradle Plugin Portal and Maven Central. The plugin ID replaces the old unqualified `wasmbot` ID; the `wasmbot { ... }` extension and runtime packages are unchanged.

For local development, run `gradle check assemble publishToMavenLocal` from this standalone build with Gradle 9.4.1 and Java 25, then add `mavenLocal()` before `gradlePluginPortal()` in the consumer's `pluginManagement.repositories`. The [independent example](examples/commands-v3) uses this published-plugin path, not an included source build.

Do not add the core runtime as a desktop `implementation` dependency. The plugin adds it only to the WASM source sets.

## Start your normal Robot

Use the same entry-point pattern as ordinary WPILib:

```java
package example;

import org.wpilib.framework.RobotBase;

public final class Main {
  private Main() {}

  public static void main(String... args) {
    RobotBase.startRobot(Robot::new);
  }
}
```

The [example Main](examples/commands-v3/src/main/java/example/Main.java) is used directly for both targets. You can instead keep a tiny `WasmMain` in `src/wasm/java/` with the same `RobotBase.startRobot(Robot::new)` call, if your project wants separate entry-point names. It must not duplicate mechanism construction, input/output loops, or scheduler execution.

Configure that actual class in the consumer:

```groovy
wasmbot {
    mainClass.set('example.Main')
    imageName.set('counter')
}
```

| Property            | Default  | Meaning                                                            |
| ------------------- | -------- | ------------------------------------------------------------------ |
| `mainClass`         | Required | Ordinary entry point calling `RobotBase.startRobot(Robot::new)`.   |
| `imageName`         | `wasm`   | Native artifact basename, without extensions.                      |
| `javascriptRuntime` | `bun`    | JSPI-capable executable on `PATH`, or an absolute executable path. |

The plugin applies the native-build plugin itself. Remove obsolete local WASM adapters, copied runtime sources, native binary definitions, and loader-patching scripts when migrating. The public extension is `wasmbot`.

## Robot lifecycle and the command scheduler

The host installs the runtime and invokes `wasmbot.start()`. Wasmbot installs its telemetry backend, refreshes driver-station data, constructs the real `Robot` once, and starts a nonblocking framework lifecycle. For the 2027 SDK, initialization belongs in the constructor; `simulationInit()` follows exactly once.

```mermaid
flowchart LR
    Host[Await wasmbot.tick] --> DS[Refresh driver station]
    DS --> Due[Run due framework callbacks]
    Due --> RP[Robot.robotPeriodic]
    RP --> App[Application-owned scheduler and IO]
    App --> Sim[simulationPeriodic]
    Sim --> Done[Resolve tick Promise]
```

`robotPeriodic()` runs in both enabled and disabled cycles. **Wasmbot does not call `Scheduler.run()` for you.** Keep the existing scheduler call inside your normal robot method; it must run only once per intended robot cycle. For example, the [shared example Robot](examples/commands-v3/src/main/java/example/Robot.java) uses:

```java
@Override
public void robotPeriodic() {
  Scheduler.getDefault().run();
  Telemetry.log("Example/Counter", counter.ticks());
  directCounter.set(counter.ticks());
  Telemetry.log("Example/Enabled", RobotState.isEnabled());
  Telemetry.log("Example/Wasmbot", Boolean.getBoolean("wasmbot"));
}
```

Input sampling and output publication should likewise stay in this shared robot lifecycle, in the order appropriate for the project. Commands V3's scheduler does not implicitly filter its entire run loop by enable state; the enabled/disabled policy above is ordinary application code, not a WASM-specific branch.

`Scheduler` and `Coroutine` remain the real WPILib classes. JSPI-backed continuation shadows preserve suspended Java stacks, command-local variables, child awaiting, cancellation, and exception identity.

### Supported framework behavior

- `OpModeRobot` and `TimedRobot`/`IterativeRobotBase` use HAL-free, host-driven lifecycle implementations. No HAL notifier, native scheduler thread, or blocking competition loop is started.
- Construction and `simulationInit()` happen once. Driver-station connection callbacks, disabled transitions, periodic hooks, and `simulationPeriodic()` follow the SDK ordering. Exceptions propagate; failed expirations are not replayed at the same host time.
- Configured main-loop periods and `addPeriodic(...)` callbacks use the host's monotonic clock. Only due callbacks execute. A period shorter than the host interval is quantized to that interval; missed expirations coalesce instead of replaying a burst.
- `endCompetition()`/`close()` stop subsequent callbacks. `getLoopStartTime()` uses the host clock.
- The host selects iterative robot mode independently of enable state: `unknown`, `teleop`, `autonomous`, or `utility`. Omitted or unrecognized mode remains UNKNOWN and uses disabled iterative callbacks, even when enabled.
- `OpModeRobot.addOpMode(...)` registers factories or classes for the browser selector. `publishOpModes()` sends the list to the simulator, and `RobotState.getOpMode()`/`getOpModeId()` report the active choice. Selected opmodes are constructed on selection, run `disabledPeriodic()` while disabled, `start()`/`periodic()` while enabled, and `end()`/`close()` on disable or mode switch. `nonePeriodic()` runs when no opmode is selected. Annotated class scanning remains best-effort because native images only retain classes and resources that survived analysis; explicit `addOpMode` registration is the reliable path.
- Native watchdog diagnostics and physical HID outputs are unavailable and fail explicitly if invoked. A custom blocking `RobotBase` subclass needs a host-driven WASM implementation rather than a native loop.

Choose simulated IO using your existing `RobotBase.isSimulation()`/`isReal()` selection. These shadows do not emulate every vendor or HAL API. Avoid hardware/JNI initialization in static fields that run before your simulation selection.

## Detect WASM without a dependency

Use **standard Java**, anywhere in shared robot code:

```java
boolean runningUnderWasm = Boolean.getBoolean("wasmbot");
```

Wasmbot sets the `wasmbot` system property to `true` **before invoking the Robot constructor**. It is unset—and `Boolean.getBoolean` returns `false`—in ordinary desktop/hardware launches unless explicitly overridden by the user.

A project can expose a normal Java helper without importing any Wasmbot class:

```java
public static boolean isWasm() {
  return Boolean.getBoolean("wasmbot");
}
```

The consuming robot's `Constants.isWasm()` follows this pattern. Prefer reading through a method rather than caching a build-time-initialized flag. Do not import `org.teamvan.wasmbot.*` into shared `src/main/java/` just to detect the environment: a conditional around such references would still require those classes at Java compile time. Neither the entry point nor the detection method needs that dependency.

## Build, smoke, and serve

From the consuming robot project:

```sh
./gradlew nativeCompile
./gradlew wasmRun
./gradlew wasmSmoke
./gradlew wasmSite
./gradlew advantagescope
python -m http.server 8099 --directory build/wasm/site
```

Open `http://localhost:8099/`. On Windows, `.\gradlew.bat` is equivalent to `./gradlew`.

| Task                 | Result                                                                                                                                                                                                                          |
| -------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `nativeCompile`      | `build/native/nativeCompile/<imageName>.js` and `<imageName>.js.wasm`.                                                                                                                                                          |
| `wasmRun`            | Starts the consumer's actual Robot, advances disabled/enabled ticks, and reports telemetry count and tick latency.                                                                                                              |
| `nativeSmokeCompile` | Builds the library's native behavioral smoke image.                                                                                                                                                                             |
| `wasmSmoke`          | Exercises RobotBase startup, constructor detection, lifecycle order/cadence/stop, scheduler execution counts, dynamics, serialization, continuations, cancellation, child awaiting, exceptions, and overlapping-tick rejection. |
| `wasmSite`           | Stages the generic browser frontend and native image at `build/wasm/site/wasm/`.                                                                                                                                                 |
| `advantagescope`     | Stages the bundled AdvantageScope Lite frontend at `build/wasm/site/`.                                                                                                                                                           |

The staged browser assets always use canonical `wasm.js`/`wasm.js.wasm` names, even for a native image named `counter`. Serve the staged directory over HTTP, not `file://`.

The bundled viewer is trimmed to what an FRC robot displays: the current FRC 3D field and the 2026 KitBot, the FRC 2D fields, and the standard joystick models. FTC assets, older season models, and AdvantageScope's own documentation site are not shipped, and the **Documentation** tabs load the hosted manual at [docs.advantagescope.org](https://docs.advantagescope.org). This keeps the published plugin JAR near 22 MB; see [PUBLISH.md](PUBLISH.md#release-size) for the Maven Central release-size budget.

The browser begins disabled in unknown mode. Open the **Robot simulator** panel, select **Teleop**, **Auto**, or **Utility**, choose a published opmode, then switch **Robot enabled** on. The AdvantageScope frontend displays telemetry without assuming a drivetrain or Pose format; the simulator itself supplies time and driver-station inputs. Losing focus or hiding the page disables the robot and clears held keyboard keys without changing the selected mode. Typing in text fields does not drive the robot.

Register opmodes in the robot constructor and publish them once. The example project does this in [`Robot.java`](examples/commands-v3/src/main/java/example/Robot.java):

```java
addOpMode(
    RobotMode.TELEOPERATED,
    "Scheduler Counter",
    "Examples",
    "Runs the normal counter loop under an explicitly selected opmode.",
    () -> new CounterOpMode(this));
publishOpModes();
```

The selector only shows opmodes for the current robot mode, and selects the first matching opmode automatically until you choose **None**. `None` keeps the normal framework lifecycle and calls `OpModeRobot.nonePeriodic()`.

**Configure controls** opens a dialog for keyboard bindings (every axis, trigger, and gamepad button), the keyboard Driver Station port, connected gamepad port assignments, stick deadzone, Y-axis inversion, and the alliance override key. Bindings are captured by pressing a key, saved in browser storage, and applied immediately. Standard browser gamepads use their mapped ports; a gamepad takes precedence when it shares the keyboard port. Press a controller button if the browser has not exposed it yet. Non-standard browser mappings are reported as unsupported rather than guessed.

Default keyboard bindings (all can be changed in **Configure controls**):

| Keyboard | Gamepad input |
| --- | --- |
| W/S, A/D | Left Y, left X |
| Q/E, R/F | Right X, right Y |
| Z/C | Left/right triggers |
| J/K/U/I | Face down/right/left/up |
| 1/3 | Left/right bumpers |
| B/Enter, Home | Back/start, guide |
| V/N, arrow keys | Stick clicks, D-pad |
| Shift | Red alliance while held |

`examples/drivetrain` uses a port-zero `Gamepad` with its normal deadbands. Forward speed is limited to 3 m/s and acceleration to 3 m/s²; turn rate is limited to π rad/s and angular acceleration to 2π rad/s². It integrates averaged velocities along an arc, turns clockwise for right-stick-right, and freezes its pose while disabled. Smoothing belongs to the example, not the generic simulator, so other robots receive unfiltered inputs.

The low-level tools in `build/wasm/library/tools/wasm-debug/` accept:

```text
run-wasm.mjs [buildDir] [imageName]
run-smoke.mjs [buildDir] [imageName]
run-browser.mjs [pageUrl] [debugPort]
```

Run them with the configured JSPI-capable Node/Bun executable. Paths default from the consumer's current working directory. Prefer the Gradle tasks to keep image names and artifact paths synchronized.

### Independent published-plugin example

From this repository's root:

```sh
./gradlew -p wasmbot check assemble publishToMavenLocal
./gradlew -p wasmbot/examples/commands-v3 wasmRun wasmSmoke wasmSite
python -m http.server 8099 --directory wasmbot/examples/commands-v3/build/wasm/site
```

That example uses a normal `Main` and `Robot`, a real scheduler-driven counter, a different image name, and no core imports in shared Java. Telemetry appears at `/Telemetry/Example/Counter`, `/Telemetry/Example/Enabled`, and `/Telemetry/Example/Wasmbot`. `/Telemetry/Example/Deploy/...` reports the bundled `src/main/deploy/wasmbot-probe.txt` read through `File`, `FileInputStream`, `FileReader`, and `Files`. `/Example/DirectCounter` uses a cached WPILib `DoublePublisher`, not `Telemetry`, so its explicit NT path is unchanged:

```java
private final DoublePublisher directCounter = NetworkTableInstance.getDefault()
    .getDoubleTopic("/Example/DirectCounter").publish();
```

The WASM NetworkTables Java surface supports default-instance tables, typed/generic publication of primitive scalars and arrays, raw/struct data, topic properties, schemas, and double-entry reads. Publisher lifetime, defaults, and explicit timestamps are preserved; `SEND_ALL`, `KEEP_DUPLICATES`, and periodic options are accepted by the immediate in-process transport. Struct publishing retains WPILib's interfaces and serialization, transports packed values as `Uint8Array` snapshots, and publishes retained nested schemas under `/.schema/struct:<type>`. Native listeners, subscription queues, extra instances, and network client/server startup remain unsupported. Desktop/hardware builds use the ordinary SDK implementation.

### Commands V2 example

The independent [Commands V2 example](examples/commands-v2) uses only the Commands V2 vendordep, a standard `Main`, and a `TimedRobot`. Its `Counter` extends `SubsystemBase` and installs a default command; `robotPeriodic()` calls the real `CommandScheduler.getInstance().run()` exactly once per cycle. Unlike Commands V3, Commands V2 automatically prevents ordinary commands from running while disabled. The counter freezes while disabled and resumes after the default command is scheduled on re-enable.

From the Wasmbot repository root, with the toolchain above configured:

```sh
./gradlew publishToMavenLocal
./gradlew -p examples/commands-v2 wasmRun wasmSite advantagescope
python -m http.server 8099 --directory examples/commands-v2/build/wasm/site
```

Open `http://localhost:8099/`, select teleop, and enable the robot in the simulator panel. This example publishes the same telemetry and direct-NT channels listed above; its native image is named `counter-v2`.

Commands V2's scheduler watchdog uses the host's monotonic simulation clock instead of a HAL notifier. Due callbacks run at host tick boundaries or when watchdog expiry is queried/disabled; epoch diagnostics go to standard error. It cannot interrupt blocked JavaScript or detect wall-clock stalls while simulation time is frozen. The bundled `wasmSmoke` suite requires Commands V3, so use `wasmRun` for this V2-only example rather than installing conflicting commands vendordeps.

### AdvantageKit live simulation

AdvantageKit support activates only when the robot project already has a `vendordeps/*.json` entry declaring `org.littletonrobotics.akit:akit-java`. Wasmbot does not install the vendordep, add the AK runtime dependency, or enable AK support for a Maven dependency alone. The verified baseline is AdvantageKit `27.0.0-alpha-6` with WPILib `2027.0.0-alpha-7`.

With that vendordep installed:

- WASM-only `Logger` and `LoggedRobot` replacements use the host clock and nonblocking iterative lifecycle. Desktop robot classes retain the original implementations.
- `Logger.recordMetadata`, `recordOutput`, and `processInputs` use upstream `LogTable` serialization. `NT4Publisher` publishes live values under `/AdvantageKit`, including typed arrays and packed structs. Receivers run synchronously on the host thread with independent snapshots; custom receivers must be host-safe and nonblocking.
- The matching `akit-autolog` annotation processor is added to `main`, `wasm`, and `wasmTest`. Keep normal `@AutoLog` inputs and generated `*AutoLogged` classes; their generated serializers access fields directly and do not need blanket constructor/field reflection registration. The metadata scanner retains separate type-specific requirements for serialized values, including struct/protobuf fields and record accessors/constructors.
- `@AutoLogOutput` fields and zero-argument getters are discovered by the upstream `AutoLogOutputManager` after `simulationInit()` and logged after each user cycle. Private members, nested robot objects, default keys, `{field}` key substitutions, and units retain AK behavior. Use `AutoLogOutputManager.addPackage(...)`/`addObject(...)` for additional packages/roots as on desktop.
- Build-time bytecode scanning finds `@AutoLogOutput` members across the effective WASM classpath, including dependency JARs. It generates invocation metadata for annotated getters and field metadata for robot-object traversal, inherited members, key substitutions, and serialized output types. Production and smoke metadata are generated separately under `build/wasm/reflection/` by `generateWasmAutoLogOutputReflection` and `generateWasmSmokeAutoLogOutputReflection`; native-image builds include them without editing user-maintained configuration.
- Timed loops coalesce missed deadlines. `setUseTiming(false)` runs one cycle per host tick, not a blocking fast-replay loop. Shutdown closes receivers; receiver failures propagate rather than being swallowed.

File-backed WPILOG recording/replay, native socket receivers, URCL, console capture, and native hardware diagnostics are not supported. `Logger` rejects replay sources, file/native-socket receivers, URCL registration, and custom console capture sources explicitly. Stdout/stderr remain available through the host console. Select live simulation receivers instead of unconditionally constructing file/hardware-backed helpers.

The [AdvantageKit example](examples/advantagekit) includes the vendordep and logs generated counter inputs, metadata, enabled state, and a pose through `NT4Publisher`, including private annotated fields/getters and a nested output with a template key and unit:

```sh
./gradlew publishToMavenLocal
./gradlew -p examples/advantagekit wasmRun
```

Retained AdvantageKit-derived code is covered by [AdvantageKit-LICENSE](AdvantageKit-LICENSE), included under `licenses/` in the distributed source bundle.


## JavaScript host and telemetry

All project-owned JavaScript hooks and exports live on the single global object `globalThis.wasmbot`; there are no standalone global aliases. GraalVM's `GraalVM` global and the browser's `document` remain unchanged. For a custom frontend, initialize the clock, driver-station hook, and JSPI continuation engine on this object **before** installing NetworkTables or importing the image, and therefore before `GraalVM.run()`:

```js
import { createWasmContinuations } from "./shim/continuations.js";
import { createNetworkTables } from "./shim/networktables.js";

let nowNanos = 0;
const ds = {
  enabled: false, mode: "teleop", alliance: "blue",
  controllers: [{ name: "Virtual gamepad", axes: Array(6).fill(0), buttons: Array(15).fill(false) }]
};
globalThis.wasmbot = {
  nowNanos: () => nowNanos,
  driverStation: () => ds,
  continuations: createWasmContinuations(),
};
const networkTables = createNetworkTables(globalThis.wasmbot.nowNanos);
networkTables.install(); // adds nt, ntGet, and ntPut to globalThis.wasmbot
```

| Field on `globalThis.wasmbot` | Contract |
| ---------------------------- | -------- |
| `nowNanos()` | Monotonic simulated time, in nanoseconds. |
| `driverStation()` | `{enabled, mode, alliance, opMode, opModeName, controllers}`; mode is `"unknown"`, `"teleop"`, `"autonomous"`, or `"utility"` and is independent of enabled state. Alliance is `"red"` or `"blue"`. `opMode` is the published opmode ID and `opModeName` is its name, or `0`/`""` for no selection. `controllers` contains up to six entries: `null` for disconnected, or `{name, axes, buttons}` as described below. |
| `setOpModes(json)` | Optional host hook. Java calls it from `publishOpModes()` with a JSON array of `{id, mode, name, group, description, textColor, backgroundColor}`. The supplied simulator uses it to populate the opmode selector. |
| `setRumble(port, left, right, leftTrigger, rightTrigger)` | Optional host hook. Java calls it from `GenericHID.setRumble()`/`setRawRumble()` with four 16-bit values from 0 through 65535. The supplied simulator maps the two handle values to the gamepad's `vibrationActuator` dual-rumble effect. |
| `ntGet(path)` | Current host value, or `undefined` if no value exists. |
| `ntPut(path, value, type?, timestampUs?)` | Publishes a JavaScript value; type defaults to inference and timestamp to the host clock. |
| `nt` | Shared in-process topic transport installed by `networkTables.install()`. |
| `continuations` | JSPI engine returned by `createWasmContinuations()`, installed by the host before the image runs. |
| `start()` | Export installed by the image; starts the real Robot once, without resetting it on repeated calls. |
| `tick()` | Export installed by the image; returns a Promise for one framework tick. |
| `probe` | Smoke-image fixture exports, used by the native smoke harness. |

After importing the image and awaiting `GraalVM.run()`, call `wasmbot.start()`. Advance the host clock by 20 ms per default tick and **await the `wasmbot.tick()` Promise before starting another tick**. The supplied viewer serializes ticks and reports startup/runtime failures. JSPI requires neither `SharedArrayBuffer` nor cross-origin isolation.

Driver-station refresh samples and normalizes the host snapshot in one JS bridge call. Unchanged state stays cached in Java; only changed header fields or controller ports cross back into Java. Hosts may mutate the same snapshot/arrays in place or return new objects with equal contents; no revision field or explicit notification is required. Button edges remain latched until consumed, and disconnect still releases held buttons. No axis tolerance or extra deadband is applied.

Telemetry registry paths publish under `/Telemetry`; explicit NT publisher paths are unchanged. Declared types, JSON properties, schemas, and sample timestamps are transported. Primitive scalars and arrays are native JS values; raw bytes, little-endian structs, protobuf bytes, and binary schemas are `Uint8Array` snapshots. A struct array is one concatenated packed byte array with type `struct:<type>[]`, including a zero-length `Uint8Array` for an empty array. Longs outside JS's safe-integer range remain exact decimal strings, including in arrays. Java nanosecond timestamps convert to NT4 microseconds once; timestamp values inside payloads retain their original units.

Telemetry and NT publications remain synchronous: reads and listeners see accepted writes immediately, including duplicate samples. Default publication timestamps resolve the host clock inside the existing JS write call rather than making a separate clock crossing. Explicit timestamps retain nanosecond-to-microsecond conversion; schema publications retain timestamp zero.

GraalVM 25.4's WASM backend does not provide primitive-array-to-typed-array coercion. The internal binary bridge packs two bytes per UTF-16 code unit, then reconstructs bytes before publication; this carrier never reaches NT consumers. This removes hex formatting/parsing but is not zero-copy. Packing scratch storage is reused, and the host copies accepted samples so later Java buffer reuse cannot corrupt retained values or queued NT4 samples. Absolute `ByteBuffer` ranges preserve position, limit, byte order, and array offsets, including direct and read-only buffers.

The generic HID bridge supplies each controller port independently. Axis indices 0–5 are left X, left Y, right X, right Y, left trigger, and right trigger. Sticks use `[-1, 1]`; triggers use `[0, 1]`. Buttons are zero-based booleans using WPILib's `Gamepad.Button` indices (up to 26); the browser standard mapping and keyboard supply indices 0–14. Array lengths determine availability masks. D-pad buttons 11–14 also provide POV zero. Press/release edges latch independently until read and are shared across wrappers of the same port; disconnect clears inputs and latches releases. Names and connection state follow the snapshot. Rumble reaches the host through `setRumble`; the standard gamepad API exposes only the two grip motors, so trigger values fold into them. Touchpads and LED outputs are not emulated.

`NiDsXboxController` getters and events translate the normalized host layout without remapping `GenericHID` or `Gamepad`. Commands V3's precompiled `CommandNiDsXboxController` also needs an adapter that routes its triggers through those translated events; keep that optional command-library shadow in the consumer's `src/wasm/java/` so Commands V2 consumers do not gain a V3 dependency.

The host-backed SDK Tunables backend is installed before robot construction and updated by the normal iterative lifecycle. Robust tunables receive requests at `/Tunables/<path>/tune` and acknowledge at `/value`; `Selectable` keeps its standard defaults/options and selected-child protocol. Double-entry writes such as AdvantageKit's `/Tuning/...` inputs are independent of this SDK protocol. There is no native listener thread.

Custom hosts that omit `controllers` may still provide `{enabled, mode, alliance, leftX, leftY, rightX}`. That snapshot supplies only three axes on port zero, with no buttons or other connected ports. Omitting mode preserves UNKNOWN rather than implicitly selecting teleop. Omitting `opMode`/`opModeName`, or omitting `setOpModes`, simply leaves the opmode selector empty and preserves `nonePeriodic()` behavior.

### AdvantageScope-compatible NT4 client

[`ascope/NT4.ts`](ascope/NT4.ts) is a self-contained replacement for AdvantageScope's `src/hub/dataSources/nt4/NT4.ts`. It exports `NT4_Client`, `NT4_Topic`, and all three `NT4_PORTS_*` constants, with the same constructor and public methods. Copy that file into the consuming frontend; existing imports and client construction can remain unchanged. This repository does not modify the sibling AdvantageScope checkout.

The frontend and Wasm robot must share the same `globalThis.wasmbot.nt` host (and `wasmbot.nowNanos` clock). The supplied viewer and CLI harnesses install it automatically; a custom frontend uses the setup above. This is an in-process transport, not a WebSocket server: address, ports, and app name are accepted for API compatibility but do not select a remote endpoint. No MessagePack dependency is required.

- `connect()` asynchronously connects to the installed host; `disconnect()` cancels pending delivery and removes that client's publishers. Reconnecting restores its subscriptions and publications.
- Exact/prefix subscriptions replay cached samples; topics-only subscriptions announce without values. `sendAll` preserves samples within each delivery period; otherwise only the latest sample is delivered. Overlapping subscriptions merge without duplicate delivery, using the shortest requested period.
- Topic announcements, removals, and property changes use the usual callbacks. Property values of `null` delete keys. Last-publisher removal deletes a topic unless retained or persistent. Both flags preserve topics only for this host's lifetime; there is no disk persistence.
- Client publications write back through the same store read by `wasmbot.ntGet`. Conflicting publication types and older timestamps cannot overwrite current values. The host and NT4 adapter share the same native value representation: packed binary samples are already byte arrays, with no hex decoding or string reinterpretation in the adapter.
- Server time uses the simulated clock in microseconds; client time uses epoch microseconds. Server time is `null` while disconnected; in-process network latency is zero. Java's explicit NT timestamps are nanoseconds and are converted at the bridge.

Host transport methods are `snapshot()`, `getTopic(name)`, `listen(callback)` (returns an unsubscribe function), `publish(owner, name, type, properties)`, `unpublish(owner, name)`, `setProperties(name, update)`, and `put(owner, name, timestampUs, value)`. Topics expose a monotonically increasing value `revision` and `lastPublisher`, allowing tunables to distinguish external writes from acknowledgements. Listener events have `kind` (`announce`, `unannounce`, `properties`, or `value`) and `topic`; value events also have `timestamp` and `value`. `owner` identifies an individual publisher; clients must share the transport rather than wrap or overwrite the robot hooks.

Listener registrations use a cached dispatch snapshot rather than copying listeners on every event. Adding/removing a listener does not change an event already being dispatched; nested and subsequent events use the updated registrations.

Run the deterministic adapter regression suite with:

```sh
bun test ./tools/wasm-debug/nt4.test.ts
```


## Source isolation and extensions

The plugin creates:

- `wasmRuntime`: bundled runtime sources/resources, compiled against the consumer's ordinary WPILib dependency classpath.
- `wasm`: optional consumer-only sources/resources from `src/wasm/java/` and `src/wasm/resources/`.
- `wasmTest`: bundled native behavioral fixtures.

Consumer WASM classes win over bundled shadows, which win over desktop/vendor classes in the native image classpath. None of the shadows/core classes are added to desktop `main` or its robot JAR's compiled packages. The plugin supplies `-parameters` and `-XDstringConcat=inline` for Web Image compatibility, including reachable ordinary robot classes.

For additional native APIs, use simulated IO or matching consumer-specific replacements in `src/wasm/java/<original/package>/`. Implement the APIs the program actually uses; do not silently substitute hardware no-ops. Additional reflection/resource metadata belongs in `src/wasm/resources/META-INF/native-image/`.

Files under the consumer's `src/main/deploy` are bundled as classpath resources under `/wasmbot/deploy/` and included in the native image. Web Image runs its file APIs on an in-memory file system, so before the `Robot` constructor runs, startup copies those resources into `<working directory>/deploy` using a build-generated name index. The WASM `org.wpilib.system.Filesystem` shadow reports that same directory from `getDeployDirectory()`, `getOperatingDirectory()`, and `getLaunchDirectory()`, so deploy lookups and the staged files cannot drift apart and deploy paths use the deployed-robot layout rather than desktop `src/main/deploy`. Ordinary Java file access then reads them the way it would on a deployed robot: `FileInputStream`/`FileReader`, `File.exists()`, `length()`, and `list()`, and the read-side `java.nio.file.Files` helpers, including directory listings. The copies live only for the browser session; this does not create a host filesystem, and writes are discarded when the page unloads. WPILib's original Java `Timer` stopwatch is retained and reads the host-backed `RobotController` clock. No FMS match clock or game-specific message is supplied (`-1` and `Optional.empty()` respectively).

After changing GraalVM, WPILib, framework shadows, or browser interop, run both smoke tasks and exercise the staged page. The guarded loader patch must still redirect instantiation and publish Graal's `javaNative` symbol; the host unwraps that symbol before every JSPI export call. Graal's throwable conversion helper and the raw application/continuation metadata exports must remain compatible. Missing JSPI or incompatible hooks produce errors rather than fake continuations.

Retained WPILib-derived code is covered by [WPILib-License.md](WPILib-License.md), also included under `licenses/` in the distributed source bundle.
