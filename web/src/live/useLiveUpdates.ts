import { Client } from "@stomp/stompjs";
import { useQueryClient } from "@tanstack/react-query";
import { useEffect, useState } from "react";

import { keys } from "../api/hooks";

export type LiveStatus = "connecting" | "live" | "offline";

/** What the server pushes on /topic/tickets: a hint that a ticket changed, not the ticket itself. */
type TicketChanged = { type: string; ticketId: number; ticketNumber: string; sentAt: string };

/** The WebSocket endpoint on this origin (nginx forwards it to an API instance). */
export function webSocketUrl(location: Pick<Location, "protocol" | "host"> = window.location): string {
  return `${location.protocol === "https:" ? "wss" : "ws"}://${location.host}/ws`;
}

/**
 * Keeps one STOMP-over-WebSocket connection open while the app is signed in.
 *
 * The browser's session cookie authenticates the WebSocket handshake, like any API call. The
 * server pushes small "ticket X changed" hints; we react by invalidating the affected queries,
 * so TanStack Query refetches them through the normal API (with its permission checks). If
 * the connection drops, the client reconnects, and on reconnect we refetch everything once,
 * in case we missed something while offline.
 */
export function useLiveUpdates(agentId: number): LiveStatus {
  const queryClient = useQueryClient();
  const [status, setStatus] = useState<LiveStatus>("connecting");

  useEffect(() => {
    let connectedBefore = false;
    const client = new Client({
      brokerURL: webSocketUrl(),
      reconnectDelay: 3_000,
      heartbeatIncoming: 20_000,
      heartbeatOutgoing: 20_000,
      onConnect: () => {
        setStatus("live");
        if (connectedBefore) {
          void queryClient.invalidateQueries();
        }
        connectedBefore = true;
        client.subscribe("/topic/tickets", (frame) => {
          const update = JSON.parse(frame.body) as TicketChanged;
          void queryClient.invalidateQueries({ queryKey: keys.ticket(update.ticketId) });
          void queryClient.invalidateQueries({ queryKey: [...keys.tickets, "list"] });
          void queryClient.invalidateQueries({ queryKey: keys.queues });
          void queryClient.invalidateQueries({ queryKey: keys.dashboard });
        });
        client.subscribe(`/topic/agent.${agentId}`, () => {
          void queryClient.invalidateQueries({ queryKey: keys.notifications });
        });
      },
      onWebSocketClose: () => setStatus("offline"),
      onStompError: () => setStatus("offline"),
    });
    client.activate();
    return () => {
      void client.deactivate();
    };
  }, [agentId, queryClient]);

  return status;
}
