import { useState } from "react";
import { useParams } from "react-router";

import { useAgents, useChangePriority, useHistory, useReply, useTicket, useTicketAction } from "../api/hooks";
import type { Me, Priority, TicketAction, TicketDetail, Visibility } from "../api/types";
import { SlaBadge } from "../components/SlaBadge";
import { useNow } from "../lib/useNow";
import { Badge, Button, Card, ErrorText, inputClass, Spinner } from "../components/ui";
import {
  actionLabel,
  formatDateTime,
  formatDuration,
  humanize,
  metricLabel,
  priorityStyle,
  statusLabel,
  statusStyle,
} from "../lib/format";
import { NotFoundPage } from "./NotFoundPage";

const PRIORITIES: Priority[] = ["LOW", "NORMAL", "HIGH", "URGENT"];

export function TicketPage({ me }: { me: Me }) {
  const id = Number(useParams().id);
  const ticket = useTicket(id);

  if (ticket.isPending) return <Spinner />;
  if (ticket.error) {
    return "status" in ticket.error && ticket.error.status === 404 ? <NotFoundPage /> : <ErrorText error={ticket.error} />;
  }
  return <TicketView detail={ticket.data} fetchedAt={ticket.dataUpdatedAt} me={me} />;
}

export function TicketView({ detail, fetchedAt, me }: { detail: TicketDetail; fetchedAt: number; me: Me }) {
  const { ticket } = detail;
  const now = useNow();
  const action = useTicketAction(ticket.id);
  const priority = useChangePriority(ticket.id);

  return (
    <div className="grid gap-4 lg:grid-cols-3">
      <div className="space-y-4 lg:col-span-2">
        <Card>
          <div className="flex flex-wrap items-start justify-between gap-3">
            <div>
              <div className="text-sm text-slate-500">
                {ticket.number} · {ticket.queue.name} · via {ticket.channel === "EMAIL" ? "email" : "agent"}
              </div>
              <h1 className="text-lg font-semibold text-slate-900">{ticket.subject}</h1>
              <div className="mt-1 text-sm text-slate-600">
                {ticket.customer.name} &lt;{ticket.customer.email}&gt;
              </div>
            </div>
            <div className="flex flex-col items-end gap-2">
              <Badge className={statusStyle[ticket.status]}>{statusLabel[ticket.status]}</Badge>
              {ticket.escalationLevel > 0 && (
                <Badge className="bg-red-600 text-white ring-red-600">Escalated, level {ticket.escalationLevel}</Badge>
              )}
            </div>
          </div>

          <div className="mt-4 flex flex-wrap items-center gap-2">
            {detail.allowedActions.map((allowed) =>
              allowed === "ASSIGN" ? (
                <AssignControl key={allowed} detail={detail} me={me} onAssign={(assigneeId) => action.mutate({ action: "ASSIGN", assigneeId })} />
              ) : (
                <Button
                  key={allowed}
                  variant={allowed === "RESOLVE" ? "primary" : "secondary"}
                  disabled={action.isPending}
                  onClick={() => action.mutate({ action: allowed as TicketAction })}
                >
                  {actionLabel[allowed]}
                </Button>
              ),
            )}
          </div>
          <div className="mt-2"><ErrorText error={action.error ?? priority.error} /></div>
        </Card>

        <Conversation detail={detail} />
        <ReplyBox detail={detail} />
      </div>

      <div className="space-y-4">
        <Card title="SLA">
          <ul className="space-y-3">
            {ticket.sla.map((timer) => (
              <li key={timer.metric} className="text-sm">
                <div className="flex items-center justify-between">
                  <span className="font-medium text-slate-700">{metricLabel[timer.metric]}</span>
                  <SlaBadge timer={timer} fetchedAt={fetchedAt} now={now} showMetric={false} />
                </div>
                <div className="mt-1 text-xs text-slate-500">
                  Target {formatDuration(timer.targetMinutes * 60)} of business time
                  {timer.dueAt && <> · due {formatDateTime(timer.dueAt)}</>}
                  {timer.clockState === "PAUSED" && <> · clock paused while waiting on the customer</>}
                </div>
              </li>
            ))}
          </ul>
        </Card>

        <Card title="Details">
          <dl className="grid grid-cols-3 gap-y-2 text-sm">
            <dt className="text-slate-500">Assignee</dt>
            <dd className="col-span-2">{ticket.assignee?.name ?? "nobody yet"}</dd>
            <dt className="text-slate-500">Priority</dt>
            <dd className="col-span-2">
              {detail.canChangePriority ? (
                <select
                  aria-label="Priority"
                  className={`${inputClass} mt-0 w-auto py-0.5`}
                  value={ticket.priority}
                  disabled={priority.isPending}
                  onChange={(e) => priority.mutate(e.target.value as Priority)}
                >
                  {PRIORITIES.map((p) => <option key={p} value={p}>{p.toLowerCase()}</option>)}
                </select>
              ) : (
                <span className={priorityStyle[ticket.priority]}>{ticket.priority.toLowerCase()}</span>
              )}
            </dd>
            <dt className="text-slate-500">Created</dt>
            <dd className="col-span-2">{formatDateTime(ticket.createdAt)}</dd>
            {detail.firstRespondedAt && (
              <>
                <dt className="text-slate-500">First reply</dt>
                <dd className="col-span-2">{formatDateTime(detail.firstRespondedAt)}</dd>
              </>
            )}
          </dl>
        </Card>

        <HistoryCard ticketId={ticket.id} />
      </div>
    </div>
  );
}

/** "Assign to me" for everyone allowed to; supervisors can also pick someone else. */
function AssignControl({ detail, me, onAssign }: { detail: TicketDetail; me: Me; onAssign: (assigneeId?: number) => void }) {
  const isSupervisor = me.roles.includes("SUPERVISOR") || me.roles.includes("ADMIN");
  const agents = useAgents();
  const [chosen, setChosen] = useState("");
  const mine = detail.ticket.assignee?.id === me.id;
  return (
    <span className="flex items-center gap-2">
      {!mine && <Button onClick={() => onAssign(undefined)}>Assign to me</Button>}
      {isSupervisor && (
        <>
          <select aria-label="Assign to" className={`${inputClass} mt-0 w-auto`} value={chosen} onChange={(e) => setChosen(e.target.value)}>
            <option value="">Assign to…</option>
            {(agents.data ?? []).filter((agent) => agent.id !== detail.ticket.assignee?.id).map((agent) => (
              <option key={agent.id} value={agent.id}>{agent.displayName}</option>
            ))}
          </select>
          <Button variant="secondary" disabled={!chosen} onClick={() => onAssign(Number(chosen))}>Assign</Button>
        </>
      )}
    </span>
  );
}

function Conversation({ detail }: { detail: TicketDetail }) {
  return (
    <Card title="Conversation">
      <ol className="space-y-3">
        {detail.messages.map((message) => {
          const internal = message.visibility === "INTERNAL";
          const fromCustomer = message.authorType === "CUSTOMER";
          return (
            <li
              key={message.id}
              className={`rounded-lg p-3 text-sm ring-1 ${
                internal ? "bg-amber-50 ring-amber-200" : fromCustomer ? "bg-white ring-slate-200" : "bg-indigo-50 ring-indigo-100"
              }`}
            >
              <div className="mb-1 flex justify-between text-xs text-slate-500">
                <span className="font-medium text-slate-700">
                  {message.authorName}
                  {internal && " · internal note"}
                  {message.viaEmail && " · by email"}
                </span>
                <span>{formatDateTime(message.createdAt)}</span>
              </div>
              {/* Plain text only: message bodies come from emails and are never rendered as HTML. */}
              <p className="whitespace-pre-wrap text-slate-800">{message.body}</p>
            </li>
          );
        })}
      </ol>
    </Card>
  );
}

function ReplyBox({ detail }: { detail: TicketDetail }) {
  const reply = useReply(detail.ticket.id);
  const [body, setBody] = useState("");
  const [visibility, setVisibility] = useState<Visibility>(detail.canReplyPublicly ? "PUBLIC" : "INTERNAL");
  if (detail.ticket.status === "CLOSED") return null;

  return (
    <Card title="Reply">
      <form
        className="space-y-3"
        onSubmit={(event) => {
          event.preventDefault();
          reply.mutate({ body, visibility }, { onSuccess: () => setBody("") });
        }}
      >
        <div className="flex gap-4 text-sm">
          <label className="flex items-center gap-1.5">
            <input type="radio" name="visibility" checked={visibility === "PUBLIC"} disabled={!detail.canReplyPublicly}
              onChange={() => setVisibility("PUBLIC")} />
            Reply to customer (emailed)
          </label>
          <label className="flex items-center gap-1.5">
            <input type="radio" name="visibility" checked={visibility === "INTERNAL"} onChange={() => setVisibility("INTERNAL")} />
            Internal note
          </label>
        </div>
        {!detail.canReplyPublicly && (
          <p className="text-xs text-slate-500">Only the assignee or a supervisor can reply to the customer.</p>
        )}
        <textarea aria-label="Message" required rows={4} className={inputClass} value={body} onChange={(e) => setBody(e.target.value)} />
        <ErrorText error={reply.error} />
        <Button type="submit" disabled={reply.isPending || !body.trim()}>
          {visibility === "PUBLIC" ? "Send reply" : "Add note"}
        </Button>
      </form>
    </Card>
  );
}

function HistoryCard({ ticketId }: { ticketId: number }) {
  const history = useHistory(ticketId);
  return (
    <Card title="History">
      {history.isPending ? (
        <Spinner />
      ) : (
        <ol className="space-y-2 text-xs">
          {(history.data ?? []).map((entry) => (
            <li key={entry.id} className="border-l-2 border-slate-200 pl-2">
              <div className="font-medium text-slate-700">
                {humanize(entry.action)} <span className="font-normal text-slate-500">by {entry.actorName}</span>
              </div>
              <div className="text-slate-500">{formatDateTime(entry.occurredAt)}</div>
              {Object.keys(entry.details).length > 0 && (
                <div className="text-slate-500">
                  {Object.entries(entry.details)
                    .filter(([, value]) => value !== null)
                    .map(([key, value]) => `${key}: ${String(value)}`)
                    .join(", ")}
                </div>
              )}
            </li>
          ))}
        </ol>
      )}
    </Card>
  );
}
