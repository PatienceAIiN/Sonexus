import { useEffect, useMemo, useState } from "react";
import { listMetrics, type MetricPoint } from "../api/client";
import LineChart from "../components/LineChart";

const PALETTE = ["#7c4dff", "#2dd4bf", "#f59e0b", "#ff5c7a", "#60a5fa", "#a3e635"];

function groupKey(p: MetricPoint): string {
  const home = p.home_id != null ? `home ${p.home_id}` : "all homes";
  const ver = p.model_version ? ` · v${p.model_version}` : "";
  return `${home}${ver}`;
}

export default function MetricsPage() {
  const [metrics, setMetrics] = useState<MetricPoint[]>([]);
  const [homeId, setHomeId] = useState<string>("");
  const [days, setDays] = useState(30);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    listMetrics(homeId === "" ? "" : Number(homeId), days)
      .then((m) => {
        if (!cancelled) setMetrics(m);
      })
      .catch((e: unknown) => {
        if (!cancelled) setError(e instanceof Error ? e.message : "Failed to load metrics");
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [homeId, days]);

  const accuracySeries = useMemo(() => {
    const groups = new Map<string, { x: number; y: number }[]>();
    for (const p of metrics) {
      if (typeof p.accuracy !== "number") continue;
      const k = groupKey(p);
      if (!groups.has(k)) groups.set(k, []);
      groups.get(k)!.push({ x: new Date(p.ts).getTime(), y: p.accuracy });
    }
    return [...groups.entries()].map(([name, points], i) => ({
      name,
      color: PALETTE[i % PALETTE.length],
      points,
    }));
  }, [metrics]);

  const overrideSeries = useMemo(() => {
    const groups = new Map<string, { x: number; y: number }[]>();
    for (const p of metrics) {
      if (typeof p.overrides !== "number") continue;
      const k = groupKey(p);
      if (!groups.has(k)) groups.set(k, []);
      groups.get(k)!.push({ x: new Date(p.ts).getTime(), y: p.overrides });
    }
    return [...groups.entries()].map(([name, points], i) => ({
      name,
      color: PALETTE[i % PALETTE.length],
      points,
    }));
  }, [metrics]);

  return (
    <div>
      <h1>Accuracy</h1>
      <p className="subtitle">Detection accuracy over time, per home and model version</p>

      <div className="card row">
        <label className="row" style={{ gap: 8 }}>
          <span className="muted">Home ID</span>
          <input
            value={homeId}
            onChange={(e) => setHomeId(e.target.value.replace(/\D/g, ""))}
            placeholder="all"
            style={{ width: 90 }}
          />
        </label>
        <label className="row" style={{ gap: 8 }}>
          <span className="muted">Window</span>
          <select value={days} onChange={(e) => setDays(Number(e.target.value))}>
            <option value={7}>7 days</option>
            <option value={30}>30 days</option>
            <option value={90}>90 days</option>
          </select>
        </label>
      </div>

      {error && <p className="error" role="alert">{error}</p>}
      {loading && <p className="muted">Loading metrics…</p>}

      {!loading && !error && (
        <>
          <div className="card">
            <strong>Accuracy</strong>
            <LineChart series={accuracySeries} yLabel="accuracy" yMax={1} />
          </div>
          {overrideSeries.length > 0 && (
            <div className="card">
              <strong>User overrides / day</strong>
              <LineChart series={overrideSeries} yLabel="overrides" />
            </div>
          )}
        </>
      )}
    </div>
  );
}
