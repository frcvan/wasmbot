import { afterEach, beforeEach, expect, test, vi } from "bun:test";
import { NT4_Client, NT4_Topic } from "../../web/sim/NT4";
import { createNetworkTables } from "../../web/sim/networktables.js";

const clients: NT4_Client[] = [];
beforeEach(() => vi.useFakeTimers());
afterEach(() => {
  for (const client of clients.splice(0)) client.disconnect();
  vi.useRealTimers();
});

function setup() {
  let nanos = 5_000_000;
  globalThis.wasmbot = { nowNanos: () => nanos };
  const host = createNetworkTables(() => nanos);
  host.install();
  const announced: string[] = [];
  const removed: string[] = [];
  const properties: Record<string, unknown>[] = [];
  const data: { name: string; timestamp: number; value: unknown }[] = [];
  const lifecycle: string[] = [];
  const client = new NT4_Client("ignored", [5810], "test",
    topic => announced.push(topic.name),
    topic => removed.push(topic.name),
    topic => properties.push({ ...topic.properties }),
    (topic, timestamp, value) => data.push({ name: topic.name, timestamp, value }),
    () => lifecycle.push("connected"),
    () => lifecycle.push("disconnected"));
  clients.push(client);
  return { host, client, announced, removed, properties, data, lifecycle, advance: () => { nanos += 1_000_000; } };
}

function delivered() {
  vi.advanceTimersByTime(10);
}

test("exact/prefix subscriptions discover cached values; topics-only never emits samples", async () => {
  const { host, client, announced, data } = setup();
  const owner = {};
  host.publish(owner, "/A/value", "double");
  host.put(owner, "/A/value", 123, 7);
  host.publish(owner, "/AB/value", "double");
  host.put(owner, "/AB/value", 124, 8);
  const metadata = client.subscribeTopicsOnly(["/A/"], true);
  client.connect();
  await Promise.resolve();
  expect(announced).toEqual(["/A/value"]);
  await delivered();
  expect(data).toEqual([]);
  client.subscribe(["/A/value"], false, false, 0);
  await delivered();
  expect(data).toEqual([{ name: "/A/value", timestamp: 123, value: 7 }]);
  client.unsubscribe(metadata);
  client.subscribeTopicsOnly(["/AB/value"], false);
  await delivered();
  expect(announced).toEqual(["/A/value", "/AB/value"]);
  expect(data).toEqual([{ name: "/A/value", timestamp: 123, value: 7 }]);
});

test("sendAll preserves samples; latest coalesces; overlapping subscriptions do not duplicate", async () => {
  const { host, client, data } = setup();
  client.subscribe(["/"], true, false, 0);
  client.connect();
  await Promise.resolve();
  const owner = {};
  host.publish(owner, "/value", "double");
  host.put(owner, "/value", 1, 1);
  host.put(owner, "/value", 2, 2);
  expect(data).toEqual([]);
  await delivered();
  expect(data.map(sample => sample.value)).toEqual([2]);
  client.subscribe(["/value"], false, true, 0);
  host.put(owner, "/value", 3, 3);
  host.put(owner, "/value", 4, 4);
  await delivered();
  expect(data.map(sample => sample.value)).toEqual([2, 3, 4]);
  host.put(owner, "/value", 5, 5);
  client.clearAllSubscriptions();
  await delivered();
  expect(data.map(sample => sample.value)).toEqual([2, 3, 4]);
});

test("client publishing, property deletion, retention and multiple owners share host state", async () => {
  const { host, client, properties, removed } = setup();
  client.subscribe([""], true, true, 0);
  client.publishTopic("/value", "double");
  client.setProperties("/value", { retained: true, units: "m" });
  client.connect();
  await Promise.resolve();
  client.addTimestampedSample("/value", 42, 1.5);
  expect(globalThis.wasmbot?.ntGet?.("/value")).toBe(1.5);
  client.setProperties("/value", { units: null });
  expect(properties).toEqual([{ retained: true }]);
  const second = {};
  host.publish(second, "/value", "double");
  client.unpublishTopic("/value");
  host.unpublish(second, "/value");
  expect(host.snapshot().map(topic => topic.name)).toEqual(["/value"]);
  client.setRetained("/value", false);
  expect(removed).toEqual(["/value"]);
  expect(globalThis.wasmbot?.ntGet?.("/value")).toBeUndefined();
});

test("disconnect cancels delivery, reconnect restores subscriptions and publications", async () => {
  const { host, client, data, lifecycle, advance } = setup();
  expect(client.getServerTime_us()).toBeNull();
  client.publishTopic("/own", "double");
  client.subscribe([""], true, true, 0);
  client.connect();
  client.connect();
  await Promise.resolve();
  expect(client.getServerTime_us()).toBe(5000);
  const clientTime = client.getClientTime_us();
  expect(client.getServerTime_us(clientTime)).toBe(5000);
  advance();
  expect(client.getServerTime_us()).toBe(6000);
  client.addSample("/own", 8);
  client.disconnect();
  await delivered();
  expect(data).toEqual([]);
  expect(host.snapshot()).toEqual([]);
  expect(client.getServerTime_us()).toBeNull();
  client.connect();
  await Promise.resolve();
  client.addSample("/own", 9);
  await delivered();
  expect(data).toEqual([{ name: "/own", timestamp: 6000, value: 9 }]);
  expect(lifecycle).toEqual(["connected", "disconnected", "connected"]);
});

test("conflicting publishers and stale timestamps cannot overwrite the current value", () => {
  const { host } = setup();
  const first = {}, second = {};
  host.publish(first, "/value", "double");
  host.publish(second, "/value", "string");
  expect(host.put(first, "/value", 20, 2)).toBe(true);
  expect(host.put(second, "/value", 30, "wrong")).toBe(false);
  expect(host.put(first, "/value", 10, 1)).toBe(false);
  host.unpublish(first, "/value");
  expect(globalThis.wasmbot?.ntGet?.("/value")).toBe(2);
  host.unpublish(second, "/value");
  expect(globalThis.wasmbot?.ntGet?.("/value")).toBeUndefined();
});

test("a faster overlapping subscription accelerates already queued samples", async () => {
  const { host, client, data } = setup();
  client.subscribe(["/value"], false, false, 1);
  client.connect();
  await Promise.resolve();
  const owner = {};
  host.publish(owner, "/value", "double");
  host.put(owner, "/value", 10, 7);
  vi.advanceTimersByTime(100);
  expect(data).toEqual([]);
  client.subscribe(["/"], true, false, 0);
  await delivered();
  expect(data).toEqual([{ name: "/value", timestamp: 10, value: 7 }]);
});

test("unknown IDs fail, and custom type strings map to NT binary", () => {
  const { client } = setup();
  expect(() => client.unsubscribe(999)).toThrow();
  expect(() => client.unpublishTopic("/missing")).toThrow();
  expect(() => client.addSample("/missing", 0)).toThrow();
  const topic = new NT4_Topic();
  topic.type = "struct:Pose2d";
  expect(topic.getTypeIdx()).toBe(5);
  topic.type = "toString";
  expect(topic.getTypeIdx()).toBe(5);
});
