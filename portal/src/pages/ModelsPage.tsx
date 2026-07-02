import { useCallback, useEffect, useState } from "react";
import { listModels, promoteModel, rollbackModel, type ModelInfo } from "../api/client";

export default function ModelsPage() {
  const [models, setModels] = useState<ModelInfo[]>([]);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  const refresh = useCallback(async () => {
    const list = await listModels();
    setModels(list);
  }, []);

  useEffect(() => {
    let cancelled = false;
    refresh()
      .catch((e: unknown) => {
        if (!cancelled) setError(e instanceof Error ? e.message : "Failed to load models");
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [refresh]);

  async function act(model: ModelInfo, action: "promote" | "rollback") {
    setBusyId(model.id);
    setError(null);
    setNotice(null);
    try {
      if (action === "promote") await promoteModel(model.id);
      else await rollbackModel(model.id);
      await refresh();
      setNotice(`Model #${model.id} (v${model.version}) ${action === "promote" ? "promoted" : "rolled back"}`);
    } catch (e) {
      setError(e instanceof Error ? e.message : `${action} failed`);
    } finally {
      setBusyId(null);
    }
  }

  return (
    <div>
      <h1>Model versions</h1>
      <p className="subtitle">Manifest entries — promote to serve OTA, rollback to retire</p>

      {error && <p className="error" role="alert">{error}</p>}
      {notice && <p className="success" role="status">{notice}</p>}
      {loading && <p className="muted">Loading models…</p>}
      {!loading && models.length === 0 && !error && <p className="muted">No models registered.</p>}

      {models.length > 0 && (
        <div className="card">
          <table className="data">
            <thead>
              <tr>
                <th>ID</th>
                <th>Kind / file</th>
                <th>Version</th>
                <th>sha256</th>
                <th>Min app</th>
                <th>Status</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {models.map((m) => (
                <tr key={m.id} data-testid={`model-${m.id}`}>
                  <td className="mono">{m.id}</td>
                  <td>
                    {m.kind ?? "—"}
                    {m.file && <div className="muted mono">{m.file}</div>}
                  </td>
                  <td>{m.version}</td>
                  <td className="mono" title={m.sha256}>
                    {m.sha256 ? `${m.sha256.slice(0, 12)}…` : "—"}
                  </td>
                  <td>{m.min_app_version}</td>
                  <td>
                    <span className={`badge ${m.status ?? "neutral"}`} data-testid={`status-${m.id}`}>
                      {m.status ?? "unknown"}
                    </span>
                  </td>
                  <td>
                    <div className="row" style={{ gap: 8, justifyContent: "flex-end" }}>
                      <button
                        className="btn-sm btn-primary"
                        disabled={busyId === m.id || m.status === "active"}
                        onClick={() => void act(m, "promote")}
                      >
                        Promote
                      </button>
                      <button
                        className="btn-sm btn-danger"
                        disabled={busyId === m.id}
                        onClick={() => void act(m, "rollback")}
                      >
                        Rollback
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
