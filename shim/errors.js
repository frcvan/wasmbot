// Throwable methods and Java array indexing are not generally exported by Web Image proxies.
// Let Java's printStackTrace render its own types, frames, causes, and suppressed exceptions.
export function errorText(error) {
    const hooks = globalThis.wasmbot;
    if (
        typeof hooks?.formatJavaError === "function" &&
        typeof hooks.javaNative === "symbol" &&
        error != null &&
        error[hooks.javaNative] !== undefined
    ) {
        return hooks.formatJavaError(error);
    }
    return String(error?.stack ?? error?.message ?? error);
}
