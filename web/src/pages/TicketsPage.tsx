import { useState } from "react";
import { Link, useNavigate } from "react-router";

import { useClaimNext, useQueues, useTickets, type TicketFilter } from "../api/hooks";
import type { Me, TicketStatus } from "../api/types";
import { SlaBadge } from "../components/SlaBadge";
import { useNow } from "../lib/useNow";
import { Badge, Button, Card, ErrorText, inputClass, Spinner } from "../components/ui";
import { formatDateTime, priorityStyle, statusLabel, statusStyle } from "../lib/format";

const ACTIVE: TicketStatus[] = ["NEW", "OPEN", "WAITING_ON_CUSTOMER"];
const VIEWS = {
  active: { label: "Active", statuses: ACTIVE },
  resolved: { label: "Resolved", statuses: ["RESOLVED"] as TicketStatus[] },
  closed: { label: "Closed", statuses: ["CLOSED"] as TicketStatus[] },
  all: { label: "All", statuses: [] as TicketStatus[] },
};

/** The ticket list, with filters. Live updates keep it current without reloading. */
export function TicketsPage({ me }: { me: Me }) {
  const [view, setView] = useState<keyof typeof VIEWS>("active");
  const [filter, setFilter] = useState<Omit<TicketFilter, "statuses">>({ page: 0 });
  const tickets = useTickets({ ...filter, statuses: VIEWS[view].statuses });
  const queues = useQueues();
  const claimNext = useClaimNext();
  const navigate = useNavigate();
  const now = useNow();
  const [nothingWaiting, setNothingWaiting] = useState<number | null>(null);

  const myQueues = (queues.data ?? []).filter((queue) => me.queueIds.includes(queue.id));

  async function next(queueId: number) {
    setNothingWaiting(null);
    const claimed = await claimNext.mutateAsync(queueId);
    if (claimed) {
      void navigate(`/tickets/${claimed.ticket.id}`);
    } else {
      setNothingWaiting(queueId);
    }
  }

  return (
    <div className="space-y-4">
      {myQueues.length > 0 && (
        <div className="flex flex-wrap items-center gap-3">
          {myQueues.map((queue) => (
            <Button key={queue.id} onClick={() => void next(queue.id)} disabled={claimNext.isPending}>
              Next ticket from {queue.name}
              <span className="rounded bg-indigo-500 px-1.5 text-xs">{queue.unassignedTickets}</span>
            </Button>
          ))}
          {nothingWaiting !== null && <span className="text-sm text-slate-500">Nothing waiting in that queue.</span>}
          <ErrorText error={claimNext.error} />
        </div>
      )}

      <Card
        title="Tickets"
        actions={
          <div className="flex flex-wrap items-center gap-2">
            {(Object.keys(VIEWS) as (keyof typeof VIEWS)[]).map((key) => (
              <button
                key={key}
                type="button"
                onClick={() => {
                  setView(key);
                  setFilter((f) => ({ ...f, page: 0 }));
                }}
                className={`rounded-md px-2.5 py-1 text-xs font-medium ${view === key ? "bg-indigo-600 text-white" : "bg-slate-100 text-slate-700"}`}
              >
                {VIEWS[key].label}
              </button>
            ))}
          </div>
        }
      >
        <div className="mb-4 grid gap-3 sm:grid-cols-4">
          <select
            aria-label="Queue"
            className={inputClass}
            value={filter.queueId ?? ""}
            onChange={(e) => setFilter((f) => ({ ...f, page: 0, queueId: e.target.value ? Number(e.target.value) : undefined }))}
          >
            <option value="">All queues</option>
            {(queues.data ?? []).map((queue) => (
              <option key={queue.id} value={queue.id}>{queue.name}</option>
            ))}
          </select>
          <select
            aria-label="Assignee"
            className={inputClass}
            value={filter.assignee ?? ""}
            onChange={(e) =>
              setFilter((f) => ({ ...f, page: 0, assignee: (e.target.value || undefined) as TicketFilter["assignee"] }))}
          >
            <option value="">Anyone</option>
            <option value="me">Assigned to me</option>
            <option value="unassigned">Unassigned</option>
          </select>
          <label className="flex items-center gap-2 text-sm text-slate-700">
            <input
              type="checkbox"
              checked={filter.escalated ?? false}
              onChange={(e) => setFilter((f) => ({ ...f, page: 0, escalated: e.target.checked || undefined }))}
            />
            Escalated only
          </label>
          <input
            aria-label="Search"
            placeholder="Search subject or HD-number"
            className={inputClass}
            value={filter.q ?? ""}
            onChange={(e) => setFilter((f) => ({ ...f, page: 0, q: e.target.value }))}
          />
        </div>

        <ErrorText error={tickets.error} />
        {tickets.isPending ? (
          <Spinner />
        ) : (
          <div className="overflow-x-auto">
            <table className="min-w-full text-sm">
              <thead className="text-left text-xs uppercase tracking-wide text-slate-500">
                <tr>
                  <th className="py-2 pr-3">Ticket</th>
                  <th className="py-2 pr-3">Status</th>
                  <th className="py-2 pr-3">Priority</th>
                  <th className="py-2 pr-3">SLA</th>
                  <th className="py-2 pr-3">Assignee</th>
                  <th className="py-2 pr-3">Updated</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {tickets.data?.items.map((ticket) => (
                  <tr key={ticket.id} className={ticket.escalationLevel > 0 ? "bg-red-50/40" : ""}>
                    <td className="py-2 pr-3">
                      <Link to={`/tickets/${ticket.id}`} className="font-medium text-indigo-700 hover:underline">
                        {ticket.number}
                      </Link>{" "}
                      <span className="text-slate-800">{ticket.subject}</span>
                      <div className="text-xs text-slate-500">
                        {ticket.customer.name} · {ticket.queue.name}
                        {ticket.escalationLevel > 0 && (
                          <span className="ml-2 font-semibold text-red-700">escalated (level {ticket.escalationLevel})</span>
                        )}
                      </div>
                    </td>
                    <td className="py-2 pr-3"><Badge className={statusStyle[ticket.status]}>{statusLabel[ticket.status]}</Badge></td>
                    <td className={`py-2 pr-3 ${priorityStyle[ticket.priority]}`}>{ticket.priority.toLowerCase()}</td>
                    <td className="space-y-1 py-2 pr-3">
                      {ticket.sla.map((timer) => (
                        <div key={timer.metric}>
                          <SlaBadge timer={timer} fetchedAt={tickets.dataUpdatedAt} now={now} />
                        </div>
                      ))}
                    </td>
                    <td className="py-2 pr-3 text-slate-700">{ticket.assignee?.name ?? <span className="text-slate-400">nobody</span>}</td>
                    <td className="py-2 pr-3 text-slate-500">{formatDateTime(ticket.updatedAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            {tickets.data?.items.length === 0 && <p className="py-6 text-center text-sm text-slate-500">No tickets here.</p>}
          </div>
        )}

        {tickets.data && tickets.data.totalPages > 1 && (
          <div className="mt-4 flex items-center justify-between text-sm">
            <Button variant="secondary" disabled={filter.page === 0} onClick={() => setFilter((f) => ({ ...f, page: f.page - 1 }))}>
              Previous
            </Button>
            <span className="text-slate-500">
              Page {tickets.data.page + 1} of {tickets.data.totalPages} ({tickets.data.totalItems} tickets)
            </span>
            <Button
              variant="secondary"
              disabled={filter.page + 1 >= tickets.data.totalPages}
              onClick={() => setFilter((f) => ({ ...f, page: f.page + 1 }))}
            >
              Next
            </Button>
          </div>
        )}
      </Card>
    </div>
  );
}
