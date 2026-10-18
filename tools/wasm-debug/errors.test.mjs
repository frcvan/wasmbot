import assert from "node:assert/strict";
import { afterEach, test } from "node:test";
import { errorText } from "../../shim/errors.js";

const originalHooks = globalThis.wasmbot;
afterEach(() => {
    if (originalHooks === undefined) delete globalThis.wasmbot;
    else globalThis.wasmbot = originalHooks;
});

test("native JavaScript errors retain their original stacks", () => {
    globalThis.wasmbot = {
        javaNative: Symbol("javaNative"),
        formatJavaError() {
            assert.fail("native JavaScript errors must not enter the Java formatter");
        }
    };
    const error = new TypeError("native-js-error");
    assert.equal(errorText(error), error.stack);
    assert.match(errorText(error), /^TypeError: native-js-error\n/);
});

test("errors before Java startup and non-Error thrown values remain readable", () => {
    delete globalThis.wasmbot;
    const error = new Error("loader-error");
    assert.equal(errorText(error), error.stack);
    assert.equal(errorText("thrown string"), "thrown string");
    assert.equal(errorText({ message: "plain error" }), "plain error");
    assert.equal(errorText(null), "null");
    assert.equal(errorText(undefined), "undefined");
});
