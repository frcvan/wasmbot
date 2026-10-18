import { createWasmContinuations } from "./continuations.js";
import { errorText } from "./errors.js";
import { createNetworkTables } from "./networktables.js";

const currentScriptUrl = import.meta.url;
const currentScriptDir = new URL(currentScriptUrl).pathname
    .split("/")
    .slice(0, -1)
    .join("/");

const PERIOD_SECONDS = 0.02;
const keys = new Set();
const telemetry = new Map();
const dirtyPaths = new Set();
const driverStation = {
    enabled: false,
    alliance: "blue",
    leftX: 0,
    leftY: 0,
    rightX: 0
};
let nowNanos = 0;

const priorOnInstallHooks = globalThis.wasmbot?.onInstallHooks ?? [];
const hooks = (globalThis.wasmbot = {
    nowNanos: () => nowNanos,
    driverStation: () => {
        driverStation.enabled = true;
        driverStation.alliance = keys.has("shift") ? "red" : "blue";
        driverStation.leftX = axis("a", "d");
        driverStation.leftY = axis("w", "s");
        driverStation.rightX = axis("q", "e");
        return driverStation;
    },
    onInstallHooks: priorOnInstallHooks
});
const networkTables = createNetworkTables(() => nowNanos);
networkTables.install();
networkTables.listen((event) => {
    if (event.kind !== "value") return;
    telemetry.set(event.topic.name, event.value);
    dirtyPaths.add(event.topic.name);
});

addEventListener("keydown", (event) => {
    keys.add(event.key.toLowerCase());
});
addEventListener("keyup", (event) => {
    keys.delete(event.key.toLowerCase());
});
addEventListener("blur", () => {
    keys.clear();
});

function axis(negative, positive) {
    return (keys.has(positive) ? 1 : 0) - (keys.has(negative) ? 1 : 0);
}

function paintKeys() {
    for (const key of ["w", "a", "s", "d", "q", "e"]) {
    }
}

try {
    hooks.continuations = createWasmContinuations();
    const BUILT_FILE = `${currentScriptDir}/wasm.js`;
    await import(`${BUILT_FILE}`);

    const config = new GraalVM.Config();
    config.wasm_path = `${BUILT_FILE}.wasm`;
    await GraalVM.run({}, config);

    console.log("[wasmbot] starting wasmbot...");

    hooks.start();
    hooks.onInstallHooks.forEach((hook) => hook());

    console.log("[wasmbot] wasmbot started");

    // status.textContent = "Running";
    let ticking = false;
    const timer = setInterval(async () => {
        if (ticking) return;
        ticking = true;
        try {
            nowNanos += PERIOD_SECONDS * 1e9;
            await hooks.tick();
            // paintTelemetry();
        } catch (error) {
            clearInterval(timer);
            // status.textContent = `Robot stopped: ${errorText(error)}`;
            console.error("[wasmbot] Robot stopped:", errorText(error));
        } finally {
            ticking = false;
        }
    }, PERIOD_SECONDS * 1000);
} catch (error) {
    // status.textContent = `Robot failed to start: ${errorText(error)}`;
    console.error("[wasmbot] Robot failed to start:", errorText(error));
}

