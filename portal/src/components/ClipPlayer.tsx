import { useEffect, useRef, useState } from "react";
import { fetchClipAudio } from "../api/client";

/**
 * Fetches clip audio (with the Bearer header via the api client), decodes it
 * with Web Audio, renders a hand-rolled canvas waveform of the PCM min/max
 * peaks, and plays it through an <audio> element via an object URL.
 */
export default function ClipPlayer({ clipId }: { clipId: number }) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const audioRef = useRef<HTMLAudioElement>(null);
  const [objectUrl, setObjectUrl] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    let url: string | null = null;
    setLoading(true);
    setError(null);

    (async () => {
      const bytes = await fetchClipAudio(clipId);
      if (cancelled) return;
      url = URL.createObjectURL(new Blob([bytes]));
      setObjectUrl(url);

      // Decode a copy for the waveform (decodeAudioData detaches the buffer).
      try {
        const Ctx =
          window.AudioContext ??
          (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
        if (!Ctx) return;
        const ctx = new Ctx();
        const decoded = await ctx.decodeAudioData(bytes.slice(0));
        if (!cancelled) drawWaveform(canvasRef.current, decoded.getChannelData(0));
        void ctx.close();
      } catch {
        // Non-decodable format: keep playback, skip waveform.
      }
    })()
      .catch((e: unknown) => {
        if (!cancelled) setError(e instanceof Error ? e.message : "Failed to load audio");
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });

    return () => {
      cancelled = true;
      if (url) URL.revokeObjectURL(url);
    };
  }, [clipId]);

  if (error) return <p className="error">{error}</p>;

  return (
    <div>
      <canvas ref={canvasRef} className="waveform-canvas" width={800} height={96} />
      {loading && <p className="muted">Loading audio…</p>}
      {objectUrl && (
        <audio
          ref={audioRef}
          controls
          src={objectUrl}
          style={{ width: "100%", marginTop: 10 }}
          data-testid={`clip-audio-${clipId}`}
        />
      )}
    </div>
  );
}

/** Draw min/max peak columns of the PCM onto the canvas. */
export function drawWaveform(canvas: HTMLCanvasElement | null, pcm: Float32Array): void {
  if (!canvas) return;
  const ctx = canvas.getContext("2d");
  if (!ctx) return;
  const { width, height } = canvas;
  ctx.clearRect(0, 0, width, height);

  const mid = height / 2;
  const samplesPerCol = Math.max(1, Math.floor(pcm.length / width));

  // centre line
  ctx.strokeStyle = "rgba(45, 212, 191, 0.25)";
  ctx.beginPath();
  ctx.moveTo(0, mid);
  ctx.lineTo(width, mid);
  ctx.stroke();

  ctx.fillStyle = "#7c4dff";
  for (let x = 0; x < width; x++) {
    const start = x * samplesPerCol;
    if (start >= pcm.length) break;
    let min = 1.0;
    let max = -1.0;
    const end = Math.min(start + samplesPerCol, pcm.length);
    for (let i = start; i < end; i++) {
      const v = pcm[i];
      if (v < min) min = v;
      if (v > max) max = v;
    }
    const y1 = mid - max * (mid - 2);
    const y2 = mid - min * (mid - 2);
    ctx.fillRect(x, y1, 1, Math.max(1, y2 - y1));
  }
}
