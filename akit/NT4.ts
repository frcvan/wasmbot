// AdvantageScope-compatible NT4 API over wasmbot's in-process host hooks.
// This file is self-contained: copy it over src/hub/dataSources/nt4/NT4.ts.
export const NT4_PORTS_DEFAULT = [5810];
export const NT4_PORTS_DS = [6767];
export const NT4_PORTS_SYSTEMCORE = [6810];

const typeIndices: Record<string, number> = {
    boolean: 0,
    double: 1,
    int: 2,
    float: 3,
    string: 4,
    json: 4,
    raw: 5,
    rpc: 5,
    msgpack: 5,
    protobuf: 5,
    "boolean[]": 16,
    "double[]": 17,
    "int[]": 18,
    "float[]": 19,
    "string[]": 20
};

export class NT4_Topic {
    uid = -1;
    name = "";
    type = "";
    properties: Record<string, unknown> = {};

    toPublishObj() {
        return {
            name: this.name,
            type: this.type,
            pubuid: this.uid,
            properties: this.properties
        };
    }

    toUnpublishObj() {
        return { pubuid: this.uid };
    }

    getTypeIdx() {
        return Object.prototype.hasOwnProperty.call(typeIndices, this.type)
            ? typeIndices[this.type]
            : 5;
    }
}

type HostTopic = {
    uid: number;
    name: string;
    type: string;
    properties: Record<string, unknown>;
    timestamp: number;
    value: unknown;
};
type HostEvent = {
    kind: "announce" | "unannounce" | "properties" | "value";
    topic: HostTopic;
    timestamp?: number;
    value?: unknown;
};

function normalizeHostValue(type: string, value: unknown): unknown {
    // StructPublisher sends packed bytes as hex because Graal's JS bridge only accepts
    // JSString reliably for this call. NT4 exposes struct samples as raw byte arrays.
    // FIXME: make graalvm JS bridge accept Uint8Array and remove this hack.
    if (
        type.startsWith("struct:") &&
        typeof value === "string" &&
        value.length % 2 === 0 &&
        /^[0-9a-fA-F]*$/.test(value)
    ) {
        const bytes = new Uint8Array(value.length / 2);
        for (let i = 0; i < bytes.length; i++)
            bytes[i] = Number.parseInt(value.slice(i * 2, i * 2 + 2), 16);
        return bytes;
    }
    return value;
}
type Host = {
    snapshot(): HostTopic[];
    listen(listener: (event: HostEvent) => void): () => void;
    publish(
        owner: object,
        name: string,
        type: string,
        properties: Record<string, unknown>
    ): void;
    unpublish(owner: object, name: string): void;
    setProperties(name: string, update: Record<string, unknown>): void;
    put(owner: object, name: string, timestamp: number, value: unknown): void;
};

declare global {
    interface WasmbotHooks {
        nowNanos?: () => number;
        driverStation?: () => {
            enabled: boolean;
            alliance: string;
            leftX: number;
            leftY: number;
            rightX: number;
        };
        nt?: Host;
        ntGet?: (path: string) => unknown;
        ntPut?: (
            path: string,
            value: unknown,
            type?: string,
            timestampUs?: number
        ) => void;
        continuations?: unknown;
        start?: () => void;
        tick?: () => Promise<void>;
        probe?: Record<string, unknown>;

        onInstallHooks: (() => void)[];
    }
    var wasmbot: WasmbotHooks | undefined;
}
type Subscription = {
    patterns: string[];
    prefix: boolean;
    all: boolean;
    topicsOnly: boolean;
    periodic: number;
};
type Pending = {
    samples: { timestamp: number; value: unknown }[];
    due: number;
};

export class NT4_Client {
    private host: Host | null = null;
    private stopListening: (() => void) | null = null;
    private requested = false;
    private active = false;
    private nextUid = 1;
    private timeOffset: number | null = null;
    private subscriptions = new Map<number, Subscription>();
    private publishedTopics = new Map<string, NT4_Topic>();
    private serverTopics = new Map<string, NT4_Topic>();
    private pending = new Map<string, Pending>();
    private dataTopics = new Set<string>();
    private cancelTimer: (() => void) | null = null;

    constructor(
        serverAddr: string,
        serverPorts: number[],
        appName: string,
        private onTopicAnnounce: (topic: NT4_Topic) => void,
        private onTopicUnannounce: (topic: NT4_Topic) => void,
        private onTopicPropertiesUpdate: (topic: NT4_Topic) => void,
        private onNewTopicData: (
            topic: NT4_Topic,
            timestamp_us: number,
            value: unknown
        ) => void,
        private onConnect: () => void,
        private onDisconnect: () => void
    ) {
        // Address, ports, and app name are intentionally transport-independent here.
        void serverAddr;
        void serverPorts;
        void appName;
    }

    connect() {
        if (this.requested) return;
        globalThis.wasmbot = (globalThis.wasmbot ??= {
            onInstallHooks: []
        }) as WasmbotHooks;
        globalThis.wasmbot.onInstallHooks =
            globalThis.wasmbot.onInstallHooks ?? [];
        if (!globalThis.wasmbot.nt) {
            console.log("[wasmbot] wasmbot not started - adding hook");
            globalThis.wasmbot.onInstallHooks.push(() => this.onInstall());
        } else {
            console.log("[wasmbot] wasmbot already started - calling hook");
            this.onInstall();
        }
    }

    disconnect() {
        if (!this.requested) return;
        this.requested = false;
        const wasActive = this.active;
        this.active = false;
        this.stopListening?.();
        this.stopListening = null;
        this.cancelTimer?.();
        this.cancelTimer = null;
        this.pending.clear();
        this.serverTopics.clear();
        this.dataTopics.clear();
        if (wasActive) {
            for (const topic of this.publishedTopics.values())
                this.host!.unpublish(this, topic.name);
        }
        this.host = null;
        this.timeOffset = null;
        if (wasActive) this.onDisconnect();
    }

    subscribe(
        topicPatterns: string[],
        prefixMode: boolean,
        sendAll: boolean = false,
        periodic: number = 0.1
    ): number {
        return this.addSubscription(
            topicPatterns,
            prefixMode,
            sendAll,
            periodic,
            false
        );
    }

    subscribeTopicsOnly(topicPatterns: string[], prefixMode: boolean): number {
        return this.addSubscription(
            topicPatterns,
            prefixMode,
            false,
            0.1,
            true
        );
    }

    unsubscribe(subscriptionId: number) {
        if (!this.subscriptions.delete(subscriptionId))
            throw 'Unknown subscription ID "' + subscriptionId + '"';
        if (this.active) this.reconcile();
    }

    clearAllSubscriptions() {
        this.subscriptions.clear();
        if (this.active) this.reconcile();
    }

    setProperties(topic: string, properties: Record<string, unknown>) {
        const published = this.publishedTopics.get(topic);
        if (published) this.updateProperties(published, properties);
        if (this.active) this.host!.setProperties(topic, properties);
    }

    setPersistent(topic: string, isPersistent: boolean) {
        this.setProperties(topic, { persistent: isPersistent });
    }

    setRetained(topic: string, isRetained: boolean) {
        this.setProperties(topic, { retained: isRetained });
    }

    publishTopic(topic: string, type: string) {
        if (this.publishedTopics.has(topic)) return;
        const published = new NT4_Topic();
        published.uid = this.nextUid++;
        published.name = topic;
        published.type = type;
        this.publishedTopics.set(topic, published);
        if (this.active)
            this.host!.publish(this, topic, type, published.properties);
    }

    unpublishTopic(topic: string) {
        if (!this.publishedTopics.delete(topic))
            throw 'Topic "' + topic + '" not found';
        if (this.active) this.host!.unpublish(this, topic);
    }

    addSample(topic: string, value: unknown) {
        this.addTimestampedSample(topic, this.getServerTime_us() ?? 0, value);
    }

    addTimestampedSample(topic: string, timestamp: number, value: unknown) {
        if (!this.publishedTopics.has(topic))
            throw 'Topic "' + topic + '" not found';
        if (this.active) this.host!.put(this, topic, timestamp, value);
    }

    getClientTime_us(): number {
        return Date.now() * 1000;
    }

    getServerTime_us(clientTime?: number): number | null {
        if (!this.active) return null;
        if (clientTime !== undefined) return clientTime + this.timeOffset!;
        return globalThis.wasmbot!.nowNanos!() / 1000;
    }

    getNetworkLatency_us(): number {
        return 0;
    }

    private onInstall() {
        console.log("[wasmbot] connecting to wasmbot networktables");
        const hooks = globalThis.wasmbot;
        if (!hooks || !hooks?.nt) {
            throw new Error("wasmbot must be started before calling onInstall");
        }
        this.requested = true;
        this.host = hooks.nt;
        queueMicrotask(() => {
            if (!this.requested || this.active) return;
            this.active = true;
            this.timeOffset =
                hooks.nowNanos!() / 1000 - this.getClientTime_us();
            this.stopListening = this.host!.listen((event) =>
                this.receive(event)
            );
            for (const topic of this.publishedTopics.values()) {
                this.host!.publish(
                    this,
                    topic.name,
                    topic.type,
                    topic.properties
                );
            }
            this.onConnect();
            if (this.active) this.reconcile();
        });
    }

    private addSubscription(
        patterns: string[],
        prefix: boolean,
        all: boolean,
        periodic: number,
        topicsOnly: boolean
    ) {
        const id = this.nextUid++;
        this.subscriptions.set(id, {
            patterns: [...patterns],
            prefix,
            all,
            periodic,
            topicsOnly
        });
        if (this.active) this.reconcile();
        return id;
    }

    private matches(subscription: Subscription, name: string) {
        return subscription.patterns.some((pattern) =>
            subscription.prefix ? name.startsWith(pattern) : name === pattern
        );
    }

    private options(name: string) {
        let announced = false;
        let all = false;
        let periodic = Infinity;
        for (const subscription of this.subscriptions.values()) {
            if (!this.matches(subscription, name)) continue;
            announced = true;
            if (subscription.topicsOnly) continue;
            all ||= subscription.all;
            periodic = Math.min(
                periodic,
                Math.max(0, subscription.periodic) * 1000
            );
        }
        return { announced, all, periodic };
    }

    private reconcile() {
        if (!this.active) return;
        for (const [name, topic] of [...this.serverTopics]) {
            const options = this.options(name);
            if (!options.announced) {
                this.serverTopics.delete(name);
                this.pending.delete(name);
                this.dataTopics.delete(name);
                this.onTopicUnannounce(topic);
            } else if (options.periodic === Infinity) {
                this.pending.delete(name);
                this.dataTopics.delete(name);
            } else {
                const pending = this.pending.get(name);
                if (pending) {
                    pending.due = Math.min(
                        pending.due,
                        Date.now() + options.periodic
                    );
                    if (!options.all && pending.samples.length > 1)
                        pending.samples.splice(0, pending.samples.length - 1);
                }
            }
        }
        if (!this.active) return;
        for (const topic of this.host!.snapshot()) {
            if (!this.active) return;
            this.announce(topic);
            const options = this.options(topic.name);
            if (options.periodic === Infinity) continue;
            const hadData = this.dataTopics.has(topic.name);
            this.dataTopics.add(topic.name);
            if (!hadData && topic.value !== undefined)
                this.enqueue(
                    topic.name,
                    topic.timestamp,
                    normalizeHostValue(topic.type, topic.value)
                );
        }
        this.schedule();
    }

    private announce(source: HostTopic) {
        if (
            !this.options(source.name).announced ||
            this.serverTopics.has(source.name)
        )
            return;
        const topic = new NT4_Topic();
        topic.uid = source.uid;
        topic.name = source.name;
        topic.type = source.type;
        topic.properties = { ...source.properties };
        this.serverTopics.set(topic.name, topic);
        this.onTopicAnnounce(topic);
    }

    private receive(event: HostEvent) {
        const name = event.topic.name;
        if (event.kind === "unannounce") {
            const topic = this.serverTopics.get(name);
            this.serverTopics.delete(name);
            this.pending.delete(name);
            this.dataTopics.delete(name);
            if (topic) this.onTopicUnannounce(topic);
        } else if (event.kind === "announce") {
            this.announce(event.topic);
        } else if (event.kind === "properties") {
            const topic = this.serverTopics.get(name);
            if (topic) {
                topic.properties = { ...event.topic.properties };
                this.onTopicPropertiesUpdate(topic);
            }
        } else {
            this.announce(event.topic);
            this.enqueue(
                name,
                event.timestamp!,
                normalizeHostValue(event.topic.type, event.value)
            );
        }
    }

    private enqueue(name: string, timestamp: number, value: unknown) {
        const options = this.options(name);
        if (
            !this.active ||
            !this.serverTopics.has(name) ||
            options.periodic === Infinity
        )
            return;
        this.dataTopics.add(name);
        let pending = this.pending.get(name);
        if (!pending) {
            pending = { samples: [], due: Date.now() + options.periodic };
            this.pending.set(name, pending);
        }
        pending.due = Math.min(pending.due, Date.now() + options.periodic);
        if (!options.all) pending.samples.length = 0;
        pending.samples.push({ timestamp, value });
        this.schedule();
    }

    private schedule() {
        this.cancelTimer?.();
        this.cancelTimer = null;
        if (!this.active || this.pending.size === 0) return;
        let due = Infinity;
        for (const pending of this.pending.values())
            due = Math.min(due, pending.due);
        const timer = setTimeout(
            () => {
                this.cancelTimer = null;
                const now = Date.now();
                for (const [name, pending] of [...this.pending]) {
                    if (this.pending.get(name) !== pending || pending.due > now)
                        continue;
                    this.pending.delete(name);
                    const topic = this.serverTopics.get(name);
                    if (!topic || this.options(name).periodic === Infinity)
                        continue;
                    for (const sample of pending.samples) {
                        if (
                            !this.active ||
                            !this.serverTopics.has(name) ||
                            this.options(name).periodic === Infinity
                        )
                            break;
                        this.onNewTopicData(
                            topic,
                            sample.timestamp,
                            sample.value
                        );
                    }
                }
                this.schedule();
            },
            Math.max(0, due - Date.now())
        );
        this.cancelTimer = () => clearTimeout(timer);
    }

    private updateProperties(
        topic: NT4_Topic,
        properties: Record<string, unknown>
    ) {
        for (const [key, value] of Object.entries(properties)) {
            if (value === null) delete topic.properties[key];
            else topic.properties[key] = value;
        }
    }
}
