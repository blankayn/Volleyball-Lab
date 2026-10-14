/* Small shared view helpers: shell, cards, empty states, score bars. */

import { esc, initials, scoreTone } from "./format";

export function topbar(signedIn: boolean, active: string): string {
  const links = signedIn
    ? `<a class="btn btn-ghost btn-sm" href="#/app">Open dashboard</a>`
    : `<a class="btn btn-ghost btn-sm" href="#/login">Sign in</a>
       <a class="btn btn-primary btn-sm" href="#/register">Become a coach</a>`;
  return `<header class="topbar"><div class="topbar-inner">
    <a class="brand" href="#/"><img class="brand-mark" src="/assets/volleyball-lab-logo.png" alt="Volleyball Lab logo" /><span>Volleyball Lab <small>Coach${active === "portal" ? " portal" : ""}</small></span></a>
    ${!signedIn ? `<nav class="site-nav" aria-label="Primary"><a href="#features">Features</a><a href="#how-it-works">How it works</a><a href="#about">About</a></nav>` : ""}
    <nav>${links}</nav>
  </div></header>`;
}

export function footer(): string {
  return `<footer class="footer"><div class="wrap">
    <span><strong>Volleyball Lab</strong> — coach portal, connected to the player app.</span>
    <nav><a href="#/">Home</a><a href="#/login">Sign in</a><a href="#/register">Register</a></nav>
  </div></footer>`;
}

export function iconChip(icon: string, tone: "rust" | "indigo" | "green" | "violet"): string {
  return `<span class="icon-chip icon-${tone}">${icon}</span>`;
}

export function emptyState(icon: string, title: string, body: string, action = ""): string {
  return `<div class="empty">${iconChip(icon, "indigo")}
    <p style="font-weight:800;color:var(--ink);margin:0 0 6px">${esc(title)}</p>
    <p style="margin:0 0 4px">${esc(body)}</p>${action}</div>`;
}

export function scoreBar(score: number | null): string {
  if (score === null) return `<span class="muted">—</span>`;
  const tone = scoreTone(score);
  const color =
    tone === "good" ? "var(--good)" : tone === "warn" ? "var(--warn)" : "var(--bad)";
  return `<span style="display:flex;align-items:center;gap:10px;min-width:150px">
    <span class="scorebar" style="flex:1"><i style="width:${score}%;background:${color}"></i></span>
    <span class="score-num" style="color:${color}">${score}</span></span>`;
}

export function avatar(name: string): string {
  return `<span class="avatar">${esc(initials(name))}</span>`;
}

export function spinner(label: string): string {
  return `<div class="card" role="status"><p class="muted" style="margin:0">${esc(label)}…</p></div>`;
}

export function errorCard(message: string, retry: string): string {
  return `<div class="form-error" role="alert">${esc(message)}
    <div style="margin-top:10px"><button class="btn btn-ghost btn-sm" data-retry="${retry}">Try again</button></div></div>`;
}
