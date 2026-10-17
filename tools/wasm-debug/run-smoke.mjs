// Build with ./gradlew nativeSmokeCompile, then run with a JSPI-capable Node or Bun.
// Usage: node <library>/tools/wasm-debug/run-smoke.mjs [buildDir] [imageName]
// Defaults: consumer cwd's build/native/nativeSmokeCompile and imageName=wasm.
import assert from "node:assert/strict";
import path from "node:path";
import { pathToFileURL } from "node:url";
import { createWasmContinuations } from "../../web/sim/continuations.js";
import { createNetworkTables } from "../../web/sim/networktables.js";

const dir = path.resolve(process.argv[2] ?? path.join(process.cwd(), "build/native/nativeSmokeCompile"));
const imageName = process.argv[3] ?? "wasm";
const imagePath = path.join(dir, `${imageName}.js`);
globalThis.document = { currentScript: { src: pathToFileURL(imagePath).href } };
const values = Object.create(null);
let now = 0;
const ds = { enabled: false, alliance: "blue", leftX: 0, leftY: 0, rightX: 0 };
globalThis.wasmbot = {
  nowNanos: () => now,
  driverStation: () => ds,
  continuations: createWasmContinuations(),
};
const networkTables = createNetworkTables(() => now);
networkTables.install();
networkTables.listen((event) => {
  if (event.kind !== "value") return;
  assert.equal(typeof event.topic.name, "string");
  values[event.topic.name] = event.value;
});
await import(pathToFileURL(imagePath).href);
const config = new GraalVM.Config();
config.wasm_path = `${imagePath}.wasm`;
await GraalVM.run({}, config);

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
  Longs: [0, 9007199254740991, "9007199254740992", "-9223372036854775808", "9223372036854775807"],
  Raw: "00ff10",
  EmptyStructs: [],
};
for (const [key, value] of Object.entries(expected)) {
  assert.deepEqual(values[`/Review/${key}`], value, key);
}
function struct(x, y, rotation) {
  const bytes = Buffer.alloc(24);
  bytes.writeDoubleLE(x, 0);
  bytes.writeDoubleLE(y, 8);
  bytes.writeDoubleLE(rotation, 16);
  return bytes.toString("hex");
}
assert.equal(values["/Review/Struct"], struct(3, 4, -1));
assert.deepEqual(values["/Review/Structs"], [struct(1.25, -2.5, 0.5), struct(3, 4, -1)]);
function field(tag, value) {
  const bytes = Buffer.alloc(9);
  bytes[0] = tag;
  bytes.writeDoubleLE(value, 1);
  return bytes;
}
const translation = Buffer.concat([field(9, 3), field(17, 4)]);
const rotation = field(9, -1);
const protobuf = Buffer.concat([
  Buffer.from([10, translation.length]), translation,
  Buffer.from([18, rotation.length]), rotation,
]);
assert.equal(values["/Review/Proto"], protobuf.toString("hex"));
assert.equal(typeof globalThis.wasmbot.probe?.beginYield, "function", "native smoke entry did not finish");
globalThis.wasmbot.start();
globalThis.wasmbot.start(); // The standard Robot factory and simulationInit must only run once.
let attemptedTicks = 0;
let completedTicks = 0;
function assertLifecycle() {
  assert.equal(values["/Review/WasmbotInConstructor"], true, "wasmbot property must be true in the constructor");
  assert.equal(values["/Review/RobotInstances"], 1, "Robot must be constructed exactly once");
  assert.equal(values["/Review/SimulationInitCalls"], 1, "simulationInit must run exactly once");
  assert.equal(values["/Review/RobotPeriodicTicks"], attemptedTicks, "every tick must call robotPeriodic");
  assert.equal(values["/Review/SchedulerTicks"], attemptedTicks, "robotPeriodic must own exactly one Scheduler.run");
  assert.equal(values["/Review/CompletedPeriodicTicks"], completedTicks, "only successful scheduler ticks reach the end of robotPeriodic");
  assert.equal(values["/Review/SimulationPeriodicTicks"], completedTicks, "simulationPeriodic follows successful robotPeriodic calls");
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
ds.enabled = true;
const state = () => globalThis.wasmbot.probe.state().toString();
globalThis.wasmbot.probe.beginYield();
await tick();
assert.equal(state(), "[first]");
await tick();
assert.equal(state(), "[first, local=42]");
await tick(2);
assert.equal(state(), "[first, local=42, last=43]", "completed command must not restart");
globalThis.wasmbot.probe.reschedule();
await tick(3);
assert.equal(state(), "[first, local=42, last=43, first, local=42, last=43]", "rescheduling needs a fresh stack");
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
assert.equal(state(), "[parent-start, child-start, child-end, parent-end]", "await must wait for child completion");
globalThis.wasmbot.probe.beginAwait();
await tick();
globalThis.wasmbot.probe.cancel();
await tick(5);
assert.equal(state(), "[parent-start, child-start]", "canceling a parent must cancel its child");
globalThis.wasmbot.probe.beginFailure();
await assert.rejects(tick(), (error) => {
  assert.equal(error.getMessage().toString(), "probe-target-error");
  return true;
});
assertLifecycle(); // Failed scheduler ticks leave the rest of robotPeriodic and simulationPeriodic uncalled.
await tick();
assert.equal(state(), "[throws, original-error]", "the original exception must be emitted once and the failed command removed");
const firstTick = tick();
await assert.rejects(globalThis.wasmbot.tick(), /concurrently/);
await firstTick;
assertLifecycle(); // The overlapping call must not execute any lifecycle callbacks or scheduler work.
console.log("NATIVE_SMOKE_OK: RobotBase startup, constructor detection, one-time simulationInit, robotPeriodic-owned scheduler, dynamics, serialization, yielded locals, completion, rescheduling, park/cancel, await, child cancellation, Java exceptions, overlapping-tick rejection");
