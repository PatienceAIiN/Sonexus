interface Series {
  name: string;
  color: string;
  points: { x: number; y: number }[]; // x = ms epoch
}

const W = 800;
const H = 260;
const PAD = { top: 16, right: 16, bottom: 28, left: 44 };

/** Hand-rolled SVG multi-series line chart (no chart deps). */
export default function LineChart({
  series,
  yLabel,
  yMax,
}: {
  series: Series[];
  yLabel?: string;
  yMax?: number;
}) {
  const all = series.flatMap((s) => s.points);
  if (all.length === 0) return <p className="muted">No data.</p>;

  const xs = all.map((p) => p.x);
  const ys = all.map((p) => p.y);
  const xMin = Math.min(...xs);
  const xMax = Math.max(...xs);
  const yMin = 0;
  const yTop = yMax ?? (Math.max(...ys) * 1.1 || 1);

  const sx = (x: number) =>
    xMax === xMin
      ? (PAD.left + W - PAD.right) / 2
      : PAD.left + ((x - xMin) / (xMax - xMin)) * (W - PAD.left - PAD.right);
  const sy = (y: number) =>
    H - PAD.bottom - ((y - yMin) / (yTop - yMin)) * (H - PAD.top - PAD.bottom);

  const yTicks = [0, 0.25, 0.5, 0.75, 1].map((f) => yMin + f * (yTop - yMin));
  const xTickCount = Math.min(6, new Set(xs).size);
  const xTicks = Array.from({ length: xTickCount }, (_, i) =>
    xMin + (i / Math.max(1, xTickCount - 1)) * (xMax - xMin),
  );

  return (
    <div>
      <svg viewBox={`0 0 ${W} ${H}`} className="chart-svg" role="img" aria-label={yLabel ?? "chart"}>
        {yTicks.map((t) => (
          <g key={t}>
            <line
              x1={PAD.left}
              x2={W - PAD.right}
              y1={sy(t)}
              y2={sy(t)}
              stroke="#2b2450"
              strokeWidth={1}
            />
            <text x={PAD.left - 8} y={sy(t) + 4} textAnchor="end" fontSize={11} fill="#9b94b8">
              {t >= 1 ? t.toFixed(t >= 10 ? 0 : 1) : t.toFixed(2)}
            </text>
          </g>
        ))}
        {xTicks.map((t) => (
          <text
            key={t}
            x={sx(t)}
            y={H - 8}
            textAnchor="middle"
            fontSize={11}
            fill="#9b94b8"
          >
            {new Date(t).toLocaleDateString(undefined, { month: "short", day: "numeric" })}
          </text>
        ))}
        {series.map((s) => {
          const sorted = [...s.points].sort((a, b) => a.x - b.x);
          const d = sorted
            .map((p, i) => `${i === 0 ? "M" : "L"}${sx(p.x).toFixed(1)},${sy(p.y).toFixed(1)}`)
            .join(" ");
          return (
            <g key={s.name}>
              <path d={d} fill="none" stroke={s.color} strokeWidth={2} />
              {sorted.map((p) => (
                <circle key={p.x} cx={sx(p.x)} cy={sy(p.y)} r={2.5} fill={s.color} />
              ))}
            </g>
          );
        })}
      </svg>
      <div className="legend">
        {series.map((s) => (
          <span key={s.name}>
            <span className="swatch" style={{ background: s.color }} />
            {s.name}
          </span>
        ))}
      </div>
    </div>
  );
}
