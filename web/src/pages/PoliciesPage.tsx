import { useState } from "react";

import { ApiError } from "../api/client";
import { useCalendars, useSlaPolicies, useUpdatePolicy, type PolicyUpdate } from "../api/hooks";
import type { Me, SlaPolicy } from "../api/types";
import { Button, Card, ErrorText, inputClass, Spinner } from "../components/ui";
import { formatDuration } from "../lib/format";

/** SLA targets per priority. Everyone can read them; admins can change them. */
export function PoliciesPage({ me }: { me: Me }) {
  const policies = useSlaPolicies();
  const isAdmin = me.roles.includes("ADMIN");
  if (policies.isPending) return <Spinner />;
  if (policies.error) return <ErrorText error={policies.error} />;

  return (
    <Card title="SLA policies">
      <p className="mb-4 text-sm text-slate-600">
        Targets are in business time on the policy's calendar. A change applies to tickets created (or re-prioritised)
        after it; running clocks keep the targets they started with.
      </p>
      <table className="min-w-full text-sm">
        <thead className="text-left text-xs uppercase tracking-wide text-slate-500">
          <tr>
            <th className="py-2">Priority</th>
            <th className="py-2">First response</th>
            <th className="py-2">Resolution</th>
            <th className="py-2">Warn at</th>
            <th className="py-2">Calendar</th>
            {isAdmin && <th className="py-2" />}
          </tr>
        </thead>
        <tbody className="divide-y divide-slate-100">
          {policies.data.map((policy) => <PolicyRow key={policy.priority} policy={policy} editable={isAdmin} />)}
        </tbody>
      </table>
    </Card>
  );
}

function PolicyRow({ policy, editable }: { policy: SlaPolicy; editable: boolean }) {
  const [editing, setEditing] = useState<PolicyUpdate | null>(null);
  const update = useUpdatePolicy();
  const calendars = useCalendars();

  if (!editing) {
    return (
      <tr>
        <td className="py-2 font-medium">{policy.priority.toLowerCase()}</td>
        <td className="py-2">{formatDuration(policy.firstResponseMinutes * 60)}</td>
        <td className="py-2">{formatDuration(policy.resolutionMinutes * 60)}</td>
        <td className="py-2">{policy.warningPercent}%</td>
        <td className="py-2">{policy.calendarName}</td>
        {editable && (
          <td className="py-2 text-right">
            <Button variant="secondary" onClick={() => setEditing({ ...policy })}>Edit</Button>
          </td>
        )}
      </tr>
    );
  }

  const number = (field: "firstResponseMinutes" | "resolutionMinutes" | "warningPercent") => (
    <input
      aria-label={field}
      type="number"
      min={1}
      className={`${inputClass} mt-0 w-24`}
      value={editing[field]}
      onChange={(e) => setEditing({ ...editing, [field]: Number(e.target.value) })}
    />
  );
  const fieldError = update.error instanceof ApiError ? update.error.fieldErrors.map((f) => f.message).join(" ") : "";

  return (
    <tr className="bg-indigo-50/40">
      <td className="py-2 font-medium">{policy.priority.toLowerCase()}</td>
      <td className="py-2">{number("firstResponseMinutes")} min</td>
      <td className="py-2">{number("resolutionMinutes")} min</td>
      <td className="py-2">{number("warningPercent")} %</td>
      <td className="py-2">
        <select
          aria-label="Calendar"
          className={`${inputClass} mt-0`}
          value={editing.calendarId}
          onChange={(e) => setEditing({ ...editing, calendarId: Number(e.target.value) })}
        >
          {(calendars.data ?? []).map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
        </select>
      </td>
      <td className="space-x-2 py-2 text-right">
        <Button
          disabled={update.isPending}
          onClick={() => update.mutate({ priority: policy.priority, update: editing }, { onSuccess: () => setEditing(null) })}
        >
          Save
        </Button>
        <Button variant="secondary" onClick={() => setEditing(null)}>Cancel</Button>
        {update.error && <div className="mt-1 text-left"><ErrorText error={fieldError ? new Error(fieldError) : update.error} /></div>}
      </td>
    </tr>
  );
}
