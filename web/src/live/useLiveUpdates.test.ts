import { describe, expect, it } from "vitest";

import { webSocketUrl } from "./useLiveUpdates";

describe("webSocketUrl", () => {
  it("uses this origin, with the matching WebSocket scheme", () => {
    expect(webSocketUrl({ protocol: "http:", host: "localhost:5580" })).toBe("ws://localhost:5580/ws");
    expect(webSocketUrl({ protocol: "https:", host: "helpdesk.example.com" })).toBe("wss://helpdesk.example.com/ws");
  });
});
