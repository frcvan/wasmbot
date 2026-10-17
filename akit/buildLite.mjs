// Copyright (c) 2021-2026 Littleton Robotics
// http://github.com/Mechanical-Advantage
//
// Use of this source code is governed by a BSD
// license that can be found in the LICENSE file
// at the root directory of this project.

import { execFileSync } from "child_process";
import fs from "fs";
import path from "path";
import { fileURLToPath } from "url";

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const root = path.join(scriptDirectory, "build");
const distribution = process.env.ASCOPE_DISTRIBUTION ?? "LITE";

if (distribution !== "LITE" && distribution !== "LITEDS") {
    throw new Error(`Unsupported Lite distribution: ${distribution}`);
}

function run(command, args, cwd = scriptDirectory) {
    console.log(`> ${command} ${args.join(" ")}`);
    execFileSync(command, args, {
        cwd,
        env: { ...process.env, ASCOPE_DISTRIBUTION: distribution },
        stdio: "inherit"
    });
}

function copyDirectory(source, destination) {
    fs.rmSync(destination, { recursive: true, force: true });
    fs.cpSync(source, destination, { recursive: true });
}

function cloneAscope(destination) {
    fs.rmSync(destination, { recursive: true, force: true });
    fs.mkdirSync(destination, { recursive: true });

    run("git", [
        "clone",
        "--depth=1",
        "https://github.com/Mechanical-Advantage/AdvantageScope.git",
        destination
    ]);
}

cloneAscope(root);

run(
    process.platform === "win32" ? "npm.cmd" : "npm",
    ["ci"],
    root
);

function patchHubHtml(filePath) {
    const script = `    <script type="module" src="../wasm/sim.js"></script>\n`;
    const target = `    <script type="module" src="../bundles/hub.js"></script>`;
    const contents = fs.readFileSync(filePath, "utf8");
    if (contents.includes(script.trim())) return;
    if (!contents.includes(target)) {
        throw new Error(`Could not find the hub bundle script in ${filePath}`);
    }
    fs.writeFileSync(filePath, contents.replace(target, script + target));
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

function patchWasmCompile(source) {
    const versionCheck = `          if (!stdout.includes(EMSCRIPTEN_VERSION)) {
            reject(
              \`Emscripten version is not \${EMSCRIPTEN_VERSION}. Please install the correct version and try again.\`
            );
          }`;
    if (!source.includes(versionCheck)) {
        throw new Error("Could not find the Emscripten version check");
    }

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

const wasmCompilePath = path.join(root, "wasmCompile.mjs");
const originalWasmCompile = fs.readFileSync(wasmCompilePath, "utf8");
const patchedWasmCompile = patchWasmCompile(originalWasmCompile);
if (patchedWasmCompile !== originalWasmCompile)
    fs.writeFileSync(wasmCompilePath, patchedWasmCompile);

patchNT4(fs.readFileSync(path.join(scriptDirectory, "NT4.ts"), "utf8"));

try {
    // Generate the Lite bundles and WebAssembly module.
    run(
        process.platform === "win32" ? "npm.cmd" : "npm",
        ["run", "compile"],
        root
    );
    run(process.platform === "win32" ? "npm.cmd" : "npm", [
        "run",
        "wasm:compile"
    ], root);
    run(process.platform === "win32" ? "npm.cmd" : "npm", [
        "run",
        "docs:build-embed"
    ], root);
} finally {
    if (patchedAssetLoader !== originalAssetLoader)
        fs.writeFileSync(assetLoaderPath, originalAssetLoader);
    if (patchedWasmCompile !== originalWasmCompile)
        fs.writeFileSync(wasmCompilePath, originalWasmCompile);
}

// Copy files that are part of the static Lite distribution.
const staticRoot = path.join(root, "lite", "static");
copyDirectory(path.join(root, "www"), path.join(staticRoot, "www"));
copyDirectory(path.join(root, "docs", "build"), path.join(staticRoot, "docs"));
patchHubHtml(path.join(staticRoot, "www", "hub.html"));

// The server normally creates this response dynamically. An extensionless file lets
// the same response be served by a plain static file server as well.
const assetRoot = path.join(staticRoot, "bundledAssets");
const assetIndex = createAssetIndex(assetRoot);
fs.writeFileSync(path.join(staticRoot, "assets"), JSON.stringify(assetIndex));

console.log(
    `Generated AdvantageScope ${distribution} in ${path.relative(root, staticRoot)}`
);
