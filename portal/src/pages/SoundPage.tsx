import { useEffect, useState } from "react";
import { listClips, type Clip } from "../api/client";
import ClipPlayer from "../components/ClipPlayer";

export default function SoundPage() {
  const [clips, setClips] = useState<Clip[]>([]);
  const [homeId, setHomeId] = useState<string>("");
  const [selected, setSelected] = useState<Clip | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    listClips(homeId === "" ? "" : Number(homeId))
      .then((c) => {
        if (!cancelled) setClips(c);
      })
      .catch((e: unknown) => {
        if (!cancelled) setError(e instanceof Error ? e.message : "Failed to load clips");
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [homeId]);

  return (
    <div>
      <h1>Sound</h1>
      <p className="subtitle">Uploaded clips — waveform and playback</p>

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
      </div>

      {error && <p className="error" role="alert">{error}</p>}
      {loading && <p className="muted">Loading clips…</p>}
      {!loading && !error && clips.length === 0 && <p className="muted">No clips yet.</p>}

      {clips.length > 0 && (
        <div className="card">
          <table className="data">
            <thead>
              <tr>
                <th>ID</th>
                <th>Timestamp</th>
                <th>Duration</th>
                <th>Room state</th>
                <th>Label</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {clips.map((c) => (
                <tr key={c.id}>
                  <td className="mono">{c.id}</td>
                  <td>{c.ts}</td>
                  <td>{(c.duration_ms / 1000).toFixed(1)}s</td>
                  <td>
                    <span className={`orb ${c.room_state ?? "unknown"}`} />
                    {c.room_state ?? "—"}
                  </td>
                  <td>{c.label ?? <span className="muted">unlabelled</span>}</td>
                  <td>
                    <button
                      className="btn-sm btn-teal"
                      onClick={() => setSelected(selected?.id === c.id ? null : c)}
                    >
                      {selected?.id === c.id ? "Close" : "Open"}
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {selected && (
        <div className="card">
          <div className="row" style={{ justifyContent: "space-between", marginBottom: 12 }}>
            <strong>Clip #{selected.id}</strong>
            <span className="muted">{selected.ts}</span>
          </div>
          <ClipPlayer clipId={selected.id} />
        </div>
      )}
    </div>
  );
}
