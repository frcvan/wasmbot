// Copyright (c) 2021-2026 Littleton Robotics
// http://github.com/Mechanical-Advantage
//
// Use of this source code is governed by a BSD
// license that can be found in the LICENSE file
// at the root directory of this project.

import { execFileSync } from "child_process";
import fs from "fs";
import path from "path";
import { fileURLToPath, pathToFileURL } from "url";

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const root = path.join(scriptDirectory, "build");
const npmCache = path.join(scriptDirectory, ".npm-cache");
const distribution = "LITE";
const ASCOPE_COMMIT = "e28580467";

// This frontend is shipped inside the plugin JAR, which Maven Central charges against a monthly
// release-size quota shared with every other release that month. AdvantageScope is pinned to
// ASCOPE_COMMIT, so the bundled asset set is fixed; keep only what an FRC robot viewer displays and
// drop the rest (FTC fields/robots, older season models, and the AdvantageScope documentation site).
// Update this list together with ASCOPE_COMMIT.
const retainedBundledAssets = new Set([
    "Field2d_2026FRCFieldV2",
    "Field2d_FRCEvergreen",
    "Field3d_2026FRCFieldV2",
    "Joystick_LogitechF310V3",
    "Joystick_PS4ControllerV3",
    "Joystick_XboxControllerBlueV3",
    "Joystick_XboxControllerWhiteV3",
    "Robot_2026FRCKitBotV2"
]);

// The bundled documentation site is gone, so documentation tabs load the hosted manual.
const hostedDocumentationUrl = "https://docs.advantagescope.org";

function log(message) {
    console.log(`[buildLite] ${new Date().toISOString()} ${message}`);
}

if (distribution !== "LITE" && distribution !== "LITEDS") {
    throw new Error(`Unsupported Lite distribution: ${distribution}`);
}

function run(command, args, cwd = scriptDirectory, environment = {}) {
    log(`running ${command} ${args.join(" ")} (cwd: ${cwd})`);
    execFileSync(command, args, {
        cwd,
        env: {
            ...process.env,
            ...environment,
            ASCOPE_DISTRIBUTION: distribution
        },
        stdio: "inherit"
    });
}

function copyDirectory(source, destination) {
    fs.rmSync(destination, { recursive: true, force: true });
    fs.cpSync(source, destination, { recursive: true });
}

function cloneAscope(destination) {
    if (isAdvantageScopeRepository(destination)) {
        log(`reusing AdvantageScope checkout at ${destination}`);
        run("git", ["checkout", "--detach", ASCOPE_COMMIT], destination);
        log(`checked out AdvantageScope revision ${ASCOPE_COMMIT}`);
        return;
    }

    log(`creating a fresh AdvantageScope checkout at ${destination}`);
    fs.rmSync(destination, { recursive: true, force: true });
    fs.mkdirSync(destination, { recursive: true });

    run("git", [
        "clone",
        "--filter=blob:none",
        "--no-checkout",
        "https://github.com/Mechanical-Advantage/AdvantageScope.git",
        destination
    ]);
    run("git", ["checkout", "--detach", ASCOPE_COMMIT], destination);
    log(`checked out AdvantageScope revision ${ASCOPE_COMMIT}`);
}

function isAdvantageScopeRepository(destination) {
    const gitDirectory = path.join(destination, ".git");
    if (!fs.existsSync(gitDirectory)) return false;

    try {
        const origin = execFileSync(
            "git",
            ["config", "--get", "remote.origin.url"],
            { cwd: destination, encoding: "utf8" }
        )
            .trim()
            .replace(/\/+$/, "")
            .replace(/\.git$/, "");
        log(`found existing repository with origin ${origin}`);
        return (
            origin.endsWith("/Mechanical-Advantage/AdvantageScope") ||
            origin.endsWith(":Mechanical-Advantage/AdvantageScope")
        );
    } catch {
        log(
            `existing checkout at ${destination} is not a usable Git repository`
        );
        return false;
    }
}

log(`starting ${distribution} build`);
log(`AdvantageScope source directory: ${root}`);
cloneAscope(root);

function installDeps() {
    log("installing AdvantageScope dependencies");
    run(
        "npm",
        [
            "ci",
            "--cache",
            npmCache,
            "--prefer-offline",
            "--no-audit",
            "--no-fund",
            "--ignore-scripts"
        ],
        root,
        { YOUTUBE_DL_SKIP_DOWNLOAD: "true" }
    );
}

if (!fs.existsSync(path.join(root, "node_modules", ".bin", "tsc"))) {
    installDeps();
} else {
    log("AdvantageScope dependencies already installed");
}

log("generating third-party licenses");
run("node", ["getLicenses.mjs"], root);
log("downloading Tesseract language data");
run("node", ["tesseractLangDownload.mjs"], root);
log("bundling Lite assets");
run("node", ["bundleLiteAssets.mjs"], root);

/** Deletes every bundled asset that the shipped viewer does not need. */
function pruneBundledAssets(assetRoot) {
    if (!fs.existsSync(assetRoot)) return;
    const removed = [];
    for (const entry of fs.readdirSync(assetRoot, { withFileTypes: true })) {
        if (!entry.isDirectory() || retainedBundledAssets.has(entry.name))
            continue;
        fs.rmSync(path.join(assetRoot, entry.name), {
            recursive: true,
            force: true
        });
        removed.push(entry.name);
    }
    if (removed.length > 0) {
        log(`removed unshipped assets: ${removed.sort().join(", ")}`);
    }
}

function patchHubHtml(filePath) {
    const script = `<script type="module" src="../wasm/sim.js"></script>\n`;
    const target = `<script type="module" src="../bundles/hub.js"></script>`;
    const contents = fs.readFileSync(filePath, "utf8");
    if (contents.includes(script.trim())) return;
    if (!contents.includes(target)) {
        throw new Error(`Could not find the hub bundle script in ${filePath}`);
    }
    fs.writeFileSync(filePath, contents.replace(target, script + target));
}

/** Points the satellite window's documentation iframe at the hosted manual. */
function patchSatelliteHtml(filePath) {
    const contents = fs.readFileSync(filePath, "utf8");
    if (contents.includes(hostedDocumentationUrl)) return;

    const target = `<iframe src="../docs/build/index.html"></iframe>`;
    if (!contents.includes(target)) {
        throw new Error(`Could not find the documentation iframe in ${filePath}`);
    }
    fs.writeFileSync(
        filePath,
        contents.replace(
            target,
            `<iframe src="${hostedDocumentationUrl}"></iframe>`
        )
    );
}

function patchNT4(newSource) {
    const nt4Path = path.join(
        root,
        "src",
        "hub",
        "dataSources",
        "nt4",
        "NT4.ts"
    );
    const originalSource = fs.readFileSync(nt4Path, "utf8");
    if (originalSource === newSource) return;
    fs.writeFileSync(nt4Path, newSource);
}

function patchAssetLoader(source) {
    if (source.includes("// BEGIN STATIC ASSET HOSTING PATCH")) return source;

    const probe = `  // BEGIN STATIC ASSET HOSTING PATCH
  // lite_server.py maps /assets/* to bundledAssets/*, while a plain static server
  // cannot have both an /assets file and an /assets directory. Probe one asset to
  // support both hosting modes without changing the asset index response.
  let assetBasePath = "assets";
  let firstAssetPath = Object.keys(assetIndex).find((path) => !path.endsWith("config.json"));
  if (firstAssetPath !== undefined) {
    try {
      let assetResponse = await fetch(\`assets/\${firstAssetPath}\`);
      if (!assetResponse.ok) assetBasePath = "bundledAssets";
    } catch {
      assetBasePath = "bundledAssets";
    }
  }
  // END STATIC ASSET HOSTING PATCH`;

    let patched = source.replace(
        `  let currentPath = window.location.pathname;`,
        `  let currentPath = window.location.pathname;\n\n${probe}`
    );
    patched = patched.replaceAll(
        "fetch(`assets/${configPath}`)",
        "fetch(`${assetBasePath}/${configPath}`)"
    );
    patched = patched.replaceAll(
        "currentPath + `assets/${encodeURIComponent(name)}/",
        "currentPath + `${assetBasePath}/${encodeURIComponent(name)}/"
    );
    return patched;
}

// The bundled documentation site is gone, so the "Documentation" tab must load the hosted manual.
// That frame is cross-origin, which makes hash and scroll access throw, so those accesses are
// guarded while same-origin frames keep the original behavior.
function patchDocumentationRenderer(source) {
    if (source.includes("// BEGIN WASMBOT HOSTED DOCUMENTATION PATCH")) return source;

    function replaceOnce(text, before, after) {
        const index = text.indexOf(before);
        if (index === -1) {
            throw new Error(
                `Could not find DocumentationRenderer anchor: ${before}`
            );
        }
        return text.slice(0, index) + after + text.slice(index + before.length);
    }

    let patched = replaceOnce(
        source,
        `      this.IFRAME.src = "../docs/build/index.html";`,
        `      // BEGIN WASMBOT HOSTED DOCUMENTATION PATCH
      this.IFRAME.src = "${hostedDocumentationUrl}";
      // END WASMBOT HOSTED DOCUMENTATION PATCH`
    );
    patched = replaceOnce(
        patched,
        `  saveState(): unknown {
    return this.IFRAME.contentWindow?.location.hash;
  }`,
        `  /** The hosted documentation frame is cross-origin; same-origin frames behave as before. */
  private frameIsSameOrigin(): boolean {
    try {
      return this.IFRAME.contentWindow?.location.origin === window.location.origin;
    } catch {
      return false;
    }
  }

  saveState(): unknown {
    return this.frameIsSameOrigin()
      ? this.IFRAME.contentWindow?.location.hash
      : null;
  }`
    );
    patched = replaceOnce(
        patched,
        `      if (this.loaded) {
        this.IFRAME.contentWindow!.location.hash = state;`,
        `      if (this.loaded && this.frameIsSameOrigin()) {
        this.IFRAME.contentWindow!.location.hash = state;`
    );
    patched = replaceOnce(
        patched,
        `    if (this.stateQueue !== null && this.loaded) {
      this.IFRAME.contentWindow!.location.hash = this.stateQueue;
      this.stateQueue = null;
    }`,
        `    if (this.stateQueue !== null && this.loaded) {
      if (this.frameIsSameOrigin()) {
        this.IFRAME.contentWindow!.location.hash = this.stateQueue;
      }
      this.stateQueue = null;
    }`
    );
    patched = replaceOnce(
        patched,
        `    if (this.loaded && this.IFRAME.contentWindow !== null) {`,
        `    if (this.loaded && this.frameIsSameOrigin()) {`
    );
    return patched;
}

function patchWasmCompile(source) {
    const versionCheck = `          if (!stdout.includes(EMSCRIPTEN_VERSION)) {
            reject(
              \`Emscripten version is not \${EMSCRIPTEN_VERSION}. Please install the correct version and try again.\`
            );
          }`;

    return source.replace(
        versionCheck,
        `          if (!stdout.includes(EMSCRIPTEN_VERSION)) {
            console.warn(
              \`Using an unrecognized Emscripten version. Expected \${EMSCRIPTEN_VERSION}.\`
            );
          }`
    );
}

/** Returns the asset index served by lite_server.py, without its filesystem paths. */
function createAssetIndex(assetRoot) {
    const assetIndex = {};

    function visit(directory, relativeDirectory = "") {
        for (const entry of fs.readdirSync(directory, {
            withFileTypes: true
        })) {
            if (entry.name.startsWith(".")) continue;

            const relativePath = path.posix.join(relativeDirectory, entry.name);
            const fullPath = path.join(directory, entry.name);
            if (entry.isDirectory()) {
                visit(fullPath, relativePath);
            } else if (entry.isFile()) {
                let contents = null;
                if (entry.name === "config.json") {
                    try {
                        contents = JSON.parse(
                            fs.readFileSync(fullPath, "utf8")
                        );
                    } catch {
                        // Match lite_server.py: malformed config files remain in the index as null.
                    }
                }
                assetIndex[relativePath] = contents;
            }
        }
    }

    visit(assetRoot);
    return assetIndex;
}

// The static asset index uses a file named "assets", so patch the loader while
// compiling to support both lite_server.py and plain static file servers. Restore
// the source even if one of the build steps fails.
const assetLoaderPath = path.join(
    root,
    "src",
    "main",
    "lite",
    "assetLoader.ts"
);
const originalAssetLoader = fs.readFileSync(assetLoaderPath, "utf8");
const patchedAssetLoader = patchAssetLoader(originalAssetLoader);
if (patchedAssetLoader !== originalAssetLoader)
    fs.writeFileSync(assetLoaderPath, patchedAssetLoader);
log(
    patchedAssetLoader !== originalAssetLoader
        ? "patched AdvantageScope asset loader"
        : "asset loader already supports static hosting"
);

const wasmCompilePath = path.join(root, "wasmCompile.mjs");
const originalWasmCompile = fs.readFileSync(wasmCompilePath, "utf8");
const patchedWasmCompile = patchWasmCompile(originalWasmCompile);
if (patchedWasmCompile !== originalWasmCompile)
    fs.writeFileSync(wasmCompilePath, patchedWasmCompile);
log(
    patchedWasmCompile !== originalWasmCompile
        ? "patched Emscripten version handling"
        : "Emscripten version handling already patched"
);

const documentationRendererPath = path.join(
    root,
    "src",
    "shared",
    "renderers",
    "DocumentationRenderer.ts"
);
const originalDocumentationRenderer = fs.readFileSync(
    documentationRendererPath,
    "utf8"
);
const patchedDocumentationRenderer = patchDocumentationRenderer(
    originalDocumentationRenderer
);
if (patchedDocumentationRenderer !== originalDocumentationRenderer)
    fs.writeFileSync(documentationRendererPath, patchedDocumentationRenderer);
log(
    patchedDocumentationRenderer !== originalDocumentationRenderer
        ? "patched AdvantageScope documentation renderer"
        : "documentation renderer already loads the hosted manual"
);

patchNT4(fs.readFileSync(path.join(scriptDirectory, "NT4.ts"), "utf8"));
log("installed Wasmbot NT4 adapter");

try {
    // Generate the Lite bundles and WebAssembly module.
    log("building TypeScript bundles");
    run("npm", ["run", "compile"], root);
    run("npm", ["run", "wasm:compile"], root);
} finally {
    log("restoring temporary AdvantageScope source patches");
    if (patchedAssetLoader !== originalAssetLoader)
        fs.writeFileSync(assetLoaderPath, originalAssetLoader);
    if (patchedWasmCompile !== originalWasmCompile)
        fs.writeFileSync(wasmCompilePath, originalWasmCompile);
    if (patchedDocumentationRenderer !== originalDocumentationRenderer)
        fs.writeFileSync(
            documentationRendererPath,
            originalDocumentationRenderer
        );
}

// Assemble Wasmbot's copy of the static Lite distribution. AdvantageScope's own `lite/static` is
// left complete so its asset downloader keeps its on-disk cache between builds.
const staticRoot = path.join(root, "lite", "static");
const bundleRoot = path.join(root, "lite", "wasmbot");
log(`assembling static output at ${bundleRoot}`);
fs.rmSync(bundleRoot, { recursive: true, force: true });
fs.mkdirSync(bundleRoot, { recursive: true });
for (const entry of fs.readdirSync(staticRoot)) {
    // The embedded AdvantageScope documentation site is an upstream user manual rather than part
    // of the plugin; the "Documentation" tab loads the hosted manual instead.
    if (entry === "docs" || entry === "www") continue;
    fs.cpSync(path.join(staticRoot, entry), path.join(bundleRoot, entry), {
        recursive: true
    });
}
copyDirectory(path.join(root, "www"), path.join(bundleRoot, "www"));
pruneBundledAssets(path.join(bundleRoot, "bundledAssets"));
patchHubHtml(path.join(bundleRoot, "www", "hub.html"));
patchSatelliteHtml(path.join(bundleRoot, "www", "satellite.html"));

// The server normally creates this response dynamically. An extensionless file lets
// the same response be served by a plain static file server as well.
const assetRoot = path.join(bundleRoot, "bundledAssets");
const assetIndex = createAssetIndex(assetRoot);
fs.writeFileSync(path.join(bundleRoot, "assets"), JSON.stringify(assetIndex));

log(
    `generated trimmed AdvantageScope ${distribution} in ${path.relative(root, bundleRoot)}`
);
