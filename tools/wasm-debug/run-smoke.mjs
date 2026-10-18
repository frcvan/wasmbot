// Build with ./gradlew nativeSmokeCompile, then run with a JSPI-capable Node or Bun.
// Usage: node <library>/tools/wasm-debug/run-smoke.mjs [buildDir] [imageName] [--startup-failure]
// Defaults: consumer cwd's build/native/nativeSmokeCompile and imageName=wasm.
import assert from "node:assert/strict";
import path from "node:path";
import { pathToFileURL } from "node:url";
import { createWasmContinuations } from "../../shim/continuations.js";
import { errorText } from "../../shim/errors.js";
import { createNetworkTables } from "../../shim/networktables.js";

const dir = path.resolve(
    process.argv[2] ??
        path.join(process.cwd(), "build/native/nativeSmokeCompile")
);
const imageName = process.argv[3] ?? "wasm";
const startupFailureProbe = process.argv[4] === "--startup-failure";
const imagePath = path.join(dir, `${imageName}.js`);
globalThis.document = { currentScript: { src: pathToFileURL(imagePath).href } };
const values = Object.create(null);
const ntEvents = [];
let now = 123_456_789;
const ds = { enabled: false, alliance: "blue", leftX: 0, leftY: 0, rightX: 0 };
globalThis.wasmbot = {
    startupFailureProbe,
    nowNanos: () => now,
    driverStation: () => ds,
    continuations: createWasmContinuations()
};
const networkTables = createNetworkTables(() => now);
networkTables.install();
networkTables.listen((event) => {
    if (
        event.topic.name.startsWith("/Review/NTStruct") ||
        event.topic.name.startsWith("/.schema/")
    ) {
        ntEvents.push({
            kind: event.kind,
            name: event.topic.name,
            type: event.topic.type,
            timestamp: event.timestamp,
            value: event.value
        });
    }
    if (event.kind !== "value") return;
    assert.equal(typeof event.topic.name, "string");
    values[event.topic.name] = event.value;
});
await import(pathToFileURL(imagePath).href);
const config = new GraalVM.Config();
config.wasm_path = `${imagePath}.wasm`;
await GraalVM.run({}, config);

if (startupFailureProbe) {
    let original;
    assert.throws(() => globalThis.wasmbot.start(), (error) => {
        original = error[globalThis.wasmbot.javaNative];
        assert.ok(original, "startup must expose the original Java throwable");
        const text = errorText(error);
        assert.match(text, /^java\.lang\.AssertionError: startup-probe-error\n/);
        assert.match(text, /\bat org\.teamvan\.wasmbot\.testing\.WasmSmoke\.failStartup\(/);
        assert.match(text, /Suppressed: java\.lang\.UnsupportedOperationException: startup-probe-suppressed/);
        assert.match(text, /Caused by: java\.lang\.IllegalArgumentException: startup-probe-cause/);
        console.error("[wasmbot] Robot failed to start:", text);
        return true;
    });
    const sameFailure = (error) => {
        assert.equal(error[globalThis.wasmbot.javaNative], original);
        assert.match(errorText(error), /^java\.lang\.AssertionError: startup-probe-error\n/);
        return true;
    };
    assert.throws(() => globalThis.wasmbot.start(), sameFailure);
    await assert.rejects(globalThis.wasmbot.tick(), sameFailure);
    console.log("STARTUP_ERROR_SMOKE_OK: Java class, frames, cause, suppressed, repeated-start/tick identity");
    process.exit(0);
}

const expected = {
    Boolean: true,
    Number: 1.25,
    String: "hello",
    Booleans: [true, false],
    Shorts: [-3, 7],
    Ints: [-100000, 200000],
    Floats: [1.25, -2.5],
    Doubles: [1.25, -2.5],
    Strings: ["a", "b"],
    Empty: [],
    SafeLong: 9007199254740991,
    UnsafeLong: "9007199254740992",
    Longs: [
        0,
        9007199254740991,
        "9007199254740992",
        "-9223372036854775808",
        "9223372036854775807"
    ],
    Raw: "00ff10",
    EmptyStructs: ""
};
for (const [key, value] of Object.entries(expected)) {
    assert.deepEqual(values[`/Telemetry/Review/${key}`], value, key);
}
function struct(x, y, rotation) {
    const bytes = Buffer.alloc(24);
    bytes.writeDoubleLE(x, 0);
    bytes.writeDoubleLE(y, 8);
    bytes.writeDoubleLE(rotation, 16);
    return bytes.toString("hex");
}
assert.equal(values["/Telemetry/Review/Struct"], struct(3, 4, -1));
assert.equal(values["/Telemetry/Review/Structs"], struct(1.25, -2.5, 0.5) + struct(3, 4, -1));
function field(tag, value) {
    const bytes = Buffer.alloc(9);
    bytes[0] = tag;
    bytes.writeDoubleLE(value, 1);
    return bytes;
}
const translation = Buffer.concat([field(9, 3), field(17, 4)]);
const rotation = field(9, -1);
const protobuf = Buffer.concat([
    Buffer.from([10, translation.length]),
    translation,
    Buffer.from([18, rotation.length]),
    rotation
]);
assert.equal(values["/Telemetry/Review/Proto"], protobuf.toString("hex"));
const currentStructEvents = ntEvents.filter(
    (event) => event.name === "/Review/NTStructCurrent"
);
const timedStructEvents = ntEvents.filter(
    (event) => event.name === "/Review/NTStructTimed"
);
assert.deepEqual(currentStructEvents.map((event) => event.kind), [
    "announce", "value", "value", "unannounce"
]);
assert.deepEqual(timedStructEvents.map((event) => event.kind), [
    "announce", "value", "value", "unannounce", "announce", "value", "unannounce"
]);
assert.ok(
    [...currentStructEvents, ...timedStructEvents].every(
        (event) => event.type === "struct:Pose2d"
    ),
    "the SDK type string must not acquire a duplicate struct: prefix"
);
assert.deepEqual(
    currentStructEvents.filter((event) => event.kind === "value")
        .map((event) => [event.timestamp, event.value]),
    [
        [123_456.789, struct(1.25, -2.5, 0.5)],
        [123_456.789, struct(3, 4, -1)]
    ],
    "default values must not overwrite data; Consumer.accept must publish at current NT time"
);
assert.deepEqual(
    timedStructEvents.filter((event) => event.kind === "value")
        .map((event) => [event.timestamp, event.value]),
    [
        [2_000_000, struct(1.25, -2.5, 0.5)],
        [4_000_000, struct(3, 4, -1)],
        [123_456.789, struct(1.25, -2.5, 0.5)]
    ],
    "nanoseconds must convert to microseconds; stale/closed writes must drop, peers survive, reopening resets data"
);
const ntSnapshot = networkTables.snapshot();
assert.equal(ntSnapshot.find(topic => topic.name === "/Telemetry/Review/String")?.properties.unit, "m");
assert.equal(ntSnapshot.find(topic => topic.name === "/Telemetry/Review/Structs")?.type, "struct:Pose2d[]");
assert.ok(
    !ntSnapshot.some((topic) => topic.name.startsWith("/Review/NTStruct")),
    "closing the last struct publisher must unannounce its topic"
);
for (const [name, schema] of Object.entries({
    Pose2d: "Translation2d translation;Rotation2d rotation",
    Translation2d: "double x;double y",
    Rotation2d: "double value"
})) {
    const schemaPath = `/.schema/struct:${name}`;
    const topic = ntSnapshot.find((candidate) => candidate.name === schemaPath);
    assert.ok(topic, `missing nested struct schema ${schemaPath}`);
    assert.equal(topic.type, "structschema");
    assert.equal(topic.properties.retained, true);
    assert.ok(topic.value instanceof Uint8Array, "schemas must be raw UTF-8 bytes for NT4 clients");
    assert.equal(Buffer.from(topic.value).toString("utf8"), schema);
    assert.equal(
        ntEvents.filter((event) => event.name === schemaPath && event.kind === "value").length,
        1,
        "repeated publishers must not republish struct schemas"
    );
}
assert.equal(
    typeof globalThis.wasmbot.probe?.beginYield,
    "function",
    "native smoke entry did not finish"
);
globalThis.wasmbot.start();
globalThis.wasmbot.start(); // The standard Robot factory and simulationInit must only run once.
let attemptedTicks = 0;
let completedTicks = 0;
function assertLifecycle() {
    assert.equal(
        values["/Telemetry/Review/WasmbotInConstructor"],
        true,
        "wasmbot property must be true in the constructor"
    );
    assert.equal(
        values["/Telemetry/Review/RobotInstances"],
        1,
        "Robot must be constructed exactly once"
    );
    assert.equal(
        values["/Telemetry/Review/SimulationInitCalls"],
        1,
        "simulationInit must run exactly once"
    );
    assert.equal(
        values["/Telemetry/Review/RobotPeriodicTicks"],
        attemptedTicks,
        "every tick must call robotPeriodic"
    );
    assert.equal(
        values["/Telemetry/Review/SchedulerTicks"],
        attemptedTicks,
        "robotPeriodic must own exactly one Scheduler.run"
    );
    assert.equal(
        values["/Telemetry/Review/CompletedPeriodicTicks"],
        completedTicks,
        "only successful scheduler ticks reach the end of robotPeriodic"
    );
    assert.equal(
        values["/Telemetry/Review/SimulationPeriodicTicks"],
        completedTicks,
        "simulationPeriodic follows successful robotPeriodic calls"
    );
}
assertLifecycle();
async function tick(count = 1) {
    for (let i = 0; i < count; i++) {
        now += 20_000_000;
        attemptedTicks++;
        await globalThis.wasmbot.tick();
        completedTicks++;
        assertLifecycle();
    }
}
await tick(2); // Disabled cycles still run robotPeriodic, the scheduler, and simulationPeriodic.
ds.leftX = 0.25;
ds.leftY = -0.5;
ds.rightX = 0.75;
await tick();
globalThis.wasmbot.checkControllers(0);
const controllerButtons = (...indices) =>
    Array.from({ length: 26 }, (_, index) => indices.includes(index));
ds.controllers = [
    {
        name: "Primary",
        axes: [0.2, -0.3, 0.4, -0.5, 0.6, 0.7],
        buttons: controllerButtons(0, 1, 11, 14, 25)
    },
    {
        name: "Secondary",
        axes: [-0.8, 0.9, -1, 1, 0.25, 0.75],
        buttons: controllerButtons(0)
    },
    null,
    null,
    null,
    { name: "Last port", axes: [0.1], buttons: controllerButtons(25) }
];
await tick();
globalThis.wasmbot.checkControllers(1);
await tick(2);
globalThis.wasmbot.checkControllers(2);
ds.controllers[0].buttons[1] = false;
await tick(2);
globalThis.wasmbot.checkControllers(3);
ds.controllers = [null, null, null, null, null, null];
await tick();
globalThis.wasmbot.checkControllers(4);
ds.controllers[0] = { name: "Short controller", axes: [-0.2, 0.4], buttons: [true] };
await tick();
globalThis.wasmbot.checkControllers(5);
delete ds.controllers;
await tick();
globalThis.wasmbot.checkControllers(6);
ds.controllers = [
    { name: "NI primary", axes: [0.2, -0.3, 0.4, -0.5, 0.6, 0.7], buttons: controllerButtons(9, 4, 7) },
    { name: "NI secondary", axes: [-0.8, 0.9, -1, 1, 0.25, 0.75], buttons: controllerButtons(10, 6, 8) }
];
await tick();
globalThis.wasmbot.checkControllers(7);
delete ds.controllers;
ds.leftX = 0;
ds.leftY = 0;
ds.rightX = 0;
console.log("CONTROLLER_SMOKE_OK: legacy, six ports, axes/triggers, capabilities, independent latched edges, disconnect/reconnect");
ds.enabled = true;
const state = () => globalThis.wasmbot.probe.state().toString();
globalThis.wasmbot.probe.beginYield();
await tick();
assert.equal(state(), "[first]");
await tick();
assert.equal(state(), "[first, local=42]");
await tick(2);
assert.equal(
    state(),
    "[first, local=42, last=43]",
    "completed command must not restart"
);
globalThis.wasmbot.probe.reschedule();
await tick(3);
assert.equal(
    state(),
    "[first, local=42, last=43, first, local=42, last=43]",
    "rescheduling needs a fresh stack"
);
globalThis.wasmbot.probe.beginPark();
await tick(2);
assert.equal(state(), "[parked]");
globalThis.wasmbot.probe.cancel();
await tick(2);
assert.equal(state(), "[parked, canceled]");
globalThis.wasmbot.probe.beginAwait();
await tick();
assert.equal(state(), "[parent-start, child-start]");
await tick(5);
assert.equal(
    state(),
    "[parent-start, child-start, child-end, parent-end]",
    "await must wait for child completion"
);
globalThis.wasmbot.probe.beginAwait();
await tick();
globalThis.wasmbot.probe.cancel();
await tick(5);
assert.equal(
    state(),
    "[parent-start, child-start]",
    "canceling a parent must cancel its child"
);
globalThis.wasmbot.probe.beginFailure();
await tick();
assert.equal(state(), "[before-throw]");
await assert.rejects(tick(), (error) => {
    const text = errorText(error);
    assert.match(text, /^java\.lang\.IllegalStateException: probe-target-error\n/);
    assert.match(text, /\bat org\.teamvan\.wasmbot\.testing\.WasmSmoke\.begin\(/);
    assert.match(text, /Suppressed: java\.lang\.UnsupportedOperationException: probe-target-suppressed/);
    assert.match(text, /Caused by: java\.lang\.IllegalArgumentException: probe-target-cause/);
    console.error("[wasmbot] Robot stopped:", text);
    return true;
});
assertLifecycle(); // Failed scheduler ticks leave the rest of robotPeriodic and simulationPeriodic uncalled.
await tick();
assert.equal(
    state(),
    "[before-throw, throws, original-error]",
    "the original exception must be emitted once and the failed command removed"
);
const firstTick = tick();
await assert.rejects(globalThis.wasmbot.tick(), /concurrently/);
await firstTick;
assertLifecycle(); // The overlapping call must not execute any lifecycle callbacks or scheduler work.
console.log(
    "NATIVE_SMOKE_OK: RobotBase startup, constructor detection, one-time simulationInit, robotPeriodic-owned scheduler, dynamics, serialization, yielded locals, completion, rescheduling, park/cancel, await, child cancellation, original Java exceptions with class/stack/cause/suppressed across yield, overlapping-tick rejection"
);
