import type { TrainingSession } from "./cloud";
import { esc, fullDate } from "./format";

/* Small charts for the coach dashboard. One accent hue carries magnitude, numbers stay
   in ink, and every value drawn here is also written as text nearby. */

/** Scored sessions, oldest first. */
export function scoredSessions(sessions: TrainingSession[]): (TrainingSession & { formScore: number })[] {
  return sessions.filter((s): s is TrainingSession & { formScore: number } => s.formScore !== null)
    .sort((a, b) => a.startedAt - b.startedAt);
}

/** A compact trend of the last scores; the reader gets the same facts from its label. */
export function sparkline(sessions: TrainingSession[]): string {
  const scores = scoredSessions(sessions).slice(-8).map((s) => s.formScore);
  if (scores.length < 2) return `<span class="spark-empty">${scores.length ? "1 scored set" : "No scores yet"}</span>`;
  const width = 112, height = 34, pad = 5;
  const low = Math.min(...scores), span = Math.max(8, Math.max(...scores) - low);
  const points = scores.map((score, index) => [
    pad + (index / (scores.length - 1)) * (width - pad * 2),
    height - pad - ((score - low) / span) * (height - pad * 2),
  ] as const);
  const last = points[points.length - 1]!;
  return `<svg class="spark" viewBox="0 0 ${width} ${height}" role="img" aria-label="Form over the last ${scores.length} scored sets, from ${scores[0]} to ${scores[scores.length - 1]}">
    <polyline points="${points.map(([x, y]) => `${x.toFixed(1)},${y.toFixed(1)}`).join(" ")}"/><circle cx="${last[0].toFixed(1)}" cy="${last[1].toFixed(1)}" r="4"/></svg>`;
}

/** Form across a player's scored sessions. Hover a point for its skill and date. */
export function trendChart(sessions: TrainingSession[], skillLabel: (skill: string) => string): string {
  const scored = scoredSessions(sessions).slice(-12);
  if (scored.length < 2) return `<p class="chart-note">Two scored sessions are needed to show a trend.</p>`;
  const width = 640, height = 210, left = 34, right = 20, top = 22, bottom = 30;
  const values = scored.map((s) => s.formScore);
  const low = Math.max(0, Math.floor((Math.min(...values) - 5) / 10) * 10);
  const high = Math.min(100, Math.max(low + 20, Math.ceil((Math.max(...values) + 5) / 10) * 10));
  const x = (index: number) => left + (index / (scored.length - 1)) * (width - left - right);
  const y = (value: number) => top + (1 - (value - low) / (high - low)) * (height - top - bottom);
  const line = scored.map((s, index) => `${x(index).toFixed(1)},${y(s.formScore).toFixed(1)}`);
  const ticks = [low, Math.round((low + high) / 2), high];
  const day = (at: number) => new Date(at).toLocaleDateString(undefined, { month: "short", day: "numeric" });
  return `<svg class="trend" viewBox="0 0 ${width} ${height}" role="img" aria-label="Form score across ${scored.length} scored sessions, from ${values[0]} to ${values[values.length - 1]}">
    ${ticks.map((tick) => `<line class="trend-grid" x1="${left}" x2="${width - right}" y1="${y(tick)}" y2="${y(tick)}"/><text x="${left - 8}" y="${y(tick) + 4}" text-anchor="end">${tick}</text>`).join("")}
    <text x="${left}" y="${height - 8}">${esc(day(scored[0]!.startedAt))}</text><text x="${width - right}" y="${height - 8}" text-anchor="end">${esc(day(scored[scored.length - 1]!.startedAt))}</text>
    <polygon class="trend-area" points="${left},${y(low)} ${line.join(" ")} ${width - right},${y(low)}"/>
    <polyline class="trend-line" points="${line.join(" ")}"/>
    ${scored.map((s, index) => `<g class="trend-point"><circle class="trend-hit" cx="${x(index)}" cy="${y(s.formScore)}" r="16"><title>${esc(skillLabel(s.skill))} · form ${s.formScore} · ${esc(fullDate(s.startedAt))}</title></circle><circle class="trend-dot" cx="${x(index)}" cy="${y(s.formScore)}" r="5"/></g>`).join("")}
    <text class="trend-value" x="${x(scored.length - 1)}" y="${y(values[values.length - 1]!) - 13}" text-anchor="end">${values[values.length - 1]}</text>
  </svg>`;
}

/** Sessions per day for the last seven days. */
export function activityChart(sessions: TrainingSession[]): string {
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  const days = Array.from({ length: 7 }, (_, index) => {
    const day = new Date(today);
    day.setDate(today.getDate() - (6 - index));
    const next = new Date(day);
    next.setDate(day.getDate() + 1);
    return {
      label: day.toLocaleDateString(undefined, { weekday: "short" }),
      count: sessions.filter((s) => s.startedAt >= day.getTime() && s.startedAt < next.getTime()).length,
      today: index === 6,
    };
  });
  const max = Math.max(1, ...days.map((d) => d.count));
  return `<div class="week-chart" role="img" aria-label="${esc(days.map((d) => `${d.label} ${d.count}`).join(", "))} sets">${days.map((d) =>
    `<div class="week-day${d.today ? " is-today" : ""}" title="${d.label}: ${d.count} set${d.count === 1 ? "" : "s"}"><span class="week-count">${d.count || ""}</span><span class="week-track"><i data-grow="y" style="height:${d.count ? Math.round((d.count / max) * 100) : 0}%"></i></span><small>${d.today ? "Today" : esc(d.label)}</small></div>`).join("")}</div>`;
}

/** Labelled bars for mean form by skill, with the weakest one named in words. */
export function skillBars(skills: { label: string; mean: number | null }[]): string {
  const scored = skills.filter((s) => s.mean !== null);
  const lowest = scored.length > 1 ? Math.min(...scored.map((s) => s.mean ?? 0)) : null;
  const flagged = scored.find((s) => s.mean === lowest);
  return `<div class="skill-bars">${skills.map((s) => `<div class="skill-bar${s.mean === null ? " is-empty" : ""}"><span>${esc(s.label)}${s === flagged ? `<em>Lowest</em>` : ""}</span><span class="meter" aria-hidden="true"><i data-grow="x" style="width:${s.mean ?? 0}%"></i></span><b>${s.mean ?? "—"}</b></div>`).join("")}</div>`;
}

/** A score in a ring. `size` is one of the CSS sizes. */
export function scoreRing(score: number | null, size: "sm" | "lg", label: string): string {
  const value = Math.max(0, Math.min(100, score ?? 0));
  return `<span class="score-ring-${size}" role="img" aria-label="${esc(label)}: ${score === null ? "not scored" : `${score} out of 100`}">
    <svg viewBox="0 0 100 100" aria-hidden="true" focusable="false"><circle class="ring-track" cx="50" cy="50" r="43"/>${score === null ? "" : `<circle class="ring-value${size === "lg" ? " hero-form-arc" : ""}" cx="50" cy="50" r="43" pathLength="100" stroke-dasharray="${value} 100" data-score="${value}"/>`}</svg>
    <strong>${score ?? "—"}</strong></span>`;
}
