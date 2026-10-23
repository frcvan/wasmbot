// JSPI-capable Node/Bun harness for a consumer's GraalVM Web Image WASM image.
//
// Usage:
//   node <library>/tools/wasm-debug/run-wasm.mjs [buildDir] [imageName]
//
// buildDir defaults to the consumer cwd's build/native/nativeCompile; imageName
// defaults to wasm. The directory must contain <imageName>.js and <imageName>.js.wasm.
//
// It shims the handful of globals the browser page (shim/sim.js) provides,
// imports the GraalVM glue, runs the image, then calls wasmbot.start()/wasmbot.tick().
// Java throwables use Java's complete printStackTrace output; native JS errors retain their stacks.
import path from "node:path";
import assert from "node:assert/strict";
import { pathToFileURL } from "node:url";
import { createWasmContinuations } from "../../shim/continuations.js";
import { errorText } from "../../shim/errors.js";
import { createNetworkTables } from "../../shim/networktables.js";

const args = process.argv.slice(2);
if (args.length > 2 || args.some((arg) => arg.startsWith("--"))) {
    throw new Error("Usage: run-wasm.mjs [buildDir] [imageName]");
}
const [buildDir, imageName = "wasm"] = args;
const dir = path.resolve(
    buildDir ?? path.join(process.cwd(), "build/native/nativeCompile")
);
const imagePath = path.join(dir, `${imageName}.js`);

globalThis.document = {
    currentScript: { src: pathToFileURL(imagePath).href }
};

let nowNanos = 0;
const telemetry = new Map();
const ds = { enabled: false, mode: "teleop", alliance: "blue", leftX: 0, leftY: 0, rightX: 0 };
globalThis.wasmbot = {
    nowNanos: () => nowNanos,
    driverStation: () => ds,
    continuations: createWasmContinuations()
};
const networkTables = createNetworkTables();
networkTables.install();
networkTables.listen((event) => {
    if (event.kind !== "value") return;
    assert.equal(
        typeof event.topic.name,
        "string",
        "telemetry paths must be JS strings"
    );
    telemetry.set(event.topic.name, event.value);
});

await import(pathToFileURL(imagePath).href);

const config = new globalThis.GraalVM.Config();
config.wasm_path = `${imagePath}.wasm`;
await globalThis.GraalVM.run({}, config);

let started = false;
try {
    let ticks = 0;
    const tick = async (count) => {
        for (let i = 0; i < count; i++) {
            nowNanos += 20_000_000;
            await globalThis.wasmbot.tick();
            ticks++;
        }
    };
    globalThis.wasmbot.start();
    globalThis.wasmbot.start(); // Repeated startup must not rebuild or reset the robot.
    started = true;
    await tick(25);
    ds.enabled = true;
    await tick(100);
    for (const [path, value] of telemetry) {
        if (Array.isArray(value)) {
            assert.ok(
                Object.keys(value).every((key) => /^\d+$/.test(key)),
                `${path}: malformed array`
            );
        }
    }
    const samples = [];
    for (let i = 0; i < 1000; i++) {
        const started = performance.now();
        await tick(1);
        samples.push(performance.now() - started);
    }
    samples.sort((a, b) => a - b);
    console.log(
        "WASM_SMOKE_OK",
        JSON.stringify({
            ticks,
            telemetryEntries: telemetry.size,
            tickMedianMs: samples[500],
            tickP95Ms: samples[950],
            tickMaxMs: samples[999]
        })
    );
} catch (error) {
    console.error((started ? "TICK_FAILED: " : "START_FAILED: ") + errorText(error));
    process.exitCode = 1;
}
