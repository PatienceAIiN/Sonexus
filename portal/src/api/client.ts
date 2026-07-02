// SoNex portal API client. All paths follow docs/api-contract.md (binding).

const TOKEN_KEY = "sonex_token";

let tokenInMemory: string | null = null;

export function getToken(): string | null {
  if (tokenInMemory) return tokenInMemory;
  try {
    tokenInMemory = window.localStorage.getItem(TOKEN_KEY);
  } catch {
    /* storage unavailable */
  }
  return tokenInMemory;
}

export function setToken(token: string | null): void {
  tokenInMemory = token;
  try {
    if (token === null) window.localStorage.removeItem(TOKEN_KEY);
    else window.localStorage.setItem(TOKEN_KEY, token);
  } catch {
    /* storage unavailable */
  }
}

export function baseUrl(): string {
  const url = (import.meta.env?.VITE_API_URL as string | undefined) ?? "";
  return url.replace(/\/+$/, "");
}

export class ApiError extends Error {
  status: number;
  constructor(status: number, detail: string) {
    super(detail);
    this.status = status;
    this.name = "ApiError";
  }
}

async function request(path: string, init: RequestInit = {}): Promise<Response> {
  const headers = new Headers(init.headers);
  const token = getToken();
  if (token) headers.set("Authorization", `Bearer ${token}`);
  const res = await fetch(`${baseUrl()}${path}`, { ...init, headers });
  if (!res.ok) {
    let detail = `HTTP ${res.status}`;
    try {
      const body = await res.json();
      if (typeof body?.detail === "string") detail = body.detail;
    } catch {
      /* non-JSON error body */
    }
    if (res.status === 401) setToken(null);
    throw new ApiError(res.status, detail);
  }
  return res;
}

async function requestJson<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  if (init.body !== undefined) headers.set("Content-Type", "application/json");
  const res = await request(path, { ...init, headers });
  return (await res.json()) as T;
}

// ---- Types (per API contract; unspecified fields kept optional) ----

export interface LoginResponse {
  access_token: string;
  token_type: string;
}

export interface Clip {
  id: number;
  ts: string;
  duration_ms: number;
  label?: string | null;
  room_state?: string | null;
  home_id?: number;
  storage_key?: string;
  backend?: string;
}

export interface ModelInfo {
  id: number;
  kind?: string;
  file?: string;
  version: string;
  sha256: string;
  min_app_version: number;
  status: string;
  home_id?: number | null;
  created_at?: string;
}

export interface MetricPoint {
  ts: string;
  home_id?: number;
  model_version?: string;
  accuracy?: number;
  precision?: number;
  recall?: number;
  false_ducks?: number;
  overrides?: number;
  [k: string]: unknown;
}

export interface DeviceInfo {
  id?: number;
  device_id?: number | string;
  device_name?: string;
  home_id?: number;
  home_name?: string;
  state?: string | null;
  last_seen?: string | null;
}

function asArray<T>(data: unknown, keys: string[]): T[] {
  if (Array.isArray(data)) return data as T[];
  if (data && typeof data === "object") {
    for (const k of keys) {
      const v = (data as Record<string, unknown>)[k];
      if (Array.isArray(v)) return v as T[];
    }
  }
  return [];
}

// ---- Endpoints ----

export async function login(email: string, password: string): Promise<LoginResponse> {
  const out = await requestJson<LoginResponse>("/v1/auth/login", {
    method: "POST",
    body: JSON.stringify({ email, password }),
  });
  setToken(out.access_token);
  return out;
}

export function logout(): void {
  setToken(null);
}

export async function listClips(homeId?: number | ""): Promise<Clip[]> {
  const qs = homeId !== undefined && homeId !== "" ? `?home_id=${homeId}` : "";
  const data = await requestJson<unknown>(`/v1/clips${qs}`);
  return asArray<Clip>(data, ["clips", "items"]);
}

export async function fetchClipAudio(clipId: number): Promise<ArrayBuffer> {
  const res = await request(`/v1/clips/${clipId}/audio`);
  return res.arrayBuffer();
}

export async function submitLabel(body: {
  clip_id?: number;
  event_id?: number;
  label: string;
  correct: boolean;
}): Promise<unknown> {
  return requestJson<unknown>("/v1/labels", {
    method: "POST",
    body: JSON.stringify(body),
  });
}

export async function listMetrics(homeId?: number | "", days = 30): Promise<MetricPoint[]> {
  const params = new URLSearchParams();
  if (homeId !== undefined && homeId !== "") params.set("home_id", String(homeId));
  params.set("days", String(days));
  const data = await requestJson<unknown>(`/v1/metrics?${params.toString()}`);
  return asArray<MetricPoint>(data, ["metrics", "items"]);
}

export async function listModels(): Promise<ModelInfo[]> {
  const data = await requestJson<unknown>("/v1/models");
  return asArray<ModelInfo>(data, ["models", "items"]);
}

export async function promoteModel(id: number): Promise<unknown> {
  return requestJson<unknown>(`/v1/models/${id}/promote`, { method: "POST" });
}

export async function rollbackModel(id: number): Promise<unknown> {
  return requestJson<unknown>(`/v1/models/${id}/rollback`, { method: "POST" });
}

export async function listDevices(): Promise<DeviceInfo[]> {
  const data = await requestJson<unknown>("/v1/devices");
  return asArray<DeviceInfo>(data, ["devices", "items"]);
}
