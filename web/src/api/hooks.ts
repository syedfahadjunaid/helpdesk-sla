import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { apiFetch } from "./client";
import type {
  Agent,
  AuditEntry,
  Calendar,
  Dashboard,
  Me,
  Message,
  NotificationList,
  Page,
  Priority,
  Queue,
  SlaPolicy,
  TicketAction,
  TicketDetail,
  TicketStatus,
  TicketSummary,
  Visibility,
} from "./types";

/**
 * Query keys. Live updates (live/useLiveUpdates.ts) invalidate these when the server says
 * something changed, and TanStack Query refetches whatever is on screen.
 */
export const keys = {
  me: ["me"] as const,
  agents: ["agents"] as const,
  queues: ["queues"] as const,
  tickets: ["tickets"] as const,
  ticket: (id: number) => ["tickets", id] as const,
  history: (id: number) => ["tickets", id, "history"] as const,
  notifications: ["notifications"] as const,
  policies: ["sla-policies"] as const,
  calendars: ["calendars"] as const,
  dashboard: ["dashboard"] as const,
};

export function useMe() {
  return useQuery({ queryKey: keys.me, queryFn: ({ signal }) => apiFetch<Me>("/api/me", { signal }), retry: false });
}

export function useAgents() {
  return useQuery({ queryKey: keys.agents, queryFn: ({ signal }) => apiFetch<Agent[]>("/api/agents", { signal }) });
}

export function useQueues() {
  return useQuery({ queryKey: keys.queues, queryFn: ({ signal }) => apiFetch<Queue[]>("/api/queues", { signal }) });
}

export type TicketFilter = {
  queueId?: number;
  statuses: TicketStatus[];
  assignee?: "me" | "unassigned";
  escalated?: boolean;
  q?: string;
  page: number;
};

export function ticketSearchParams(filter: TicketFilter): string {
  const params = new URLSearchParams();
  if (filter.queueId) params.set("queueId", String(filter.queueId));
  filter.statuses.forEach((status) => params.append("status", status));
  if (filter.assignee) params.set("assignee", filter.assignee);
  if (filter.escalated) params.set("escalated", "true");
  if (filter.q?.trim()) params.set("q", filter.q.trim());
  params.set("page", String(filter.page));
  params.set("size", "25");
  return params.toString();
}

export function useTickets(filter: TicketFilter) {
  const query = ticketSearchParams(filter);
  return useQuery({
    queryKey: [...keys.tickets, "list", query],
    queryFn: ({ signal }) => apiFetch<Page<TicketSummary>>(`/api/tickets?${query}`, { signal }),
    placeholderData: (previous) => previous, // keep the old page visible while the next one loads
  });
}

export function useTicket(id: number) {
  return useQuery({
    queryKey: keys.ticket(id),
    queryFn: ({ signal }) => apiFetch<TicketDetail>(`/api/tickets/${id}`, { signal }),
  });
}

export function useHistory(id: number) {
  return useQuery({
    queryKey: keys.history(id),
    queryFn: ({ signal }) => apiFetch<AuditEntry[]>(`/api/tickets/${id}/history`, { signal }),
  });
}

/** After any change to a ticket: refresh it, the lists, and the numbers that depend on it. */
function useTicketChanged() {
  const queryClient = useQueryClient();
  return (detail?: TicketDetail | null) => {
    if (detail) queryClient.setQueryData(keys.ticket(detail.ticket.id), detail);
    void queryClient.invalidateQueries({ queryKey: keys.tickets });
    void queryClient.invalidateQueries({ queryKey: keys.queues });
    void queryClient.invalidateQueries({ queryKey: keys.dashboard });
  };
}

export function useTicketAction(id: number) {
  const changed = useTicketChanged();
  return useMutation({
    mutationFn: ({ action, assigneeId }: { action: TicketAction; assigneeId?: number }) =>
      apiFetch<TicketDetail>(`/api/tickets/${id}/actions/${action}`, {
        method: "POST",
        body: assigneeId === undefined ? {} : { assigneeId },
      }),
    onSuccess: changed,
  });
}

export function useReply(id: number) {
  const changed = useTicketChanged();
  return useMutation({
    mutationFn: (reply: { body: string; visibility: Visibility }) =>
      apiFetch<Message>(`/api/tickets/${id}/messages`, { method: "POST", body: reply }),
    onSuccess: () => changed(),
  });
}

export function useChangePriority(id: number) {
  const changed = useTicketChanged();
  return useMutation({
    mutationFn: (priority: Priority) =>
      apiFetch<TicketDetail>(`/api/tickets/${id}/priority`, { method: "PUT", body: { priority } }),
    onSuccess: changed,
  });
}

export type NewTicket = {
  customerEmail: string;
  customerName: string;
  subject: string;
  description: string;
  queueId: number;
  priority: Priority;
};

export function useCreateTicket() {
  const changed = useTicketChanged();
  return useMutation({
    mutationFn: (ticket: NewTicket) => apiFetch<TicketDetail>("/api/tickets", { method: "POST", body: ticket }),
    onSuccess: changed,
  });
}

/** "Next ticket": resolves to the claimed ticket, or null when the queue is empty. */
export function useClaimNext() {
  const changed = useTicketChanged();
  return useMutation({
    mutationFn: (queueId: number) => apiFetch<TicketDetail | null>(`/api/queues/${queueId}/next`, { method: "POST" }),
    onSuccess: changed,
  });
}

export function useNotifications() {
  return useQuery({
    queryKey: keys.notifications,
    queryFn: ({ signal }) => apiFetch<NotificationList>("/api/notifications", { signal }),
  });
}

export function useMarkNotificationsRead() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id?: number) =>
      apiFetch<{ updated: number }>(id ? `/api/notifications/${id}/read` : "/api/notifications/read-all", {
        method: "POST",
      }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: keys.notifications }),
  });
}

export function useSlaPolicies() {
  return useQuery({ queryKey: keys.policies, queryFn: ({ signal }) => apiFetch<SlaPolicy[]>("/api/sla-policies", { signal }) });
}

export function useCalendars() {
  return useQuery({ queryKey: keys.calendars, queryFn: ({ signal }) => apiFetch<Calendar[]>("/api/calendars", { signal }) });
}

export type PolicyUpdate = Pick<
  SlaPolicy,
  "firstResponseMinutes" | "resolutionMinutes" | "warningPercent" | "calendarId" | "version"
>;

export function useUpdatePolicy() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ priority, update }: { priority: Priority; update: PolicyUpdate }) =>
      apiFetch<SlaPolicy>(`/api/sla-policies/${priority}`, { method: "PUT", body: update }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: keys.policies }),
  });
}

export function useDashboard() {
  return useQuery({
    queryKey: keys.dashboard,
    queryFn: ({ signal }) => apiFetch<Dashboard>("/api/dashboard/sla", { signal }),
    refetchInterval: 30_000, // countdowns and "at risk" change with time alone, not only with events
  });
}
