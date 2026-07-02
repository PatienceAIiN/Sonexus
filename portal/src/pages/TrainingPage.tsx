import { useEffect, useState } from "react";
import { listClips, submitLabel, type Clip } from "../api/client";
import ClipPlayer from "../components/ClipPlayer";

const LABELS = ["speech", "noise", "quiet", "music", "appliance"];

export default function TrainingPage() {
  const [clips, setClips] = useState<Clip[]>([]);
  const [playing, setPlaying] = useState<number | null>(null);
  const [pendingLabel, setPendingLabel] = useState<Record<number, string>>({});
  const [busyId, setBusyId] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    listClips()
      .then((c) => {
        if (!cancelled) setClips(c);
      })
      .catch((e: unknown) => {
        if (!cancelled) setError(e instanceof Error ? e.message : "Failed to load samples");
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  async function confirm(clip: Clip) {
    await sendLabel(clip, clip.label ?? "speech", true);
  }

  async function correct(clip: Clip) {
    const label = pendingLabel[clip.id] ?? LABELS[0];
    await sendLabel(clip, label, false);
  }

  async function sendLabel(clip: Clip, label: string, isConfirm: boolean) {
    setBusyId(clip.id);
    setError(null);
    setNotice(null);
    try {
      await submitLabel({ clip_id: clip.id, label, correct: isConfirm });
      setClips((prev) => prev.map((c) => (c.id === clip.id ? { ...c, label } : c)));
      setNotice(
        isConfirm
          ? `Confirmed label "${label}" for clip #${clip.id}`
          : `Corrected clip #${clip.id} to "${label}"`,
      );
    } catch (e) {
      setError(e instanceof Error ? e.message : "Label submit failed");
    } finally {
      setBusyId(null);
    }
  }

  return (
    <div>
      <h1>Training review</h1>
      <p className="subtitle">Listen to samples and confirm or correct their labels</p>

      {error && <p className="error" role="alert">{error}</p>}
      {notice && <p className="success" role="status">{notice}</p>}
      {loading && <p className="muted">Loading samples…</p>}
      {!loading && clips.length === 0 && !error && <p className="muted">No samples to review.</p>}

      {clips.map((clip) => (
        <div className="card" key={clip.id} data-testid={`sample-${clip.id}`}>
          <div className="row" style={{ justifyContent: "space-between" }}>
            <div>
              <strong>Clip #{clip.id}</strong>{" "}
              <span className="muted">
                {clip.ts} · {(clip.duration_ms / 1000).toFixed(1)}s ·{" "}
              </span>
              <span data-testid={`label-${clip.id}`}>
                {clip.label ?? "unlabelled"}
              </span>
            </div>
            <div className="row">
              <button
                className="btn-sm btn-ghost"
                onClick={() => setPlaying(playing === clip.id ? null : clip.id)}
              >
                {playing === clip.id ? "Hide player" : "Play"}
              </button>
              <button
                className="btn-sm btn-teal"
                disabled={busyId === clip.id}
                onClick={() => void confirm(clip)}
              >
                Confirm
              </button>
              <select
                aria-label={`Correct label for clip ${clip.id}`}
                value={pendingLabel[clip.id] ?? LABELS[0]}
                onChange={(e) =>
                  setPendingLabel((p) => ({ ...p, [clip.id]: e.target.value }))
                }
              >
                {LABELS.map((l) => (
                  <option key={l} value={l}>
                    {l}
                  </option>
                ))}
              </select>
              <button
                className="btn-sm btn-primary"
                disabled={busyId === clip.id}
                onClick={() => void correct(clip)}
              >
                Correct
              </button>
            </div>
          </div>
          {playing === clip.id && (
            <div style={{ marginTop: 14 }}>
              <ClipPlayer clipId={clip.id} />
            </div>
          )}
        </div>
      ))}
    </div>
  );
}
