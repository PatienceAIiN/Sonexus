import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi, afterEach } from "vitest";
import TrainingPage from "../pages/TrainingPage";
import { callsTo, mockFetch } from "./helpers";

const CLIPS = [
  { id: 1, ts: "2026-07-01T10:00:00Z", duration_ms: 3000, label: "speech", room_state: "TALKING" },
  { id: 2, ts: "2026-07-01T11:00:00Z", duration_ms: 2000, label: null, room_state: "BOOST" },
];

afterEach(() => vi.unstubAllGlobals());

describe("training review", () => {
  it("confirm submits the label to POST /v1/labels and shows confirmation", async () => {
    const fetchMock = mockFetch([
      { method: "GET", path: "/v1/clips", body: CLIPS },
      { method: "POST", path: "/v1/labels", status: 201, body: { id: 99 } },
    ]);

    render(<TrainingPage />);
    const sample = within(await screen.findByTestId("sample-1"));
    await userEvent.click(sample.getByRole("button", { name: /confirm/i }));

    const labelCalls = callsTo(fetchMock, "POST", "/v1/labels");
    expect(labelCalls).toHaveLength(1);
    expect(JSON.parse(labelCalls[0][1]!.body as string)).toEqual({
      clip_id: 1,
      label: "speech",
      correct: true,
    });
    expect(await screen.findByRole("status")).toHaveTextContent(/confirmed label "speech" for clip #1/i);
  });

  it("correct submits the chosen label and updates the UI", async () => {
    const fetchMock = mockFetch([
      { method: "GET", path: "/v1/clips", body: CLIPS },
      { method: "POST", path: "/v1/labels", status: 201, body: { id: 100 } },
    ]);

    render(<TrainingPage />);
    await screen.findByTestId("sample-2");
    expect(screen.getByTestId("label-2")).toHaveTextContent("unlabelled");

    await userEvent.selectOptions(screen.getByLabelText(/correct label for clip 2/i), "noise");
    const sample = within(screen.getByTestId("sample-2"));
    await userEvent.click(sample.getByRole("button", { name: /^correct$/i }));

    const labelCalls = callsTo(fetchMock, "POST", "/v1/labels");
    expect(labelCalls).toHaveLength(1);
    expect(JSON.parse(labelCalls[0][1]!.body as string)).toEqual({
      clip_id: 2,
      label: "noise",
      correct: false,
    });

    // UI reflects the corrected label
    expect(await screen.findByRole("status")).toHaveTextContent(/corrected clip #2 to "noise"/i);
    expect(screen.getByTestId("label-2")).toHaveTextContent("noise");
  });

  it("shows the API error when the label submit fails", async () => {
    mockFetch([
      { method: "GET", path: "/v1/clips", body: CLIPS },
      { method: "POST", path: "/v1/labels", status: 422, body: { detail: "label required" } },
    ]);

    render(<TrainingPage />);
    const sample = within(await screen.findByTestId("sample-1"));
    await userEvent.click(sample.getByRole("button", { name: /confirm/i }));

    expect(await screen.findByRole("alert")).toHaveTextContent(/label required/i);
  });
});
