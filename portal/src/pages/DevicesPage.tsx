import { useEffect, useState } from "react";
import { listDevices, type DeviceInfo } from "../api/client";

function lastSeenText(iso: string | null | undefined): string {
  if (!iso) return "never";
  const t = new Date(iso).getTime();
  if (Number.isNaN(t)) return iso;
  const mins = Math.round((Date.now() - t) / 60000);
  if (mins < 1) return "just now";
  if (mins < 60) return `${mins}m ago`;
  const hours = Math.round(mins / 60);
  if (hours < 24) return `${hours}h ago`;
  return `${Math.round(hours / 24)}d ago`;
}

function isOnline(iso: string | null | undefined): boolean {
  if (!iso) return false;
  const t = new Date(iso).getTime();
  return !Number.isNaN(t) && Date.now() - t < 5 * 60 * 1000;
}

export default function DevicesPage() {
  const [devices, setDevices] = useState<DeviceInfo[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    listDevices()
      .then((d) => {
        if (!cancelled) setDevices(d);
      })
      .catch((e: unknown) => {
        if (!cancelled) setError(e instanceof Error ? e.message : "Failed to load devices");
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  return (
    <div>
      <h1>Devices</h1>
      <p className="subtitle">Registered devices, live room state and last-seen</p>

      {error && <p className="error" role="alert">{error}</p>}
      {loading && <p className="muted">Loading devices…</p>}
      {!loading && devices.length === 0 && !error && <p className="muted">No devices registered.</p>}

      {devices.length > 0 && (
        <div className="card">
          <table className="data">
            <thead>
              <tr>
                <th>ID</th>
                <th>Name</th>
                <th>Home</th>
                <th>State</th>
                <th>Last seen</th>
                <th>Status</th>
              </tr>
            </thead>
            <tbody>
              {devices.map((d, i) => {
                const id = d.id ?? d.device_id ?? i;
                return (
                  <tr key={String(id)}>
                    <td className="mono">{String(id)}</td>
                    <td>{d.device_name ?? "—"}</td>
                    <td>{d.home_name ?? (d.home_id != null ? `home ${d.home_id}` : "—")}</td>
                    <td>
                      <span className={`orb ${d.state ?? "unknown"}`} />
                      {d.state ?? "unknown"}
                    </td>
                    <td>{lastSeenText(d.last_seen)}</td>
                    <td>
                      <span className={`badge ${isOnline(d.last_seen) ? "online" : "offline"}`}>
                        {isOnline(d.last_seen) ? "online" : "offline"}
                      </span>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
