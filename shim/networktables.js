// In-process NetworkTables transport shared by the robot, viewer, and NT4 adapter.
export function createNetworkTables(nowNanos) {
  const topics = new Map();
  const listeners = new Set();
  let nextId = 1;
  const emit = (event) => {
    for (const listener of [...listeners]) listener(event);
  };
  function removeUnused(topic) {
    if (topic.publishers.size || topic.properties.retained || topic.properties.persistent) return;
    topics.delete(topic.name);
    emit({ kind: "unannounce", topic });
  }
  function publish(owner, name, type, properties = {}) {
    let topic = topics.get(name);
    if (!topic) {
      topic = { uid: nextId++, name, type, properties: { ...properties }, publishers: new Map(), timestamp: 0, value: undefined, revision: 0, lastPublisher: null };
      topics.set(name, topic);
      topic.publishers.set(owner, type);
      emit({ kind: "announce", topic });
    } else {
      topic.publishers.set(owner, type);
    }
  }
  function unpublish(owner, name) {
    const topic = topics.get(name);
    if (!topic) return;
    topic.publishers.delete(owner);
    removeUnused(topic);
  }
  function put(owner, name, timestamp, value) {
    const topic = topics.get(name);
    if (!topic || topic.publishers.get(owner) !== topic.type) return false;
    if (timestamp < topic.timestamp) return false;
    topic.timestamp = timestamp;
    topic.value = Array.isArray(value) || value instanceof Uint8Array ? value.slice() : value;
    topic.revision++;
    topic.lastPublisher = owner;
    emit({ kind: "value", topic, timestamp, value: topic.value });
    return true;
  }
  const transport = {
    snapshot: () => [...topics.values()],
    getTopic: (name) => topics.get(name),
    listen(listener) {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    publish,
    unpublish,
    put,
    setProperties(name, update) {
      const topic = topics.get(name);
      if (!topic) return;
      for (const [key, value] of Object.entries(update)) {
        if (value === null) delete topic.properties[key];
        else topic.properties[key] = value;
      }
      emit({ kind: "properties", topic });
      removeUnused(topic);
    },
  };
  const implicitOwners = new Map();
  function inferType(value) {
    if (typeof value === "boolean") return "boolean";
    if (typeof value === "number") return "double";
    if (typeof value === "string") return "string";
    if (value instanceof Uint8Array) return "raw";
    if (Array.isArray(value)) return `${inferType(value[0] ?? 0)}[]`;
    return "json";
  }
  return {
    ...transport,
    install(target = globalThis.wasmbot) {
      if (!target) throw new Error("Initialize globalThis.wasmbot before installing NetworkTables.");
      target.nt = transport;
      target.ntGet = (name) => topics.get(name)?.value;
      target.ntPut = (name, value, type = inferType(value), timestamp = nowNanos() / 1000) => {
        let owner = implicitOwners.get(name);
        if (!owner) {
          owner = {};
          implicitOwners.set(name, owner);
          publish(owner, name, type);
        }
        put(owner, name, timestamp, value);
      };
    },
  };
}
