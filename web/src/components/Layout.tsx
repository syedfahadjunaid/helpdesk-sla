import { NavLink, Outlet } from "react-router";

import type { Me } from "../api/types";
import { signOut } from "../auth/session";
import type { LiveStatus } from "../live/useLiveUpdates";
import { NotificationBell } from "./NotificationBell";

const liveLook: Record<LiveStatus, { dot: string; text: string }> = {
  live: { dot: "bg-emerald-400", text: "Live" },
  connecting: { dot: "bg-amber-300", text: "Connecting…" },
  offline: { dot: "bg-red-400", text: "Reconnecting…" },
};

export function Layout({ me, live }: { me: Me; live: LiveStatus }) {
  const link = ({ isActive }: { isActive: boolean }) =>
    `rounded-md px-3 py-1.5 text-sm font-medium ${isActive ? "bg-indigo-800 text-white" : "text-indigo-100 hover:bg-indigo-500"}`;
  return (
    <div className="min-h-screen">
      <header className="bg-indigo-600 text-white">
        <div className="mx-auto flex max-w-7xl items-center gap-6 px-4 py-2">
          <span className="text-base font-semibold">Helpdesk</span>
          <nav className="flex gap-1">
            <NavLink to="/" end className={link}>Tickets</NavLink>
            <NavLink to="/tickets/new" className={link}>New ticket</NavLink>
            <NavLink to="/dashboard" className={link}>SLA dashboard</NavLink>
            <NavLink to="/policies" className={link}>SLA policies</NavLink>
          </nav>
          <div className="ml-auto flex items-center gap-4 text-sm">
            <span className="flex items-center gap-1.5 text-indigo-100" title="Live updates over WebSocket">
              <span className={`h-2 w-2 rounded-full ${liveLook[live].dot}`} />
              {liveLook[live].text}
            </span>
            <NotificationBell />
            <span className="text-indigo-100">
              {me.displayName} <span className="text-indigo-300">({me.roles.join(", ").toLowerCase()})</span>
            </span>
            <button type="button" className="rounded-md px-2 py-1 hover:bg-indigo-500" onClick={() => void signOut()}>
              Sign out
            </button>
          </div>
        </div>
      </header>
      <main className="mx-auto max-w-7xl px-4 py-6">
        <Outlet />
      </main>
    </div>
  );
}
