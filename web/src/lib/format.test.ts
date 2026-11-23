import { describe, expect, it } from "vitest";

import type { SlaTimer } from "../api/types";
import { formatDuration, humanize, remainingSecondsNow, slaLabel } from "./format";

const timer = (overrides: Partial<SlaTimer>): SlaTimer => ({
  metric: "FIRST_RESPONSE",
  health: "ON_TRACK",
  clockState: "RUNNING",
  targetMinutes: 30,
  dueAt: "2026-11-23T10:30:00Z",
  warnedAt: null,
  breachedAt: null,
  metAt: null,
  elapsedSeconds: 600,
  remainingSeconds: 1200,
  ticking: true,
  ...overrides,
});

describe("formatDuration", () => {
  it("reads like a person would say it", () => {
    expect(formatDuration(45)).toBe("45s");
    expect(formatDuration(20 * 60)).toBe("20m");
    expect(formatDuration(2 * 3600 + 5 * 60)).toBe("2h 05m");
    expect(formatDuration(3 * 86_400 + 4 * 3600)).toBe("3d 4h");
  });
});

describe("remainingSecondsNow", () => {
  it("counts down while the clock ticks", () => {
    expect(remainingSecondsNow(timer({}), 1_000_000, 1_000_000 + 90_000)).toBe(1110);
  });

  it("stands still while paused or outside business hours", () => {
    expect(remainingSecondsNow(timer({ ticking: false }), 1_000_000, 1_000_000 + 90_000)).toBe(1200);
  });
});

describe("slaLabel", () => {
  it("describes each state of an SLA clock", () => {
    expect(slaLabel(timer({}), 1200)).toBe("20m left");
    expect(slaLabel(timer({ ticking: false }), 1200)).toBe("20m left (outside hours)");
    expect(slaLabel(timer({ health: "PAUSED", clockState: "PAUSED" }), 1200)).toBe("paused, 20m left");
    expect(slaLabel(timer({ health: "MET", metAt: "2026-11-23T10:10:00Z" }), 1200)).toBe("met");
    expect(slaLabel(timer({ health: "BREACHED" }), -300)).toBe("overdue 5m");
    expect(slaLabel(timer({ health: "BREACHED", metAt: "2026-11-23T11:00:00Z" }), -1800)).toBe("met late");
    expect(slaLabel(timer({}), 0)).toBe("overdue");
  });
});

describe("humanize", () => {
  it("turns audit codes into words", () => {
    expect(humanize("SLA_BREACHED")).toBe("Sla breached");
    expect(humanize("TICKET_CREATED")).toBe("Ticket created");
  });
});
