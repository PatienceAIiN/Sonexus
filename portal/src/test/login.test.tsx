import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it, vi, afterEach } from "vitest";
import App from "../App";
import { AuthProvider } from "../auth/auth";
import { getToken } from "../api/client";
import { callsTo, mockFetch } from "./helpers";

afterEach(() => vi.unstubAllGlobals());

describe("login", () => {
  it("POSTs credentials, stores the token, and navigates to the app", async () => {
    const fetchMock = mockFetch([
      {
        method: "POST",
        path: "/v1/auth/login",
        body: { access_token: "jwt-abc-123", token_type: "bearer" },
      },
      { method: "GET", path: "/v1/clips", body: [] },
    ]);

    render(
      <MemoryRouter initialEntries={["/login"]}>
        <AuthProvider>
          <App />
        </AuthProvider>
      </MemoryRouter>,
    );

    const user = userEvent.setup();
    await user.type(screen.getByLabelText(/email/i), "dev@sonex.app");
    await user.type(screen.getByLabelText(/password/i), "hunter22");
    await user.click(screen.getByRole("button", { name: /sign in/i }));

    // Landed on the Sound page (default post-login route)
    expect(await screen.findByRole("heading", { name: /^sound$/i })).toBeInTheDocument();

    // Correct endpoint + payload
    const loginCalls = callsTo(fetchMock, "POST", "/v1/auth/login");
    expect(loginCalls).toHaveLength(1);
    expect(JSON.parse(loginCalls[0][1]!.body as string)).toEqual({
      email: "dev@sonex.app",
      password: "hunter22",
    });

    // Token stored in memory + localStorage
    expect(getToken()).toBe("jwt-abc-123");
    expect(window.localStorage.getItem("sonex_token")).toBe("jwt-abc-123");

    // Subsequent API calls carry the Bearer header
    const clipCalls = callsTo(fetchMock, "GET", "/v1/clips");
    expect(clipCalls.length).toBeGreaterThan(0);
    const headers = new Headers(clipCalls[0][1]?.headers as HeadersInit);
    expect(headers.get("Authorization")).toBe("Bearer jwt-abc-123");
  });

  it("shows the server error detail on failure and stores no token", async () => {
    mockFetch([
      {
        method: "POST",
        path: "/v1/auth/login",
        status: 401,
        body: { detail: "Invalid credentials" },
      },
    ]);

    render(
      <MemoryRouter initialEntries={["/login"]}>
        <AuthProvider>
          <App />
        </AuthProvider>
      </MemoryRouter>,
    );

    const user = userEvent.setup();
    await user.type(screen.getByLabelText(/email/i), "dev@sonex.app");
    await user.type(screen.getByLabelText(/password/i), "wrong");
    await user.click(screen.getByRole("button", { name: /sign in/i }));

    expect(await screen.findByRole("alert")).toHaveTextContent(/invalid credentials/i);
    expect(getToken()).toBeNull();
    expect(window.localStorage.getItem("sonex_token")).toBeNull();
  });
});
