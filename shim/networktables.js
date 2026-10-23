// In-process NetworkTables transport shared by the robot, viewer, and NT4 adapter.
export function createNetworkTables() {
  const topics = new Map();
  const listeners = new Set();
  let listenerSnapshot = [];
  let nextId = 1;
  // Schema samples are rare; one encoder and one property bag avoid a per-publication allocation.
  const schemaEncoder = new TextEncoder();
  const schemaProperties = { retained: true };
  const schemaOwner = {};
  const emit = (event) => {
    for (const listener of listenerSnapshot) listener(event);
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
  // `owned` marks a value the caller just built for this publication and cannot observe again,
  // so array publications from Java skip a second allocation. Shared buffers keep their copy.
  function put(owner, name, timestamp, value, owned) {
    const topic = topics.get(name);
    if (!topic || topic.publishers.get(owner) !== topic.type) return false;
    if (timestamp < topic.timestamp) return false;
    topic.timestamp = timestamp;
    topic.value =
      owned === true || !(Array.isArray(value) || value instanceof Uint8Array)
        ? value
        : value.slice();
    topic.revision++;
    topic.lastPublisher = owner;
    emit({ kind: "value", topic, timestamp, value: topic.value });
    return true;
  }
  /** Announces a struct schema topic and publishes its encoded schema bytes. */
  function putSchema(name, value) {
    publish(schemaOwner, name, "structschema", schemaProperties);
    return put(schemaOwner, name, 0, schemaEncoder.encode(value), true);
  }
  const transport = {
    snapshot: () => [...topics.values()],
    getTopic: (name) => topics.get(name),
    listen(listener) {
      if (!listeners.has(listener)) {
        listeners.add(listener);
        listenerSnapshot = [...listeners];
      }
      return () => {
        const removed = listeners.delete(listener);
        if (removed) listenerSnapshot = [...listeners];
        return removed;
      };
    },
    publish,
    unpublish,
    put,
    putSchema,
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
  return {
    ...transport,
    install(target = globalThis.wasmbot) {
      if (!target) throw new Error("Initialize globalThis.wasmbot before installing NetworkTables.");
      target.nt = transport;
    },
  };
}
