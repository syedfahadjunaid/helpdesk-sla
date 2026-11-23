import { useState } from "react";
import { Link } from "react-router";

import { useMarkNotificationsRead, useNotifications } from "../api/hooks";
import { formatDateTime } from "../lib/format";

/** The bell in the header: unread count, and a dropdown of the latest notifications. */
export function NotificationBell() {
  const notifications = useNotifications();
  const markRead = useMarkNotificationsRead();
  const [open, setOpen] = useState(false);
  const unread = notifications.data?.unread ?? 0;

  return (
    <div className="relative">
      <button
        type="button"
        aria-label={`Notifications, ${unread} unread`}
        className="relative rounded-md p-1.5 text-slate-200 hover:bg-indigo-500 hover:text-white"
        onClick={() => setOpen((value) => !value)}
      >
        <svg viewBox="0 0 24 24" className="h-5 w-5" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
          <path d="M6 8a6 6 0 1 1 12 0c0 7 3 9 3 9H3s3-2 3-9M10.3 21a1.94 1.94 0 0 0 3.4 0" />
        </svg>
        {unread > 0 && (
          <span className="absolute -right-1 -top-1 rounded-full bg-red-500 px-1.5 text-[10px] font-bold text-white">
            {unread}
          </span>
        )}
      </button>
      {open && (
        <div className="absolute right-0 z-20 mt-2 w-96 rounded-lg bg-white text-slate-800 shadow-lg ring-1 ring-slate-200">
          <div className="flex items-center justify-between border-b border-slate-100 px-4 py-2">
            <span className="text-sm font-semibold">Notifications</span>
            <button
              type="button"
              className="text-xs text-indigo-600 hover:underline disabled:text-slate-400"
              disabled={unread === 0 || markRead.isPending}
              onClick={() => markRead.mutate(undefined)}
            >
              Mark all read
            </button>
          </div>
          <ul className="max-h-96 overflow-y-auto">
            {(notifications.data?.items ?? []).map((item) => (
              <li key={item.id} className={`border-b border-slate-50 px-4 py-2 text-sm ${item.readAt ? "" : "bg-indigo-50/60"}`}>
                {item.ticketId ? (
                  <Link
                    to={`/tickets/${item.ticketId}`}
                    className="hover:underline"
                    onClick={() => {
                      setOpen(false);
                      if (!item.readAt) markRead.mutate(item.id);
                    }}
                  >
                    {item.message}
                  </Link>
                ) : (
                  item.message
                )}
                <div className="text-xs text-slate-500">{formatDateTime(item.createdAt)}</div>
              </li>
            ))}
            {notifications.data?.items.length === 0 && (
              <li className="px-4 py-6 text-center text-sm text-slate-500">Nothing yet.</li>
            )}
          </ul>
        </div>
      )}
    </div>
  );
}
