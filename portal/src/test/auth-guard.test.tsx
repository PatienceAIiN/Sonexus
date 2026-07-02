import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it, vi, afterEach } from "vitest";
import App from "../App";
import { AuthProvider } from "../auth/auth";
import { setToken } from "../api/client";
import { mockFetch } from "./helpers";

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AuthProvider>
        <App />
      </AuthProvider>
    </MemoryRouter>,
  );
}

afterEach(() => vi.unstubAllGlobals());

describe("auth guard", () => {
  it("redirects unauthenticated users to /login", () => {
    mockFetch([]);
    renderAt("/models");
    // Login page is shown instead of the protected page
    expect(screen.getByText(/sign in to the dev portal/i)).toBeInTheDocument();
    expect(screen.queryByText(/model versions/i)).not.toBeInTheDocument();
  });

  it("redirects from the root route too", () => {
    mockFetch([]);
    renderAt("/");
    expect(screen.getByText(/sign in to the dev portal/i)).toBeInTheDocument();
  });

  it("lets authenticated users through", async () => {
    setToken("test-jwt");
    mockFetch([{ method: "GET", path: "/v1/models", body: [] }]);
    renderAt("/models");
    expect(
      await screen.findByRole("heading", { name: /model versions/i }),
    ).toBeInTheDocument();
    expect(screen.queryByText(/sign in to the dev portal/i)).not.toBeInTheDocument();
  });
});
