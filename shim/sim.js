import { createWasmContinuations } from "./continuations.js";
import { errorText } from "./errors.js";
import { createNetworkTables } from "./networktables.js";

const PERIOD_MS = 20;
const PORT_COUNT = 6;
const CONTROLS_KEY = "wasmbot.controls.v1";
const MODE_SELECTION_KEY = "wasmbot.opmodes.v1";

// Browser standard buttons -> WPILib Gamepad.Button (zero-based).
const STANDARD_BUTTONS = [
    0, 1, 2, 3, 9, 10, -1, -1, 4, 6, 7, 8, 11, 12, 13, 14, 5
];
const BUTTON_LABELS = [
    "Face down (A)",
    "Face right (B)",
    "Face left (X)",
    "Face up (Y)",
    "Back",
    "Guide",
    "Start",
    "Left stick",
    "Right stick",
    "Left bumper",
    "Right bumper",
    "D-pad up",
    "D-pad down",
    "D-pad left",
    "D-pad right"
];
const AXIS_LABELS = {
    leftXNeg: "Left stick left",
    leftXPos: "Left stick right",
    leftYNeg: "Left stick up",
    leftYPos: "Left stick down",
    rightXNeg: "Right stick left",
    rightXPos: "Right stick right",
    rightYNeg: "Right stick up",
    rightYPos: "Right stick down",
    leftTrigger: "Left trigger",
    rightTrigger: "Right trigger"
};
const MODE_LABELS = {
    unknown: "None",
    teleop: "Teleop",
    autonomous: "Autonomous",
    utility: "Utility"
};
const KEY_LABELS = {
    Shift: "Shift",
    Control: "Ctrl",
    Alt: "Alt",
    Meta: "Meta",
    Space: "Space",
    Enter: "Enter",
    Backspace: "Backspace",
    Delete: "Delete",
    Tab: "Tab",
    Home: "Home",
    End: "End",
    PageUp: "PgUp",
    PageDown: "PgDn",
    Insert: "Insert",
    ArrowUp: "↑",
    ArrowDown: "↓",
    ArrowLeft: "←",
    ArrowRight: "→",
    Semicolon: ";",
    Quote: "'",
    Comma: ",",
    Period: ".",
    Slash: "/",
    Backslash: "\\",
    BracketLeft: "[",
    BracketRight: "]",
    Minus: "-",
    Equal: "=",
    Backquote: "`"
};

function defaultControls() {
    return {
        keyboardEnabled: true,
        keyboardPort: 0,
        allianceKey: "Shift",
        invertLeftY: false,
        invertRightY: false,
        deadzone: 0.08,
        gamepadPorts: {},
        axes: {
            leftXNeg: "KeyA",
            leftXPos: "KeyD",
            leftYNeg: "KeyW",
            leftYPos: "KeyS",
            rightXNeg: "KeyQ",
            rightXPos: "KeyE",
            rightYNeg: "KeyR",
            rightYPos: "KeyF",
            leftTrigger: "KeyZ",
            rightTrigger: "KeyC"
        },
        buttons: [
            "KeyJ",
            "KeyK",
            "KeyU",
            "KeyI",
            "KeyB",
            "Home",
            "Enter",
            "KeyV",
            "KeyN",
            "Digit1",
            "Digit3",
            "ArrowUp",
            "ArrowDown",
            "ArrowLeft",
            "ArrowRight"
        ]
    };
}

function clone(value) {
    return JSON.parse(JSON.stringify(value));
}

function normalizeControls(value) {
    const defaults = defaultControls();
    if (!value || typeof value !== "object") return defaults;
    const result = clone(defaults);
    if (typeof value.keyboardEnabled === "boolean")
        result.keyboardEnabled = value.keyboardEnabled;
    if (Number.isInteger(value.keyboardPort))
        result.keyboardPort = clamp(value.keyboardPort, 0, PORT_COUNT - 1);
    if (typeof value.allianceKey === "string")
        result.allianceKey = value.allianceKey;
    if (typeof value.invertLeftY === "boolean")
        result.invertLeftY = value.invertLeftY;
    if (typeof value.invertRightY === "boolean")
        result.invertRightY = value.invertRightY;
    if (Number.isFinite(value.deadzone))
        result.deadzone = clamp(value.deadzone, 0, 0.5);
    if (value.gamepadPorts && typeof value.gamepadPorts === "object") {
        for (const [index, port] of Object.entries(value.gamepadPorts)) {
            if (Number.isInteger(port) && port >= -1 && port < PORT_COUNT) {
                result.gamepadPorts[index] = port;
            }
        }
    }
    if (value.axes && typeof value.axes === "object") {
        for (const key of Object.keys(result.axes)) {
            if (typeof value.axes[key] === "string")
                result.axes[key] = value.axes[key];
        }
    }
    if (Array.isArray(value.buttons)) {
        for (let i = 0; i < result.buttons.length; i++) {
            if (typeof value.buttons[i] === "string")
                result.buttons[i] = value.buttons[i];
        }
    }
    return result;
}

function loadControls() {
    try {
        const raw = localStorage.getItem(CONTROLS_KEY);
        return raw ? normalizeControls(JSON.parse(raw)) : defaultControls();
    } catch {
        return defaultControls();
    }
}

function saveControls(value) {
    try {
        localStorage.setItem(CONTROLS_KEY, JSON.stringify(value));
    } catch {
        // Storage can be unavailable in private or sandboxed frames.
    }
}

function loadModeSelections() {
    try {
        const raw = localStorage.getItem(MODE_SELECTION_KEY);
        const parsed = raw ? JSON.parse(raw) : {};
        return parsed && typeof parsed === "object" ? parsed : {};
    } catch {
        return {};
    }
}

function saveModeSelections(value) {
    try {
        localStorage.setItem(MODE_SELECTION_KEY, JSON.stringify(value));
    } catch {
        // Storage can be unavailable in private or sandboxed frames.
    }
}

function clamp(value, min, max) {
    return Math.min(max, Math.max(min, value));
}

function normalizeCode(event) {
    const code = event.code || event.key || "";
    if (code === "ShiftLeft" || code === "ShiftRight") return "Shift";
    if (code === "ControlLeft" || code === "ControlRight") return "Control";
    if (code === "AltLeft" || code === "AltRight") return "Alt";
    if (code === "MetaLeft" || code === "MetaRight") return "Meta";
    return code;
}

function keyLabel(code) {
    if (!code) return "Unbound";
    if (KEY_LABELS[code]) return KEY_LABELS[code];
    if (code.startsWith("Key")) return code.slice(3);
    if (code.startsWith("Digit")) return code.slice(5);
    if (code.startsWith("Numpad")) return "Num " + code.slice(6);
    if (/^F\d{1,2}$/.test(code)) return code;
    return code;
}

function labelFor(value) {
    return MODE_LABELS[value] ?? value;
}

function escapeHtml(value) {
    return String(value)
        .replaceAll("&", "&amp;")
        .replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;")
        .replaceAll('"', "&quot;")
        .replaceAll("'", "&#39;");
}

let controls = loadControls();
let modeValue = "unknown";
let allianceValue = "blue";
let opModes = [];
let selectedOpModeId = 0;
let ready = false;
let captureBinding = null;
const modeSelections = loadModeSelections();
const keys = new Set();
const boundCodes = new Set();
const keyboard = {
    name: "Keyboard",
    axes: Array(6).fill(0),
    buttons: Array(15).fill(false)
};
const pads = Array.from({ length: PORT_COUNT }, () => ({
    name: "",
    axes: Array(6).fill(0),
    buttons: Array(15).fill(false)
}));
const driverStation = {
    enabled: false,
    mode: "unknown",
    alliance: "blue",
    opMode: 0,
    opModeName: "",
    controllers: Array(PORT_COUNT).fill(null)
};
let nowNanos = 0;

// Keep the existing object: frontend adapters may already hold a reference to it.
const hooks = (globalThis.wasmbot ??= {});
hooks.nowNanos = () => nowNanos;
hooks.driverStation = readDriverStation;
hooks.onInstallHooks ??= [];
const networkTables = createNetworkTables(() => nowNanos);
networkTables.install();

const style = document.createElement("style");
style.textContent = `
    .wb-root {
        box-sizing: border-box;
        --wb-main: #652633;
        --wb-main-dark: #4c1c26;
        --wb-silver: #c0c0c0;
        --wb-paper: #ffffff;
        --wb-ink: #1c1114;
        --wb-muted: #6b5b60;
        --wb-blue: #1e4f9c;
        --wb-red: #a51c30;
        --wb-radius: 5px;
        color-scheme: light;
        position: fixed;
        right: 12px;
        bottom: 12px;
        z-index: 10000;
        max-width: min(390px, calc(100vw - 24px));
        max-height: calc(100vh - 24px);
        display: flex;
        flex-direction: column;
        overflow: hidden;
        border: 2px solid var(--wb-silver);
        border-radius: var(--wb-radius);
        background: var(--wb-paper);
        color: var(--wb-ink);
        box-shadow: 0 12px 32px rgb(28 17 20 / 28%), 0 2px 6px rgb(28 17 20 / 18%);
        font: 500 13px/1.45 "Merriweather Sans", "Helvetica Neue", -apple-system, "Segoe UI", Roboto, sans-serif;
        text-align: left;
        letter-spacing: 0;
    }
    .wb-root *, .wb-root *::before, .wb-root *::after { box-sizing: border-box; }
    .wb-root button, .wb-root input, .wb-root select { font: inherit; color: inherit; }
    .wb-root h2, .wb-root h3, .wb-root p { margin: 0; }
    .wb-header {
        display: flex;
        align-items: center;
        justify-content: flex-end;
        gap: 10px;
        padding: 12px 12px 11px;
        border-bottom: 2px solid var(--wb-silver);
        background: var(--wb-main);
        color: #fff;
        cursor: pointer;
    }
    .wb-root.is-collapsed { cursor: pointer; }
    .wb-root.is-collapsed .wb-header { border-bottom: 0; }
    .wb-header button, .wb-header input, .wb-header select { cursor: pointer; }
    .wb-brand { display: flex; align-items: center; gap: 10px; min-width: 0; }
    .wb-title { font-size: 15px; font-weight: 900; line-height: 1.15; letter-spacing: 0.01em; }
    .wb-subtitle { margin-top: 2px; font-size: 10px; font-weight: 600; letter-spacing: 0.08em; text-transform: uppercase; opacity: 0.82; }
    .wb-header-actions { display: flex; align-items: center; gap: 8px; flex: none; justify-content: flex-end; width: 100%; }
    .wb-status {
        display: inline-flex;
        align-items: center;
        gap: 5px;
        padding: 3px 8px;
        border: 1px solid rgb(255 255 255 / 45%);
        border-radius: 999px;
        background: rgb(255 255 255 / 12%);
        color: #fff;
        font-size: 10px;
        font-weight: 700;
        letter-spacing: 0.04em;
        text-transform: uppercase;
        white-space: nowrap;
        width: 100%;
    }
    .wb-status::before {
        content: "";
        width: 6px;
        height: 6px;
        border-radius: 50%;
        background: #f5c451;
    }
    .wb-status[data-state="running"]::before { background: #7fe0a0; }
    .wb-status[data-state="error"]::before { background: #ff6b7a; }
    .wb-icon-button {
        display: grid;
        place-items: center;
        width: 28px;
        height: 28px;
        padding: 0;
        border: 1px solid rgb(255 255 255 / 45%);
        border-radius: var(--wb-radius);
        background: transparent;
        color: #fff;
        cursor: pointer;
        font-size: 16px;
        font-weight: 900;
        line-height: 1;
    }
    .wb-icon-button:hover { background: rgb(255 255 255 / 16%); }
    .wb-root :focus-visible {
        outline: 3px solid rgb(101 38 51 / 35%);
        outline-offset: 2px;
    }
    .wb-header :focus-visible { outline-color: rgb(255 255 255 / 70%); }
    .wb-body {
        display: flex;
        flex-direction: column;
        gap: 0;
        overflow: auto;
        overscroll-behavior: contain;
    }
    .wb-root.is-collapsed .wb-body { display: none; }
    .wb-enabled {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: 12px;
        padding: 12px;
        border-bottom: 1px solid var(--wb-silver);
        background: #faf8f8;
    }
    .wb-enabled-label { font-size: 14px; font-weight: 800; }
    .wb-enabled-hint { margin-top: 1px; color: var(--wb-muted); font-size: 11px; }
    .wb-switch {
        appearance: none;
        position: relative;
        flex: none;
        width: 46px;
        height: 26px;
        margin: 0;
        border: 2px solid var(--wb-silver);
        border-radius: 999px;
        background: #fff;
        cursor: pointer;
        transition: background 120ms ease, border-color 120ms ease;
    }
    .wb-switch::before {
        content: "";
        position: absolute;
        top: 2px;
        left: 2px;
        width: 18px;
        height: 18px;
        border-radius: 50%;
        background: var(--wb-silver);
        transition: transform 120ms ease, background 120ms ease;
    }
    .wb-switch:checked { border-color: var(--wb-main); background: var(--wb-main); }
    .wb-switch:checked::before { transform: translateX(20px); background: #fff; }
    .wb-switch:disabled { cursor: not-allowed; opacity: 0.55; }
    .wb-section { padding: 12px; border-bottom: 1px solid var(--wb-silver); }
    .wb-section:last-child { border-bottom: 0; }
    .wb-section-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-bottom: 8px; }
    .wb-section-title {
        font-size: 11px;
        font-weight: 900;
        letter-spacing: 0.09em;
        text-transform: uppercase;
        color: var(--wb-main);
    }
    .wb-field { display: grid; gap: 5px; margin-top: 10px; }
    .wb-field:first-child { margin-top: 0; }
    .wb-field-label { font-size: 11px; font-weight: 800; letter-spacing: 0.02em; }
    .wb-segmented {
        display: grid;
        grid-auto-flow: column;
        grid-auto-columns: 1fr;
        gap: 2px;
        padding: 2px;
        border: 2px solid var(--wb-silver);
        border-radius: var(--wb-radius);
        background: var(--wb-silver);
    }
    .wb-segmented button {
        min-height: 30px;
        padding: 4px 6px;
        border: 0;
        border-radius: 3px;
        background: #fff;
        color: var(--wb-main);
        cursor: pointer;
        font-size: 12px;
        font-weight: 800;
    }
    .wb-segmented button:hover { background: #f3edee; }
    .wb-segmented button[aria-checked="true"] { background: var(--wb-main); color: #fff; }
    .wb-segmented.wb-alliance button[data-value="red"][aria-checked="true"] { background: var(--wb-red); }
    .wb-segmented.wb-alliance button[data-value="blue"][aria-checked="true"] { background: var(--wb-blue); }
    .wb-select {
        width: 100%;
        min-height: 34px;
        padding: 5px 8px;
        border: 2px solid var(--wb-silver);
        border-radius: var(--wb-radius);
        background: #fff;
        color: var(--wb-ink);
        font-weight: 700;
        cursor: pointer;
    }
    .wb-select:disabled { cursor: not-allowed; background: #f6f4f4; color: var(--wb-muted); }
    .wb-select:focus-visible { border-color: var(--wb-main); }
    .wb-hint { color: var(--wb-muted); font-size: 11px; line-height: 1.4; }
    .wb-description { margin-top: 4px; padding: 6px 8px; border-left: 3px solid var(--wb-main); background: #faf8f8; color: var(--wb-muted); font-size: 11px; }
    .wb-button {
        display: inline-flex;
        align-items: center;
        justify-content: center;
        min-height: 32px;
        padding: 4px 10px;
        border: 2px solid var(--wb-main);
        border-radius: var(--wb-radius);
        background: var(--wb-main);
        color: #fff;
        cursor: pointer;
        font-size: 11px;
        font-weight: 900;
    }
    .wb-button:hover { background: var(--wb-main-dark); border-color: var(--wb-main-dark); }
    .wb-button.secondary { border-color: var(--wb-silver); background: #fff; color: var(--wb-main); }
    .wb-button.secondary:hover { border-color: var(--wb-main); background: #faf8f8; }
    .wb-button.ghost { border-color: transparent; background: transparent; color: var(--wb-main); }
    .wb-button.ghost:hover { background: #f3edee; }
    .wb-button:disabled { cursor: not-allowed; opacity: 0.5; }
    .wb-ports { display: grid; gap: 4px; margin: 0; padding: 0; list-style: none; }
    .wb-port {
        display: grid;
        grid-template-columns: minmax(22px, auto) minmax(0, 1fr) auto;
        align-items: center;
        gap: 7px;
        padding: 5px 7px;
        border: 1px solid #e2dcdd;
        border-radius: var(--wb-radius);
        background: #fbfafa;
        color: var(--wb-muted);
        font-size: 11px;
    }
    .wb-port.is-connected { border-color: var(--wb-silver); background: #fff; color: var(--wb-ink); }
    .wb-port-index { display: grid; place-items: center; min-width: 22px; height: 22px; padding: 0 4px; border-radius: 3px; background: var(--wb-silver); color: #fff; font-size: 10px; font-weight: 900; }
    .wb-port.is-connected .wb-port-index { background: var(--wb-main); }
    .wb-port-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-weight: 700; }
    .wb-port-tag { font-size: 9px; font-weight: 800; letter-spacing: 0.05em; text-transform: uppercase; }
    .wb-port.is-connected .wb-port-tag { color: var(--wb-main); }
    .wb-help { padding: 10px 12px 12px; color: var(--wb-muted); font-size: 10.5px; line-height: 1.5; }
    .wb-help kbd {
        display: inline-block;
        min-width: 17px;
        padding: 1px 4px;
        border: 1px solid var(--wb-silver);
        border-bottom-width: 2px;
        border-radius: 3px;
        background: #fff;
        color: var(--wb-ink);
        font: 800 10px/1.3 inherit;
        text-align: center;
    }
    .wb-error {
        display: none;
        padding: 9px 12px;
        border-bottom: 1px solid var(--wb-silver);
        background: #fff0f1;
        color: #8c1220;
        font-size: 11px;
        white-space: pre-wrap;
        word-break: break-word;
    }
    .wb-root.has-error .wb-error { display: block; }

    dialog.wb-dialog {
        box-sizing: border-box;
        --wb-main: #652633;
        --wb-main-dark: #4c1c26;
        --wb-silver: #c0c0c0;
        --wb-paper: #ffffff;
        --wb-ink: #1c1114;
        --wb-muted: #6b5b60;
        --wb-blue: #1e4f9c;
        --wb-red: #a51c30;
        --wb-radius: 5px;
        width: min(760px, calc(100vw - 24px));
        max-height: min(88vh, 860px);
        margin: auto;
        padding: 0;
        overflow: hidden;
        border: 2px solid var(--wb-main);
        border-radius: 8px;
        background: #fff;
        color: var(--wb-ink);
        box-shadow: 0 24px 64px rgb(28 17 20 / 35%);
        font: 500 13px/1.45 "Merriweather Sans", "Helvetica Neue", -apple-system, "Segoe UI", Roboto, sans-serif;
        color-scheme: light;
    }
    dialog.wb-dialog *, dialog.wb-dialog *::before, dialog.wb-dialog *::after { box-sizing: border-box; }
    dialog.wb-dialog h2, dialog.wb-dialog p { margin: 0; }
    dialog.wb-dialog::backdrop { background: rgb(28 17 20 / 52%); backdrop-filter: blur(2px); }
    .wb-dialog form { display: flex; flex-direction: column; max-height: inherit; }
    .wb-dialog-header {
        display: flex;
        align-items: flex-start;
        justify-content: space-between;
        gap: 12px;
        padding: 14px 16px 12px;
        background: var(--wb-main);
        color: #fff;
    }
    .wb-dialog-title { font-size: 17px; font-weight: 900; line-height: 1.2; }
    .wb-dialog-subtitle { margin-top: 3px; font-size: 11px; opacity: 0.84; }
    .wb-dialog-close {
        flex: none;
        width: 30px;
        height: 30px;
        padding: 0;
        border: 1px solid rgb(255 255 255 / 45%);
        border-radius: var(--wb-radius);
        background: transparent;
        color: #fff;
        cursor: pointer;
        font-size: 18px;
        line-height: 1;
    }
    .wb-dialog-close:hover { background: rgb(255 255 255 / 16%); }
    .wb-dialog-body { flex: 1; overflow: auto; padding: 16px; }
    .wb-dialog-footer {
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        justify-content: flex-end;
        gap: 8px;
        padding: 12px 16px;
        border-top: 2px solid var(--wb-silver);
        background: #faf8f8;
    }
    .wb-dialog-footer .wb-spacer { flex: 1; }
    .wb-dialog h3 {
        margin: 0 0 8px;
        color: var(--wb-main);
        font-size: 12px;
        font-weight: 900;
        letter-spacing: 0.08em;
        text-transform: uppercase;
    }
    .wb-dialog h3:not(:first-child) { margin-top: 18px; }
    .wb-form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px 18px; }
    .wb-form-row { display: flex; align-items: center; justify-content: space-between; gap: 10px; min-height: 32px; }
    .wb-form-row > span { font-size: 12px; font-weight: 700; }
    .wb-form-row small { display: block; margin-top: 1px; color: var(--wb-muted); font-size: 10px; font-weight: 500; }
    .wb-dialog select {
        min-width: 110px;
        min-height: 32px;
        padding: 4px 7px;
        border: 2px solid var(--wb-silver);
        border-radius: var(--wb-radius);
        background: #fff;
        font-weight: 700;
    }
    .wb-dialog select:focus-visible { border-color: var(--wb-main); }
    .wb-dialog input[type="range"] { width: 130px; accent-color: var(--wb-main); }
    .wb-dialog input[type="checkbox"] { width: 18px; height: 18px; accent-color: var(--wb-main); }
    .wb-bindings { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 6px 18px; }
    .wb-binding { display: flex; align-items: center; justify-content: space-between; gap: 8px; min-height: 30px; }
    .wb-binding span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 12px; }
    .wb-key {
        flex: none;
        min-width: 88px;
        min-height: 28px;
        padding: 2px 7px;
        border: 2px solid var(--wb-silver);
        border-radius: 4px;
        background: #fff;
        color: var(--wb-main);
        cursor: pointer;
        font-size: 11px;
        font-weight: 800;
    }
    .wb-key:hover, .wb-key:focus-visible { border-color: var(--wb-main); }
    .wb-key.is-capturing { border-style: dashed; border-color: var(--wb-main); background: #faf8f8; }
    .wb-note { margin-top: 8px; padding: 8px 10px; border-left: 3px solid var(--wb-silver); background: #faf8f8; color: var(--wb-muted); font-size: 10.5px; line-height: 1.5; }
    .wb-empty { padding: 10px; border: 1px dashed var(--wb-silver); border-radius: var(--wb-radius); color: var(--wb-muted); font-size: 11px; text-align: center; }
    @media (max-width: 520px) {
        .wb-form-grid, .wb-bindings { grid-template-columns: 1fr; }
        .wb-root { right: 8px; bottom: 8px; width: calc(100vw - 16px); }
    }

    .wb-hidden { display: none !important; }
`;
document.head.append(style);

const panel = document.createElement("section");
panel.className = "wb-root";
panel.setAttribute("aria-label", "Robot simulator");
panel.innerHTML = `
    <header class="wb-header">
        <div class="wb-header-actions">
            <span class="wb-status" id="status" data-state="loading" role="status" aria-live="polite">Loading robot…</span>
            <button class="wb-icon-button" type="button" data-action="collapse" aria-label="Collapse robot simulator" aria-expanded="true">−</button>
        </div>
    </header>
    <div class="wb-error" data-role="error"></div>
    <div class="wb-body">
        <section class="wb-enabled" aria-labelledby="wb-enable-title">
            <div>
                <div class="wb-enabled-label" id="wb-enable-title">Robot enabled</div>
                <div class="wb-enabled-hint" data-role="enabled-hint">Select a mode, then enable</div>
            </div>
            <input class="wb-switch" type="checkbox" data-role="enable" aria-labelledby="wb-enable-title" disabled />
        </section>
        <section class="wb-section" aria-labelledby="wb-station-title">
            <h3 class="wb-section-title" id="wb-station-title">Driver station</h3>
            <div class="wb-field">
                <span class="wb-field-label" id="wb-mode-label">Robot mode</span>
                <div class="wb-segmented" role="radiogroup" aria-labelledby="wb-mode-label" data-role="mode">
                    <button type="button" data-value="teleop" role="radio" aria-checked="false">Teleop</button>
                    <button type="button" data-value="autonomous" role="radio" aria-checked="false">Auto</button>
                    <button type="button" data-value="utility" role="radio" aria-checked="false">Utility</button>
                    <button type="button" data-value="unknown" role="radio" aria-checked="true">None</button>
                </div>
            </div>
            <div class="wb-field">
                <span class="wb-field-label" id="wb-alliance-label">Alliance</span>
                <div class="wb-segmented wb-alliance" role="radiogroup" aria-labelledby="wb-alliance-label" data-role="alliance">
                    <button type="button" data-value="blue" role="radio" aria-checked="true">Blue</button>
                    <button type="button" data-value="red" role="radio" aria-checked="false">Red</button>
                </div>
            </div>
            <div class="wb-field">
                <label class="wb-field-label" for="wb-opmode">OpMode</label>
                <select class="wb-select" id="wb-opmode" data-role="opmode"></select>
                <p class="wb-hint" data-role="opmode-hint">No opmodes published.</p>
                <p class="wb-description" data-role="opmode-description" hidden></p>
            </div>
        </section>
        <section class="wb-section" aria-labelledby="wb-controllers-title">
            <div class="wb-section-head">
                <h3 class="wb-section-title" id="wb-controllers-title">Controllers</h3>
                <button class="wb-button secondary" type="button" data-action="controls">Configure controls</button>
            </div>
            <ul class="wb-ports" data-role="ports"></ul>
        </section>
        <p class="wb-help" data-role="help"></p>
    </div>
`;

const dialog = document.createElement("dialog");
dialog.className = "wb-dialog";
dialog.setAttribute("aria-labelledby", "wb-controls-title");
dialog.innerHTML = `
    <form method="dialog">
        <header class="wb-dialog-header">
            <div>
                <h2 class="wb-dialog-title" id="wb-controls-title">Controls &amp; devices</h2>
                <p class="wb-dialog-subtitle">Keyboard bindings, gamepad ports, and input shaping. Changes stay in this browser.</p>
            </div>
            <button class="wb-dialog-close" type="button" data-action="controls-cancel" aria-label="Close controls">×</button>
        </header>
        <div class="wb-dialog-body" data-role="controls-body"></div>
        <footer class="wb-dialog-footer">
            <button class="wb-button ghost" type="button" data-action="controls-reset">Restore defaults</button>
            <span class="wb-spacer"></span>
            <button class="wb-button secondary" type="button" data-action="controls-cancel">Cancel</button>
            <button class="wb-button" type="button" data-action="controls-save">Save controls</button>
        </footer>
    </form>
`;

document.body.append(panel, dialog);

const statusBadge = panel.querySelector("#status");
const errorBox = panel.querySelector('[data-role="error"]');
const enable = panel.querySelector('[data-role="enable"]');
const enabledHint = panel.querySelector('[data-role="enabled-hint"]');
const modeGroup = panel.querySelector('[data-role="mode"]');
const allianceGroup = panel.querySelector('[data-role="alliance"]');
const opModeSelect = panel.querySelector('[data-role="opmode"]');
const opModeHint = panel.querySelector('[data-role="opmode-hint"]');
const opModeDescription = panel.querySelector(
    '[data-role="opmode-description"]'
);
const portsList = panel.querySelector('[data-role="ports"]');
const help = panel.querySelector('[data-role="help"]');
const controlsBody = dialog.querySelector('[data-role="controls-body"]');

let lastPortKey = "";

function createPortRow(indexText, nameText, tagText, connected) {
    const row = document.createElement("li");
    row.className = connected ? "wb-port is-connected" : "wb-port";
    const index = document.createElement("span");
    index.className = "wb-port-index";
    index.textContent = indexText;
    const name = document.createElement("span");
    name.className = "wb-port-name";
    name.textContent = nameText;
    const tag = document.createElement("span");
    tag.className = "wb-port-tag";
    tag.textContent = tagText;
    row.append(index, name, tag);
    return row;
}

function setStatus(text, state) {
    statusBadge.textContent = text;
    statusBadge.dataset.state = state;
}

function setError(message) {
    errorBox.textContent = message;
    panel.classList.toggle("has-error", Boolean(message));
}

function updateEnabledUi() {
    const enabled = enable.checked;
    driverStation.enabled = enabled;
    enabledHint.textContent = enabled
        ? `Enabled ${modeValue === "unknown" ? "" : "in " + labelFor(modeValue) + " mode"}`
        : "Select a mode, then enable";
    if (ready)
        setStatus(enabled ? "Robot Enabled" : "Simulation Running", "running");
}

function renderModes() {
    for (const button of modeGroup.querySelectorAll("button[data-value]")) {
        button.setAttribute(
            "aria-checked",
            String(button.dataset.value === modeValue)
        );
    }
}

function renderAlliance() {
    for (const button of allianceGroup.querySelectorAll("button[data-value]")) {
        button.setAttribute(
            "aria-checked",
            String(button.dataset.value === allianceValue)
        );
    }
}

function applyControls() {
    boundCodes.clear();
    for (const code of Object.values(controls.axes))
        if (code) boundCodes.add(code);
    for (const code of controls.buttons) if (code) boundCodes.add(code);
    if (controls.allianceKey) boundCodes.add(controls.allianceKey);
    if (!controls.keyboardEnabled) {
        boundCodes.delete(controls.allianceKey);
    }
    updateHelp();
}

function updateHelp() {
    help.innerHTML = `
        <a href="https://github.com/frcvan/wasmbot" target="_blank"><strong>wasmBot</strong></a>: Web simulator by <a href="https://www.teamvan.org" target="_blank"><strong>Team V.A.N. · FRC 12148</strong></a>
    `;
}

function connectedGamepads() {
    const padsNow = navigator.getGamepads?.() ?? [];
    return [...padsNow].filter((gamepad) => gamepad?.connected);
}

function gamepadPort(gamepad) {
    const configured = controls.gamepadPorts[String(gamepad.index)];
    if (Number.isInteger(configured)) return configured;
    return gamepad.index < PORT_COUNT ? gamepad.index : -1;
}

function renderOpModes() {
    const matches = opModes.filter((opMode) => opMode.mode === modeValue);
    const previous = selectedOpModeId;
    const stored = modeSelections[modeValue];
    let selection = 0;

    if (modeValue !== "unknown") {
        const byName =
            stored && matches.find((opMode) => opMode.name === stored);
        if (byName) {
            selection = byName.id;
        } else if (stored === "") {
            selection = 0;
        } else {
            selection = matches[0]?.id ?? 0;
        }
        if (
            selection !== 0 &&
            !matches.some((opMode) => opMode.id === selection)
        ) {
            selection = 0;
        }
    }

    opModeSelect.replaceChildren();
    const noneOption = document.createElement("option");
    noneOption.value = "0";
    if (modeValue === "unknown") {
        noneOption.textContent = "None - use the mode lifecycle";
    } else {
        noneOption.textContent = "Select a robot mode first";
        opModeSelect.parentElement.hidden = false;
        opModeSelect.parentElement.classList.remove("wb-hidden");
    }
    opModeSelect.append(noneOption);

    const groups = new Map();
    for (const opMode of matches) {
        const group = opMode.group || "";
        if (!groups.has(group)) groups.set(group, []);
        groups.get(group).push(opMode);
    }
    for (const [group, entries] of groups) {
        const parent = group
            ? opModeSelect.appendChild(document.createElement("optgroup"))
            : opModeSelect;
        if (group) parent.label = group;
        for (const opMode of entries) {
            const option = document.createElement("option");
            option.value = String(opMode.id);
            option.textContent = opMode.name;
            option.dataset.name = opMode.name;
            if (opMode.textColor || opMode.backgroundColor) {
                option.style.color = opMode.textColor || "";
                option.style.background = opMode.backgroundColor || "";
            }
            parent.append(option);
        }
    }

    selectedOpModeId = selection;
    opModeSelect.value = String(selection);
    opModeSelect.disabled =
        !ready || modeValue === "unknown" || matches.length === 0;

    if (modeValue === "unknown") {
        opModeHint.textContent =
            "Choose Teleop, Auto, or Utility to see published opmodes.";
        opModeSelect.parentElement.hidden = true;
        opModeSelect.parentElement.classList.add("wb-hidden");
    } else if (matches.length === 0) {
        opModeHint.textContent = `No ${labelFor(modeValue).toLowerCase()} opmodes published.`;
        opModeSelect.parentElement.hidden = true;
        opModeSelect.parentElement.classList.add("wb-hidden");
    } else {
        opModeHint.textContent = `${matches.length} ${labelFor(modeValue).toLowerCase()} opmode${matches.length === 1 ? "" : "s"} published.`;
        opModeSelect.parentElement.hidden = false;
        opModeSelect.parentElement.classList.remove("wb-hidden");
    }

    const selected = matches.find((opMode) => opMode.id === selectedOpModeId);
    if (selected?.description) {
        opModeDescription.textContent = selected.description;
        opModeDescription.hidden = false;
    } else {
        opModeDescription.textContent = "";
        opModeDescription.hidden = true;
    }

    if (previous !== selection) {
        driverStation.opMode = selection;
        driverStation.opModeName = selected?.name ?? "";
    }
}

function setMode(value) {
    if (modeValue === value) return;
    modeValue = value;
    driverStation.mode = value;
    keys.clear();
    renderModes();
    renderOpModes();
    updateEnabledUi();
}

function setAlliance(value) {
    allianceValue = value;
    renderAlliance();
}

function renderPorts(names, keyboardPort) {
    const key = `${keyboardPort}|${names.join("|")}`;
    if (key === lastPortKey) return;
    lastPortKey = key;

    const rows = [];
    for (let port = 0; port < PORT_COUNT; port++) {
        const name = names[port] || "Disconnected";
        if (name !== "Disconnected") {
            const isKeyboard = name === "Keyboard" && keyboardPort === port;
            rows.push(
                createPortRow(
                    String(port),
                    name,
                    isKeyboard ? "Keyboard" : "Gamepad",
                    true
                )
            );
            continue;
        }

        // Collapse a contiguous run of disconnected ports into a single row.
        let end = port;
        while (
            end + 1 < PORT_COUNT &&
            (names[end + 1] || "Disconnected") === "Disconnected"
        ) {
            end++;
        }
        const count = end - port + 1;
        rows.push(
            createPortRow(
                count === 1 ? String(port) : `${port}–${end}`,
                "Disconnected",
                count === 1 ? "—" : `${count} ports`,
                false
            )
        );
        port = end;
    }
    portsList.replaceChildren(...rows);
}

function controllerAxes(controller) {
    return controller?.axes ?? [];
}

function controllerButtons(controller) {
    return controller?.buttons ?? [];
}

function readDriverStation() {
    const axes = controls.axes;
    const leftYInvert = controls.invertLeftY ? -1 : 1;
    const rightYInvert = controls.invertRightY ? -1 : 1;

    keyboard.axes[0] =
        Number(keys.has(axes.leftXPos)) - Number(keys.has(axes.leftXNeg));
    keyboard.axes[1] =
        (Number(keys.has(axes.leftYPos)) - Number(keys.has(axes.leftYNeg))) *
        leftYInvert;
    keyboard.axes[2] =
        Number(keys.has(axes.rightXPos)) - Number(keys.has(axes.rightXNeg));
    keyboard.axes[3] =
        (Number(keys.has(axes.rightYPos)) - Number(keys.has(axes.rightYNeg))) *
        rightYInvert;
    keyboard.axes[4] = keys.has(axes.leftTrigger) ? 1 : 0;
    keyboard.axes[5] = keys.has(axes.rightTrigger) ? 1 : 0;
    for (let i = 0; i < controls.buttons.length; i++) {
        keyboard.buttons[i] = keys.has(controls.buttons[i]);
    }

    const allianceOverride =
        controls.allianceKey && keys.has(controls.allianceKey);
    driverStation.alliance = allianceOverride ? "red" : allianceValue;
    driverStation.mode = modeValue;
    const selected = opModes.find(
        (opMode) => opMode.id === selectedOpModeId && opMode.mode === modeValue
    );
    driverStation.opMode = selected?.id ?? 0;
    driverStation.opModeName = selected?.name ?? "";
    driverStation.controllers.fill(null);

    const names = Array(PORT_COUNT).fill("Disconnected");
    const keyboardPort = controls.keyboardEnabled ? controls.keyboardPort : -1;

    for (const gamepad of connectedGamepads()) {
        if (gamepad.mapping !== "standard") {
            if (gamepad.index < PORT_COUNT)
                names[gamepad.index] = "Unsupported mapping";
            continue;
        }
        const port = gamepadPort(gamepad);
        if (port < 0 || port >= PORT_COUNT) continue;
        const pad = pads[port];
        pad.name = gamepad.id;
        const rawAxes = controllerAxes(gamepad);
        const deadzone = controls.deadzone;
        const rawButtons = controllerButtons(gamepad);
        pad.axes[0] = applyDeadzone(rawAxes[0], deadzone);
        pad.axes[1] = applyDeadzone(rawAxes[1], deadzone) * leftYInvert;
        pad.axes[2] = applyDeadzone(rawAxes[2], deadzone);
        pad.axes[3] = applyDeadzone(rawAxes[3], deadzone) * rightYInvert;
        pad.axes[4] = clamp(rawButtons[6]?.value ?? 0, 0, 1);
        pad.axes[5] = clamp(rawButtons[7]?.value ?? 0, 0, 1);
        pad.buttons.fill(false);
        for (let i = 0; i < STANDARD_BUTTONS.length; i++) {
            if (STANDARD_BUTTONS[i] >= 0) {
                pad.buttons[STANDARD_BUTTONS[i]] =
                    rawButtons[i]?.pressed ?? false;
            }
        }
        driverStation.controllers[port] = pad;
        names[port] = pad.name;
    }

    if (
        keyboardPort >= 0 &&
        keyboardPort < PORT_COUNT &&
        !driverStation.controllers[keyboardPort]
    ) {
        driverStation.controllers[keyboardPort] = keyboard;
        names[keyboardPort] = "Keyboard";
    }

    renderPorts(names, keyboardPort);
    return driverStation;
}

function applyDeadzone(value, deadzone) {
    if (typeof value !== "number" || !Number.isFinite(value)) return 0;
    const clamped = clamp(value, -1, 1);
    return Math.abs(clamped) < deadzone ? 0 : clamped;
}

function editing(target) {
    return (
        target instanceof Element &&
        target.closest(
            'textarea, select, input:not([type="checkbox"]):not([type="button"]), [contenteditable]:not([contenteditable="false"])'
        )
    );
}

function disable() {
    keys.clear();
    enable.checked = false;
    driverStation.enabled = false;
    updateEnabledUi();
}

addEventListener("keydown", (event) => {
    if (dialog.open || captureBinding) return;
    if (editing(event.target) || event.ctrlKey || event.metaKey || event.altKey)
        return;
    const code = normalizeCode(event);
    if (!boundCodes.has(code)) return;
    event.preventDefault();
    keys.add(code);
});

addEventListener("keyup", (event) => {
    keys.delete(normalizeCode(event));
});

addEventListener("blur", disable);
addEventListener("visibilitychange", () => {
    if (document.hidden) disable();
});
// A held key must not remain active when focus moves into a telemetry search field.
addEventListener("focusin", (event) => {
    if (editing(event.target)) keys.clear();
});
addEventListener("gamepadconnected", () => {
    if (dialog.open) renderControls();
});
addEventListener("gamepaddisconnected", () => {
    if (dialog.open) renderControls();
});

enable.addEventListener("change", () => {
    if (enable.checked && !ready) {
        enable.checked = false;
        return;
    }
    updateEnabledUi();
});

modeGroup.addEventListener("click", (event) => {
    const button = event.target.closest("button[data-value]");
    if (button) setMode(button.dataset.value);
});

allianceGroup.addEventListener("click", (event) => {
    const button = event.target.closest("button[data-value]");
    if (button) setAlliance(button.dataset.value);
});

opModeSelect.addEventListener("change", () => {
    const id = Number(opModeSelect.value) || 0;
    selectedOpModeId = id;
    const selected = opModes.find(
        (opMode) => opMode.id === id && opMode.mode === modeValue
    );
    modeSelections[modeValue] = selected?.name ?? "";
    saveModeSelections(modeSelections);
    driverStation.opMode = id;
    driverStation.opModeName = selected?.name ?? "";
    if (selected?.description) {
        opModeDescription.textContent = selected.description;
        opModeDescription.hidden = false;
    } else {
        opModeDescription.textContent = "";
        opModeDescription.hidden = true;
    }
});

function setCollapsed(collapsed) {
    panel.classList.toggle("is-collapsed", collapsed);
    const button = panel.querySelector('button[data-action="collapse"]');
    button.textContent = collapsed ? "+" : "−";
    button.setAttribute("aria-expanded", String(!collapsed));
    button.setAttribute(
        "aria-label",
        collapsed ? "Expand robot simulator" : "Collapse robot simulator"
    );
}

panel.addEventListener("click", (event) => {
    const action = event.target.closest("button[data-action]")?.dataset.action;
    if (action === "controls") {
        openControls();
        return;
    }
    if (action === "collapse") {
        setCollapsed(!panel.classList.contains("is-collapsed"));
        return;
    }
    // Collapsed: the whole widget is a single expand target.
    // Expanded: the header is the collapse target.
    if (panel.classList.contains("is-collapsed")) {
        setCollapsed(false);
    } else if (event.target.closest(".wb-header")) {
        setCollapsed(true);
    }
});

hooks.setOpModes = (value) => {
    try {
        const parsed = typeof value === "string" ? JSON.parse(value) : value;
        opModes = Array.isArray(parsed)
            ? parsed
                  .filter(
                      (opMode) => opMode && Number.isFinite(Number(opMode.id))
                  )
                  .map((opMode) => ({ ...opMode, id: Number(opMode.id) }))
            : [];
    } catch {
        opModes = [];
    }
    renderOpModes();
};

// Rumble arrives as four 16-bit handle values. The standard gamepad API exposes only the two
// grip motors, so trigger values fold into them instead of being dropped.
hooks.setRumble = (
    port,
    leftRumble,
    rightRumble,
    leftTriggerRumble,
    rightTriggerRumble
) => {
    const gamepad = connectedGamepads().find(
        (candidate) => candidate.mapping === "standard" && gamepadPort(candidate) === port
    );
    const actuator = gamepad?.vibrationActuator;
    if (!actuator) return;
    const strong = clamp(Math.max(leftRumble, leftTriggerRumble) / 65535, 0, 1);
    const weak = clamp(Math.max(rightRumble, rightTriggerRumble) / 65535, 0, 1);
    if (strong === 0 && weak === 0) {
        actuator.reset?.();
        return;
    }
    try {
        void actuator
            .playEffect("dual-rumble", {
                startDelay: 0,
                duration: 250,
                strongMagnitude: strong,
                weakMagnitude: weak
            })
            ?.catch(() => {});
    } catch {
        // Browsers without a dual-rumble effect ignore haptic requests.
    }
};

function openControls() {
    keys.clear();
    draft = clone(controls);
    captureBinding = null;
    renderControls();
    dialog.showModal();
}

function connectedGamepadOptions() {
    return connectedGamepads().sort((a, b) => a.index - b.index);
}

function renderControls() {
    const gamepads = connectedGamepadOptions();
    const padRows =
        gamepads.length === 0
            ? `<p class="wb-empty">No gamepads detected. Connect one and press a button, or use the keyboard.</p>`
            : gamepads
                  .map((gamepad) => {
                      const assigned =
                          draft.gamepadPorts[String(gamepad.index)] ??
                          (gamepad.index < PORT_COUNT ? gamepad.index : -1);
                      const options = [
                          '<option value="-1">Unassigned</option>'
                      ];
                      for (let port = 0; port < PORT_COUNT; port++) {
                          options.push(
                              `<option value="${port}"${assigned === port ? " selected" : ""}>Port ${port}</option>`
                          );
                      }
                      return `<div class="wb-form-row">
                <span>${escapeHtml(gamepad.id)}<small>Browser index ${gamepad.index}</small></span>
                <select data-gamepad-index="${gamepad.index}" aria-label="Driver Station port for ${escapeHtml(gamepad.id)}">${options.join("")}</select>
            </div>`;
                  })
                  .join("");

    const axisRows = Object.entries(AXIS_LABELS)
        .map(([key, label]) => bindingRow(key, label))
        .join("");
    const buttonRows = BUTTON_LABELS.map((label, index) =>
        bindingRow(String(index), label, "buttons")
    ).join("");
    const portOptions = Array.from(
        { length: PORT_COUNT },
        (_, port) =>
            `<option value="${port}"${draft.keyboardPort === port ? " selected" : ""}>Port ${port}</option>`
    ).join("");

    controlsBody.innerHTML = `
        <h3>Keyboard</h3>
        <div class="wb-form-grid">
            <div class="wb-form-row">
                <span>Keyboard controller<small>Feeds the selected Driver Station port</small></span>
                <input type="checkbox" data-setting="keyboardEnabled"${draft.keyboardEnabled ? " checked" : ""} aria-label="Enable keyboard controller" />
            </div>
            <div class="wb-form-row">
                <span>Keyboard port</span>
                <select data-setting="keyboardPort" aria-label="Keyboard Driver Station port">${portOptions}</select>
            </div>
            <div class="wb-form-row">
                <span>Alliance override<small>Hold while driving to select red</small></span>
                ${bindingButton("allianceKey", draft.allianceKey, "alliance")}
            </div>
        </div>

        <h3>Sticks &amp; triggers</h3>
        <div class="wb-bindings">${axisRows}</div>

        <h3>Buttons</h3>
        <div class="wb-bindings">${buttonRows}</div>

        <h3>Gamepads</h3>
        <div class="wb-form-grid">
            <div class="wb-form-row">
                <span>Stick deadzone <output data-role="deadzone-value">${Math.round(draft.deadzone * 100)}%</output></span>
                <input type="range" min="0" max="0.5" step="0.01" value="${draft.deadzone}" data-setting="deadzone" aria-label="Stick deadzone" />
            </div>
            <div class="wb-form-row">
                <span>Invert left Y</span>
                <input type="checkbox" data-setting="invertLeftY"${draft.invertLeftY ? " checked" : ""} aria-label="Invert left Y axis" />
            </div>
            <div class="wb-form-row">
                <span>Invert right Y</span>
                <input type="checkbox" data-setting="invertRightY"${draft.invertRightY ? " checked" : ""} aria-label="Invert right Y axis" />
            </div>
        </div>
        <div style="margin-top:8px">${padRows}</div>
        <p class="wb-note">Standard browser gamepads map face buttons, bumpers, triggers, sticks, and the D-pad automatically. Drag the deadzone down to 0 for raw sticks; the robot's own deadbands still apply.</p>
    `;
}

function bindingRow(key, label, section = "axes") {
    const value =
        section === "buttons" ? draft.buttons[Number(key)] : draft.axes[key];
    return `<div class="wb-binding"><span>${label}</span>${bindingButton(key, value, section)}</div>`;
}

function bindingButton(key, value, section = "axes") {
    const index = section === "buttons" ? Number(key) : null;
    const isCapturing =
        captureBinding &&
        captureBinding.section === section &&
        (section === "buttons"
            ? captureBinding.index === index
            : captureBinding.key === key);
    const text = isCapturing ? "Press a key…" : escapeHtml(keyLabel(value));
    return `<button type="button" class="wb-key${isCapturing ? " is-capturing" : ""}" data-binding-section="${section}" data-binding-key="${key}"${index === null ? "" : ` data-binding-index="${index}"`} aria-label="Set binding for ${escapeHtml(key)}">${text}</button>`;
}

let draft = clone(controls);

controlsBody.addEventListener("click", (event) => {
    const button = event.target.closest(".wb-key");
    if (!button) {
        if (captureBinding) {
            captureBinding = null;
            renderControls();
        }
        return;
    }
    const section = button.dataset.bindingSection;
    captureBinding =
        section === "buttons"
            ? { section, index: Number(button.dataset.bindingIndex) }
            : { section, key: button.dataset.bindingKey };
    renderControls();
});

controlsBody.addEventListener("change", (event) => {
    const select = event.target.closest("select[data-gamepad-index]");
    if (select) {
        draft.gamepadPorts[select.dataset.gamepadIndex] = Number(select.value);
        return;
    }
    const setting = event.target.closest("[data-setting]")?.dataset.setting;
    if (!setting) return;
    if (event.target.type === "checkbox") {
        draft[setting] = event.target.checked;
    } else if (event.target.type === "range") {
        draft[setting] = Number(event.target.value);
        const output = controlsBody.querySelector(
            '[data-role="deadzone-value"]'
        );
        if (output) output.textContent = `${Math.round(draft.deadzone * 100)}%`;
    } else if (setting === "keyboardPort") {
        draft.keyboardPort = Number(event.target.value);
    }
});

dialog.addEventListener("close", () => {
    captureBinding = null;
    keys.clear();
});

dialog.addEventListener("click", (event) => {
    const action = event.target.closest("button[data-action]")?.dataset.action;
    if (!action) return;
    if (action === "controls-save") {
        controls = normalizeControls(draft);
        saveControls(controls);
        applyControls();
        keys.clear();
        renderOpModes();
        dialog.close();
    } else if (action === "controls-cancel") {
        captureBinding = null;
        dialog.close();
    } else if (action === "controls-reset") {
        draft = defaultControls();
        captureBinding = null;
        renderControls();
    }
});

function setBinding(code) {
    if (!captureBinding) return;
    if (captureBinding.section === "buttons") {
        draft.buttons[captureBinding.index] = code;
    } else if (captureBinding.section === "alliance") {
        draft.allianceKey = code;
    } else {
        draft.axes[captureBinding.key] = code;
    }
    captureBinding = null;
    renderControls();
}

dialog.querySelector("form").addEventListener("submit", (event) => {
    event.preventDefault();
});

addEventListener(
    "keydown",
    (event) => {
        if (!captureBinding) return;
        event.preventDefault();
        event.stopImmediatePropagation();
        if (event.key === "Escape") {
            captureBinding = null;
            renderControls();
            return;
        }
        if (event.key === "Backspace" || event.key === "Delete") {
            setBinding("");
            return;
        }
        setBinding(normalizeCode(event));
    },
    true
);

renderModes();
renderAlliance();
applyControls();
renderOpModes();

try {
    hooks.continuations = createWasmContinuations();
    const builtFile = new URL("./wasm.js", import.meta.url).href;
    await import(builtFile);

    const config = new GraalVM.Config();
    config.wasm_path = `${builtFile}.wasm`;
    await GraalVM.run({}, config);
    hooks.start();
    hooks.onInstallHooks.forEach((hook) => hook());
    ready = true;
    enable.disabled = false;
    setStatus("Simulation Running", "running");
    renderOpModes();
    updateEnabledUi();
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
            accumulatedMs = Math.min(
                accumulatedMs + time - previousTime,
                5 * PERIOD_MS
            );
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
            setError(`Simulation stopped: ${errorText(error)}`);
            setStatus("Simulation Stopped", "error");
            console.error("[wasmbot] Simulation stopped:", errorText(error));
        } finally {
            ticking = false;
        }
    }, PERIOD_MS);
} catch (error) {
    ready = false;
    disable();
    enable.disabled = true;
    setError(`Simulation failed to start: ${errorText(error)}`);
    setStatus("Simulation Failed", "error");
    console.error("[wasmbot] Simulation failed to start:", errorText(error));
}
