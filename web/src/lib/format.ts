import type { Priority, SlaHealth, SlaMetric, SlaTimer, TicketStatus } from "../api/types";

/** "2h 05m", "45m", "30s": a duration in seconds, the way people read deadlines. */
export function formatDuration(totalSeconds: number): string {
  const seconds = Math.max(0, Math.round(Math.abs(totalSeconds)));
  const days = Math.floor(seconds / 86_400);
  const hours = Math.floor((seconds % 86_400) / 3_600);
  const minutes = Math.floor((seconds % 3_600) / 60);
  if (days > 0) return `${days}d ${hours}h`;
  if (hours > 0) return `${hours}h ${String(minutes).padStart(2, "0")}m`;
  if (minutes > 0) return `${minutes}m`;
  return `${seconds % 60}s`;
}

/**
 * Business time left on an SLA clock, now. The server's {@code remainingSeconds} was computed
 * when the data was fetched; while the clock ticks we count down from there, so the badge moves
 * every second without asking the server.
 */
export function remainingSecondsNow(timer: SlaTimer, fetchedAt: number, now: number): number {
  if (!timer.ticking) return timer.remainingSeconds;
  return timer.remainingSeconds - Math.floor((now - fetchedAt) / 1000);
}

/** The text of an SLA badge. */
export function slaLabel(timer: SlaTimer, remaining: number): string {
  switch (timer.health) {
    case "MET":
      return "met";
    case "STOPPED":
      return "stopped";
    case "PAUSED":
      return `paused, ${formatDuration(remaining)} left`;
    case "BREACHED":
      return timer.metAt ? "met late" : `overdue ${formatDuration(-Math.min(remaining, 0))}`;
    case "ON_TRACK":
    case "AT_RISK":
      if (remaining <= 0) return "overdue";
      return timer.ticking ? `${formatDuration(remaining)} left` : `${formatDuration(remaining)} left (outside hours)`;
  }
}

export const healthStyle: Record<SlaHealth, string> = {
  ON_TRACK: "bg-emerald-50 text-emerald-800 ring-emerald-200",
  AT_RISK: "bg-amber-50 text-amber-800 ring-amber-300",
  BREACHED: "bg-red-50 text-red-800 ring-red-300",
  PAUSED: "bg-slate-100 text-slate-700 ring-slate-300",
  MET: "bg-sky-50 text-sky-800 ring-sky-200",
  STOPPED: "bg-slate-50 text-slate-500 ring-slate-200",
};

export const metricLabel: Record<SlaMetric, string> = {
  FIRST_RESPONSE: "First response",
  RESOLUTION: "Resolution",
};

export const statusLabel: Record<TicketStatus, string> = {
  NEW: "New",
  OPEN: "Open",
  WAITING_ON_CUSTOMER: "Waiting on customer",
  RESOLVED: "Resolved",
  CLOSED: "Closed",
};

export const statusStyle: Record<TicketStatus, string> = {
  NEW: "bg-indigo-50 text-indigo-800 ring-indigo-200",
  OPEN: "bg-blue-50 text-blue-800 ring-blue-200",
  WAITING_ON_CUSTOMER: "bg-slate-100 text-slate-700 ring-slate-300",
  RESOLVED: "bg-emerald-50 text-emerald-800 ring-emerald-200",
  CLOSED: "bg-slate-50 text-slate-500 ring-slate-200",
};

export const priorityStyle: Record<Priority, string> = {
  LOW: "text-slate-500",
  NORMAL: "text-slate-700",
  HIGH: "text-orange-700 font-medium",
  URGENT: "text-red-700 font-semibold",
};

export const actionLabel: Record<string, string> = {
  ASSIGN: "Assign",
  WAIT_ON_CUSTOMER: "Wait on customer",
  RESUME: "Resume",
  RESOLVE: "Resolve",
  REOPEN: "Reopen",
  CLOSE: "Close",
};

export function formatDateTime(iso: string): string {
  return new Date(iso).toLocaleString(undefined, { dateStyle: "medium", timeStyle: "short" });
}

/** "TICKET_CREATED" -> "Ticket created". */
export function humanize(code: string): string {
  const words = code.toLowerCase().replaceAll("_", " ");
  return words.charAt(0).toUpperCase() + words.slice(1);
}
