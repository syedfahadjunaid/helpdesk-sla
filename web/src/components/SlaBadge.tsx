import type { SlaTimer } from "../api/types";
import { healthStyle, metricLabel, remainingSecondsNow, slaLabel } from "../lib/format";
import { Badge } from "./ui";

/**
 * One SLA clock as a coloured badge that counts down live: green on track, amber at risk,
 * red breached, grey paused, blue met.
 *
 * @param fetchedAt when the timer data was fetched (TanStack Query's dataUpdatedAt)
 */
export function SlaBadge({ timer, fetchedAt, now, showMetric = true }: {
  timer: SlaTimer;
  fetchedAt: number;
  now: number;
  showMetric?: boolean;
}) {
  const remaining = remainingSecondsNow(timer, fetchedAt, now);
  const health = timer.health === "ON_TRACK" && remaining <= 0 ? "BREACHED" : timer.health;
  const title = timer.dueAt ? `Due ${new Date(timer.dueAt).toLocaleString()}` : undefined;
  return (
    <span title={title}>
      <Badge className={healthStyle[health]}>
        {showMetric && <span className="mr-1 opacity-70">{metricLabel[timer.metric]}:</span>}
        {slaLabel(timer, remaining)}
      </Badge>
    </span>
  );
}
