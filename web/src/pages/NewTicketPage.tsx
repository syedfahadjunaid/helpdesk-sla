import { useState } from "react";
import { useNavigate } from "react-router";

import { ApiError } from "../api/client";
import { useCreateTicket, useQueues, type NewTicket } from "../api/hooks";
import type { Priority } from "../api/types";
import { Button, Card, ErrorText, inputClass, Label } from "../components/ui";

/** An agent logs a ticket for a customer (e.g. after a phone call). Email tickets arrive by themselves. */
export function NewTicketPage() {
  const queues = useQueues();
  const create = useCreateTicket();
  const navigate = useNavigate();
  const [form, setForm] = useState<Omit<NewTicket, "queueId"> & { queueId: string }>({
    customerEmail: "",
    customerName: "",
    subject: "",
    description: "",
    queueId: "",
    priority: "NORMAL",
  });
  const fieldError = (field: string) => (create.error instanceof ApiError ? create.error.messageFor(field) : undefined);
  const set = (field: keyof typeof form) => (e: { target: { value: string } }) =>
    setForm((f) => ({ ...f, [field]: e.target.value }));

  return (
    <Card title="New ticket">
      <form
        className="grid max-w-2xl gap-4"
        onSubmit={(event) => {
          event.preventDefault();
          create.mutate(
            { ...form, queueId: Number(form.queueId) },
            { onSuccess: (detail) => void navigate(`/tickets/${detail.ticket.id}`) },
          );
        }}
      >
        <div className="grid gap-4 sm:grid-cols-2">
          <div>
            <Label htmlFor="customerEmail">Customer email</Label>
            <input id="customerEmail" type="email" required className={inputClass} value={form.customerEmail} onChange={set("customerEmail")} />
            <FieldError message={fieldError("customerEmail")} />
          </div>
          <div>
            <Label htmlFor="customerName">Customer name</Label>
            <input id="customerName" className={inputClass} value={form.customerName} onChange={set("customerName")} />
          </div>
        </div>
        <div>
          <Label htmlFor="subject">Subject</Label>
          <input id="subject" required className={inputClass} value={form.subject} onChange={set("subject")} />
          <FieldError message={fieldError("subject")} />
        </div>
        <div>
          <Label htmlFor="description">What's the problem? (kept as an internal note)</Label>
          <textarea id="description" required rows={5} className={inputClass} value={form.description} onChange={set("description")} />
        </div>
        <div className="grid gap-4 sm:grid-cols-2">
          <div>
            <Label htmlFor="queueId">Queue</Label>
            <select id="queueId" required className={inputClass} value={form.queueId} onChange={set("queueId")}>
              <option value="">Choose…</option>
              {(queues.data ?? []).map((queue) => <option key={queue.id} value={queue.id}>{queue.name}</option>)}
            </select>
          </div>
          <div>
            <Label htmlFor="priority">Priority</Label>
            <select id="priority" className={inputClass} value={form.priority} onChange={set("priority")}>
              {(["LOW", "NORMAL", "HIGH", "URGENT"] as Priority[]).map((p) => <option key={p} value={p}>{p.toLowerCase()}</option>)}
            </select>
          </div>
        </div>
        <ErrorText error={create.error} />
        <div>
          <Button type="submit" disabled={create.isPending}>Create ticket</Button>
        </div>
      </form>
    </Card>
  );
}

function FieldError({ message }: { message?: string }) {
  return message ? <p className="mt-1 text-xs text-red-700">{message}</p> : null;
}
