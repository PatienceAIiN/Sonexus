import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi, afterEach } from "vitest";
import ModelsPage from "../pages/ModelsPage";
import { callsTo, mockFetch, type Route } from "./helpers";

function modelFixtures(status1: string, status2: string) {
  return [
    { id: 1, kind: "home", file: "home_v1.onnx", version: "1.0", sha256: "a".repeat(64), min_app_version: 1, status: status1 },
    { id: 2, kind: "home", file: "home_v2.onnx", version: "2.0", sha256: "b".repeat(64), min_app_version: 2, status: status2 },
  ];
}

afterEach(() => vi.unstubAllGlobals());

describe("model versions", () => {
  it("promote hits POST /v1/models/{id}/promote and refreshes status", async () => {
    let promoted = false;
    const routes: Route[] = [
      {
        method: "GET",
        path: "/v1/models",
        handler: () => ({ body: promoted ? modelFixtures("rolled_back", "active") : modelFixtures("active", "candidate") }),
      },
      {
        method: "POST",
        path: "/v1/models/2/promote",
        handler: () => {
          promoted = true;
          return { body: { ok: true } };
        },
      },
    ];
    const fetchMock = mockFetch(routes);

    render(<ModelsPage />);
    const row2 = within(await screen.findByTestId("model-2"));
    expect(screen.getByTestId("status-2")).toHaveTextContent("candidate");

    await userEvent.click(row2.getByRole("button", { name: /promote/i }));

    expect(callsTo(fetchMock, "POST", "/v1/models/2/promote")).toHaveLength(1);
    expect(callsTo(fetchMock, "POST", "/v1/models/2/rollback")).toHaveLength(0);
    expect(await screen.findByRole("status")).toHaveTextContent(/promoted/i);
    expect(screen.getByTestId("status-2")).toHaveTextContent("active");
  });

  it("rollback hits POST /v1/models/{id}/rollback", async () => {
    let rolled = false;
    const fetchMock = mockFetch([
      {
        method: "GET",
        path: "/v1/models",
        handler: () => ({ body: rolled ? modelFixtures("rolled_back", "active") : modelFixtures("active", "candidate") }),
      },
      {
        method: "POST",
        path: "/v1/models/1/rollback",
        handler: () => {
          rolled = true;
          return { body: { ok: true } };
        },
      },
    ]);

    render(<ModelsPage />);
    const row1 = within(await screen.findByTestId("model-1"));
    await userEvent.click(row1.getByRole("button", { name: /rollback/i }));

    expect(callsTo(fetchMock, "POST", "/v1/models/1/rollback")).toHaveLength(1);
    expect(callsTo(fetchMock, "POST", "/v1/models/1/promote")).toHaveLength(0);
    expect(await screen.findByRole("status")).toHaveTextContent(/rolled back/i);
    expect(screen.getByTestId("status-1")).toHaveTextContent("rolled_back");
  });

  it("renders manifest fields: version, sha256, min_app_version, status", async () => {
    mockFetch([
      { method: "GET", path: "/v1/models", body: modelFixtures("active", "candidate") },
    ]);
    render(<ModelsPage />);
    const row1 = within(await screen.findByTestId("model-1"));
    expect(row1.getByText("1.0")).toBeInTheDocument();
    expect(row1.getByText(/^aaaaaaaaaaaa…$/)).toBeInTheDocument();
    expect(row1.getByText("active")).toBeInTheDocument();
    expect(row1.getByText("home_v1.onnx")).toBeInTheDocument();
  });
});
