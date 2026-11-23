import tailwindcss from "@tailwindcss/vite";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    // Must match the redirect URI registered for the "helpdesk-web" client in Keycloak.
    // Stop the web container first (docker compose stop web): it holds the same port.
    port: 5580,
    strictPort: true,
    // Same origin in development too: everything dynamic goes to API instance 1 (published
    // on 127.0.0.1:8580 by Docker Compose). In the Docker image, nginx does this (nginx.conf).
    proxy: {
      "/api": "http://localhost:8580",
      "/oauth2": "http://localhost:8580",
      "/login/oauth2": "http://localhost:8580",
      "/logout": "http://localhost:8580",
      "/ws": { target: "ws://localhost:8580", ws: true },
    },
  },
  test: {
    environment: "jsdom",
    setupFiles: ["./src/test/setup.ts"],
  },
});
