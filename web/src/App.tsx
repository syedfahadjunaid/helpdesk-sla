import { Route, Routes } from "react-router";

import type { Me } from "./api/types";
import { Layout } from "./components/Layout";
import { useLiveUpdates } from "./live/useLiveUpdates";
import { DashboardPage } from "./pages/DashboardPage";
import { NewTicketPage } from "./pages/NewTicketPage";
import { NotFoundPage } from "./pages/NotFoundPage";
import { PoliciesPage } from "./pages/PoliciesPage";
import { TicketPage } from "./pages/TicketPage";
import { TicketsPage } from "./pages/TicketsPage";

/** The signed-in app. Rendered only once /api/me succeeded (see AuthGate). */
export function App({ me }: { me: Me }) {
  const live = useLiveUpdates(me.id);
  return (
    <Routes>
      <Route element={<Layout me={me} live={live} />}>
        <Route index element={<TicketsPage me={me} />} />
        <Route path="tickets/new" element={<NewTicketPage />} />
        <Route path="tickets/:id" element={<TicketPage me={me} />} />
        <Route path="dashboard" element={<DashboardPage />} />
        <Route path="policies" element={<PoliciesPage me={me} />} />
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  );
}
