import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import type { ReactNode } from "react";
import { MemoryRouter } from "react-router";
import { describe, expect, it } from "vitest";

import type { Me, TicketDetail } from "../api/types";
import { TicketView } from "./TicketPage";

const me: Me = { id: 7, username: "ann", displayName: "Ann Agent", roles: ["AGENT"], queueIds: [1] };

function detail(overrides: Partial<TicketDetail> = {}): TicketDetail {
  return {
    ticket: {
      id: 1001,
      number: "HD-1001",
      subject: "Printer on fire",
      status: "OPEN",
      priority: "URGENT",
      channel: "EMAIL",
      queue: { id: 1, key: "general", name: "General support" },
      customer: { id: 3, email: "jane@example.test", name: "Jane Doe" },
      assignee: { id: 7, name: "Ann Agent" },
      escalationLevel: 1,
      createdAt: "2026-11-23T10:00:00Z",
      updatedAt: "2026-11-23T10:05:00Z",
      version: 3,
      sla: [
        {
          metric: "FIRST_RESPONSE", health: "BREACHED", clockState: "RUNNING", targetMinutes: 30,
          dueAt: "2026-11-23T10:30:00Z", warnedAt: null, breachedAt: "2026-11-23T10:30:00Z", metAt: null,
          elapsedSeconds: 2000, remainingSeconds: -200, ticking: true,
        },
        {
          metric: "RESOLUTION", health: "PAUSED", clockState: "PAUSED", targetMinutes: 240,
          dueAt: null, warnedAt: null, breachedAt: null, metAt: null,
          elapsedSeconds: 600, remainingSeconds: 13_800, ticking: false,
        },
      ],
    },
    firstRespondedAt: null,
    resolvedAt: null,
    closedAt: null,
    messages: [
      {
        id: 1, authorType: "CUSTOMER", authorName: "Jane Doe", visibility: "PUBLIC",
        body: "<img src=x onerror=alert(1)> it smokes", createdAt: "2026-11-23T10:00:00Z", viaEmail: true,
      },
    ],
    allowedActions: ["WAIT_ON_CUSTOMER", "RESOLVE"],
    canReplyPublicly: false,
    canChangePriority: false,
    ...overrides,
  };
}

function renderPage(ticket: TicketDetail) {
  // Queries are disabled: the page gets its data as props, and nothing here may hit the network.
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, enabled: false } } });
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>{children}</MemoryRouter>
    </QueryClientProvider>
  );
  return render(<TicketView detail={ticket} fetchedAt={Date.now()} me={me} />, { wrapper });
}

describe("TicketView", () => {
  it("offers exactly the actions the server allows, nothing more", () => {
    renderPage(detail());
    expect(screen.getByRole("button", { name: "Wait on customer" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Resolve" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Reopen" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Close" })).not.toBeInTheDocument();
  });

  it("shows the escalation and both SLA clocks", () => {
    renderPage(detail());
    expect(screen.getByText("Escalated, level 1")).toBeInTheDocument();
    expect(screen.getByText(/overdue/)).toBeInTheDocument();
    expect(screen.getByText(/paused, 3h 50m left/)).toBeInTheDocument();
  });

  it("renders email bodies as plain text, never as HTML", () => {
    const { container } = renderPage(detail());
    expect(screen.getByText("<img src=x onerror=alert(1)> it smokes")).toBeInTheDocument();
    expect(container.querySelector("img")).toBeNull();
  });

  it("only lets the people allowed to reply to the customer choose a public reply", () => {
    renderPage(detail({ canReplyPublicly: false }));
    expect(screen.getByRole("radio", { name: /Reply to customer/ })).toBeDisabled();
    expect(screen.getByRole("radio", { name: /Internal note/ })).toBeChecked();
  });
});
