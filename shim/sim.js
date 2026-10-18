import { createWasmContinuations } from "./continuations.js";
import { errorText } from "./errors.js";
import { createNetworkTables } from "./networktables.js";

const PERIOD_MS = 20;
const PORT_COUNT = 6;
const keys = new Set();
const keyboard = {
    name: "Keyboard",
    axes: Array(6).fill(0),
    buttons: Array(15).fill(false)
};
// Browser standard buttons -> WPILib Gamepad.Button (zero-based).
const standardButtons = [0, 1, 2, 3, 9, 10, -1, -1, 4, 6, 7, 8, 11, 12, 13, 14, 5];
const keyboardButtons = ["j", "k", "u", "i", "b", "home", "enter", "v", "n", "1", "3",
    "arrowup", "arrowdown", "arrowleft", "arrowright"];
const controlKeys = new Set([
    "w", "a", "s", "d", "q", "e", "r", "f", "z", "c", "shift", ...keyboardButtons
]);
const pads = Array.from({ length: PORT_COUNT }, () => ({
    name: "",
    axes: Array(6).fill(0),
    buttons: Array(15).fill(false)
}));
const driverStation = {
    enabled: false,
    mode: "unknown",
    alliance: "blue",
    controllers: Array(PORT_COUNT).fill(null)
};
let nowNanos = 0;

// Keep the existing object: frontend adapters may already hold a reference to it.
const hooks = globalThis.wasmbot ??= {};
hooks.nowNanos = () => nowNanos;
hooks.driverStation = readDriverStation;
hooks.onInstallHooks ??= [];
const networkTables = createNetworkTables(() => nowNanos);
networkTables.install();

const panel = document.createElement("details");
panel.style.cssText = "position:fixed;right:12px;bottom:12px;z-index:10000;max-width:360px;padding:10px;border:1px solid GrayText;border-radius:6px;background:Canvas;color:CanvasText;font:13px system-ui;line-height:1.5;color-scheme:light dark";
const summary = document.createElement("summary");
summary.textContent = "Robot simulator — loading";
panel.append(summary);
const enableLabel = document.createElement("label");
const enable = document.createElement("input");
enable.type = "checkbox";
enable.disabled = true;
enableLabel.append(enable, " Enable robot ");
panel.append(enableLabel);
const mode = document.createElement("select");
mode.setAttribute("aria-label", "Robot mode");
for (const value of ["unknown", "teleop", "autonomous", "utility"]) {
    const option = document.createElement("option");
    option.value = value;
    option.textContent = `${value} mode`;
    mode.append(option);
}
panel.append(mode);
const alliance = document.createElement("select");
alliance.setAttribute("aria-label", "Robot alliance");
for (const color of ["blue", "red"]) {
    const option = document.createElement("option");
    option.value = color;
    option.textContent = `${color} alliance`;
    alliance.append(option);
}
panel.append(alliance);
const inputStatus = document.createElement("div");
panel.append(inputStatus);
const help = document.createElement("p");
help.style.marginBottom = "0";
help.textContent = "Select a robot mode, then enable. Connect a standard browser gamepad (ports 0–5), or use keyboard on port 0: W/S left Y, A/D left X, Q/E right X, R/F right Y, Z/C triggers. J/K/U/I face buttons, 1/3 bumpers, B/Enter back/start, Home guide, V/N stick clicks, arrows D-pad. Shift selects red alliance. Blur or hiding this page disables the robot without changing the selected mode. Click outside text fields to drive.";
panel.append(help);
document.body.append(panel);
enable.addEventListener("change", () => {
    driverStation.enabled = enable.checked;
});

function editing(target) {
    return target instanceof Element && target.closest(
        'textarea, select, input:not([type="checkbox"]):not([type="button"]), [contenteditable]:not([contenteditable="false"])'
    );
}

addEventListener("keydown", (event) => {
    const key = event.key.toLowerCase();
    if (!controlKeys.has(key) || editing(event.target) || event.ctrlKey || event.metaKey || event.altKey) return;
    event.preventDefault();
    keys.add(key);
});
addEventListener("keyup", (event) => {
    keys.delete(event.key.toLowerCase());
});
function disable() {
    keys.clear();
    enable.checked = false;
    driverStation.enabled = false;
}
addEventListener("blur", disable);
addEventListener("visibilitychange", () => {
    if (document.hidden) disable();
});
// A held key must not remain active when focus moves into a telemetry search field.
addEventListener("focusin", (event) => {
    if (editing(event.target)) keys.clear();
});

function axis(negative, positive) {
    return Number(keys.has(positive)) - Number(keys.has(negative));
}

function readDriverStation() {
    keyboard.axes[0] = axis("a", "d");
    keyboard.axes[1] = axis("w", "s");
    keyboard.axes[2] = axis("q", "e");
    keyboard.axes[3] = axis("r", "f");
    keyboard.axes[4] = Number(keys.has("z"));
    keyboard.axes[5] = Number(keys.has("c"));
    for (let i = 0; i < keyboardButtons.length; i++) {
        keyboard.buttons[i] = keys.has(keyboardButtons[i]);
    }
    driverStation.alliance = keys.has("shift") ? "red" : alliance.value;
    driverStation.mode = mode.value;
    driverStation.controllers.fill(null);
    let names = "";
    const gamepads = navigator.getGamepads?.() ?? [];
    for (const gamepad of gamepads) {
        if (!gamepad?.connected || gamepad.index >= PORT_COUNT) continue;
        if (gamepad.mapping !== "standard") {
            names += `Port ${gamepad.index}: unsupported non-standard mapping. `;
            continue;
        }
        const pad = pads[gamepad.index];
        pad.name = gamepad.id;
        // Browser axes are LX, LY, RX, RY; triggers are analog buttons 6 and 7.
        for (let i = 0; i < 4; i++) pad.axes[i] = gamepad.axes[i] ?? 0;
        pad.axes[4] = gamepad.buttons[6]?.value ?? 0;
        pad.axes[5] = gamepad.buttons[7]?.value ?? 0;
        pad.buttons.fill(false);
        for (let i = 0; i < standardButtons.length; i++) {
            if (standardButtons[i] >= 0) {
                pad.buttons[standardButtons[i]] = gamepad.buttons[i]?.pressed ?? false;
            }
        }
        driverStation.controllers[gamepad.index] = pad;
        names += `Port ${gamepad.index}: ${pad.name}. `;
    }
    if (!driverStation.controllers[0]) {
        driverStation.controllers[0] = keyboard;
        names += "Port 0: keyboard.";
    }
    if (inputStatus.textContent !== names) inputStatus.textContent = names;
    return driverStation;
}

try {
    hooks.continuations = createWasmContinuations();
    const builtFile = new URL("./wasm.js", import.meta.url).href;
    await import(builtFile);

    const config = new GraalVM.Config();
    config.wasm_path = `${builtFile}.wasm`;
    await GraalVM.run({}, config);
    hooks.start();
    hooks.onInstallHooks.forEach((hook) => hook());
    enable.disabled = false;
    panel.open = true;
    summary.textContent = "Robot simulator — running";
    readDriverStation();
    console.log("[wasmbot] wasmbot started");

    let ticking = false;
    let previousTime = performance.now();
    let accumulatedMs = 0;
    const timer = setInterval(async () => {
        if (ticking) return;
        ticking = true;
        try {
            const time = performance.now();
            // Bounded catch-up keeps 20 ms robot steps without replaying a background-tab backlog.
            accumulatedMs = Math.min(accumulatedMs + time - previousTime, 5 * PERIOD_MS);
            previousTime = time;
            while (accumulatedMs >= PERIOD_MS) {
                accumulatedMs -= PERIOD_MS;
                nowNanos += PERIOD_MS * 1e6;
                await hooks.tick();
            }
        } catch (error) {
            clearInterval(timer);
            disable();
            enable.disabled = true;
            summary.textContent = `Robot stopped: ${errorText(error)}`;
            console.error("[wasmbot] Robot stopped:", errorText(error));
        } finally {
            ticking = false;
        }
    }, PERIOD_MS);
} catch (error) {
    disable();
    panel.open = true;
    summary.textContent = `Robot failed to start: ${errorText(error)}`;
    console.error("[wasmbot] Robot failed to start:", errorText(error));
}
