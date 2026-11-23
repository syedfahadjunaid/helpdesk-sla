/** Shapes of the API's JSON, mirroring the Java records (TicketDtos and friends). */

export type Role = "AGENT" | "SUPERVISOR" | "ADMIN";
export type TicketStatus = "NEW" | "OPEN" | "WAITING_ON_CUSTOMER" | "RESOLVED" | "CLOSED";
export type Priority = "LOW" | "NORMAL" | "HIGH" | "URGENT";
export type TicketAction = "ASSIGN" | "WAIT_ON_CUSTOMER" | "RESUME" | "RESOLVE" | "REOPEN" | "CLOSE";
export type Visibility = "PUBLIC" | "INTERNAL";
export type SlaMetric = "FIRST_RESPONSE" | "RESOLUTION";
export type SlaHealth = "ON_TRACK" | "AT_RISK" | "BREACHED" | "PAUSED" | "MET" | "STOPPED";
export type ClockState = "RUNNING" | "PAUSED" | "STOPPED";

export type Me = { id: number; username: string; displayName: string; roles: Role[]; queueIds: number[] };

export type Agent = { id: number; username: string; displayName: string; supervisor: boolean };

export type Queue = {
  id: number;
  key: string;
  name: string;
  inboundAddress: string | null;
  defaultQueue: boolean;
  memberIds: number[];
  openTickets: number;
  unassignedTickets: number;
};

export type SlaTimer = {
  metric: SlaMetric;
  health: SlaHealth;
  clockState: ClockState;
  targetMinutes: number;
  dueAt: string | null;
  warnedAt: string | null;
  breachedAt: string | null;
  metAt: string | null;
  elapsedSeconds: number;
  remainingSeconds: number;
  ticking: boolean;
};

export type TicketSummary = {
  id: number;
  number: string;
  subject: string;
  status: TicketStatus;
  priority: Priority;
  channel: "EMAIL" | "AGENT";
  queue: { id: number; key: string; name: string };
  customer: { id: number; email: string; name: string };
  assignee: { id: number; name: string } | null;
  escalationLevel: number;
  createdAt: string;
  updatedAt: string;
  version: number;
  sla: SlaTimer[];
};

export type Message = {
  id: number;
  authorType: "CUSTOMER" | "AGENT";
  authorName: string;
  visibility: Visibility;
  body: string;
  createdAt: string;
  viaEmail: boolean;
};

export type TicketDetail = {
  ticket: TicketSummary;
  firstRespondedAt: string | null;
  resolvedAt: string | null;
  closedAt: string | null;
  messages: Message[];
  allowedActions: TicketAction[];
  canReplyPublicly: boolean;
  canChangePriority: boolean;
};

export type Page<T> = { items: T[]; page: number; size: number; totalItems: number; totalPages: number };

export type AuditEntry = {
  id: number;
  actorType: "AGENT" | "CUSTOMER" | "SYSTEM";
  actorName: string;
  action: string;
  details: Record<string, unknown>;
  occurredAt: string;
};

export type Notification = {
  id: number;
  ticketId: number | null;
  kind: string;
  message: string;
  createdAt: string;
  readAt: string | null;
};

export type NotificationList = { unread: number; items: Notification[] };

export type SlaPolicy = {
  priority: Priority;
  firstResponseMinutes: number;
  resolutionMinutes: number;
  warningPercent: number;
  calendarId: number;
  calendarName: string;
  updatedAt: string;
  version: number;
};

export type Calendar = { id: number; name: string; timeZone: string };

export type Dashboard = {
  generatedAt: string;
  windowDays: number;
  queues: {
    queueId: number;
    queueName: string;
    active: number;
    unassigned: number;
    waitingOnCustomer: number;
    atRisk: number;
    escalated: number;
  }[];
  compliance: {
    metric: SlaMetric;
    met: number;
    breached: number;
    compliancePercent: number | null;
    averageBusinessSeconds: number | null;
  }[];
  breachesByDay: { day: string; breaches: number }[];
  dueSoon: {
    ticketId: number;
    ticketNumber: string;
    subject: string;
    priority: Priority;
    metric: SlaMetric;
    dueAt: string;
    assignee: string | null;
  }[];
};
