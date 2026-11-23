import { Link } from "react-router";

import { useDashboard } from "../api/hooks";
import { useNow } from "../lib/useNow";
import { Card, ErrorText, Spinner } from "../components/ui";
import { formatDuration, metricLabel, priorityStyle } from "../lib/format";

/** SLA health at a glance: queue load, compliance over the last 7 days, breaches per day, what's due next. */
export function DashboardPage() {
  const dashboard = useDashboard();
  const now = useNow();
  if (dashboard.isPending) return <Spinner />;
  if (dashboard.error) return <ErrorText error={dashboard.error} />;
  const data = dashboard.data;
  const maxBreaches = Math.max(1, ...data.breachesByDay.map((day) => day.breaches));

  return (
    <div className="space-y-4">
      <div className="grid gap-4 sm:grid-cols-2">
        {(["FIRST_RESPONSE", "RESOLUTION"] as const).map((metric) => {
          const row = data.compliance.find((c) => c.metric === metric);
          return (
            <Card key={metric} title={`${metricLabel[metric]}, last ${data.windowDays} days`}>
              <div className="flex items-end justify-between">
                <div>
                  <div className={`text-3xl font-semibold ${percentColour(row?.compliancePercent ?? null)}`}>
                    {row?.compliancePercent != null ? `${row.compliancePercent}%` : "n/a"}
                  </div>
                  <div className="text-sm text-slate-500">targets met on time</div>
                </div>
                <div className="text-right text-sm text-slate-600">
                  <div>{row?.met ?? 0} met · {row?.breached ?? 0} breached</div>
                  {row?.averageBusinessSeconds != null && (
                    <div>average {formatDuration(row.averageBusinessSeconds)} of business time</div>
                  )}
                </div>
              </div>
            </Card>
          );
        })}
      </div>

      <Card title="Queues right now">
        <table className="min-w-full text-sm">
          <thead className="text-left text-xs uppercase tracking-wide text-slate-500">
            <tr>
              <th className="py-2">Queue</th>
              <th className="py-2 text-right">Active</th>
              <th className="py-2 text-right">Unassigned</th>
              <th className="py-2 text-right">Waiting on customer</th>
              <th className="py-2 text-right">At risk</th>
              <th className="py-2 text-right">Escalated</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {data.queues.map((queue) => (
              <tr key={queue.queueId}>
                <td className="py-2">{queue.queueName}</td>
                <td className="py-2 text-right">{queue.active}</td>
                <td className="py-2 text-right">{queue.unassigned}</td>
                <td className="py-2 text-right">{queue.waitingOnCustomer}</td>
                <td className={`py-2 text-right ${queue.atRisk > 0 ? "font-semibold text-amber-700" : ""}`}>{queue.atRisk}</td>
                <td className={`py-2 text-right ${queue.escalated > 0 ? "font-semibold text-red-700" : ""}`}>{queue.escalated}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </Card>

      <div className="grid gap-4 lg:grid-cols-2">
        <Card title="Breaches per day">
          {data.breachesByDay.length === 0 ? (
            <p className="text-sm text-slate-500">No breaches in the last {data.windowDays} days.</p>
          ) : (
            <div className="flex h-40 items-end gap-2" role="img" aria-label="SLA breaches per day">
              {data.breachesByDay.map((day) => (
                <div key={day.day} className="flex flex-1 flex-col items-center gap-1">
                  <span className="text-xs text-slate-600">{day.breaches}</span>
                  {/* React's style prop sets a CSSOM property, which the CSP allows (no inline <style>). */}
                  <div className="w-full rounded-t bg-red-400" style={{ height: `${(day.breaches / maxBreaches) * 100}%` }} />
                  <span className="text-[10px] text-slate-500">{day.day.slice(5)}</span>
                </div>
              ))}
            </div>
          )}
        </Card>

        <Card title="Due next">
          <ul className="divide-y divide-slate-100 text-sm">
            {data.dueSoon.map((item) => {
              const seconds = Math.round((new Date(item.dueAt).getTime() - now) / 1000);
              return (
                <li key={`${item.ticketId}-${item.metric}`} className="flex items-center justify-between py-2">
                  <span>
                    <Link to={`/tickets/${item.ticketId}`} className="font-medium text-indigo-700 hover:underline">{item.ticketNumber}</Link>{" "}
                    {item.subject}
                    <span className={`ml-2 text-xs ${priorityStyle[item.priority]}`}>{item.priority.toLowerCase()}</span>
                    <div className="text-xs text-slate-500">{metricLabel[item.metric]} · {item.assignee ?? "unassigned"}</div>
                  </span>
                  <span className={seconds <= 0 ? "font-semibold text-red-700" : "text-slate-700"}>
                    {seconds <= 0 ? "overdue" : `in ${formatDuration(seconds)}`}
                  </span>
                </li>
              );
            })}
            {data.dueSoon.length === 0 && <li className="py-4 text-center text-slate-500">No running clocks.</li>}
          </ul>
        </Card>
      </div>
    </div>
  );
}

function percentColour(percent: number | null): string {
  if (percent == null) return "text-slate-400";
  if (percent >= 95) return "text-emerald-700";
  if (percent >= 80) return "text-amber-700";
  return "text-red-700";
}
