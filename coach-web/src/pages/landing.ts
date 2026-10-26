import { topbar } from "../lib/ui";
import { icon } from "../lib/icons";
import { landingFooter } from "./landing-footer";
import { heroSession, mountHeroSession } from "./hero-session";
import { mountLandingMotion } from "./landing-motion";
import "../styles/landing.css";

let disposeMotion: (() => void) | null = null;
let disposeSession: (() => void) | null = null;

export function renderLanding(root: HTMLElement): void {
  document.title = "Volleyball Lab — AI-powered coaching";
  root.innerHTML = `<div class="landing-page" id="top">${topbar(false, "portal")}
  <main>
    <section class="hero" aria-labelledby="hero-title">
      <div class="hero-inner">
        <div class="hero-copy">
          <p class="eyebrow"><span class="hero-kicker-index">VL / 01</span> Your courtside advantage</p>
          <h1 id="hero-title"><span class="hero-line">See the form.</span><span class="hero-line">Understand the</span><span class="hero-line">movement.</span><em class="hero-line">Coach better.</em></h1>
          <p class="lead">Volleyball Lab helps coaches review player sessions with AI pose tracking, rep counts, and performance scores. See what they see, write feedback, and help every athlete improve.</p>
          <div class="hero-cta">
            <a class="btn btn-primary" href="#/register">Become a coach ${icon("arrow")}</a>
            <a class="btn btn-ghost" href="#how-it-works">${icon("play")} See how it works</a>
          </div>
          <div class="hero-points" aria-label="Portal features"><span>${icon("check")} AI pose analysis</span><span>${icon("check")} Session history</span><span>${icon("check")} Coach feedback</span></div>
          <p class="hero-side-note">Built for the work between match days.</p>
        </div>
        ${heroSession()}
      </div>
      <div class="performance-strip" aria-label="Train with purpose"><div class="wrap"><span class="performance-word">Train with purpose.</span><span class="performance-divider" aria-hidden="true">/</span><span class="performance-word">Review every detail.</span><span class="performance-divider" aria-hidden="true">/</span><span class="performance-word">Own the next point.</span></div></div>
      <div class="feature-ribbon wrap" id="features">
        <article class="feature-card"><div class="feature-icon">${icon("pose")}</div><div><span class="feature-index">01 / Technique</span><h2>Read the movement.</h2><p>Pose analysis highlights the details that make a difference in each rep.</p><a href="#about">Explore the analysis ${icon("arrow")}</a></div></article>
        <article class="feature-card"><div class="feature-icon">${icon("chart")}</div><div><span class="feature-index">02 / Progress</span><h2>See the whole session.</h2><p>Review rep counts, form scores, and training history in one place.</p><a href="#about">Explore session review ${icon("arrow")}</a></div></article>
        <article class="feature-card"><div class="feature-icon">${icon("message")}</div><div><span class="feature-index">03 / Coaching</span><h2>Give the next cue.</h2><p>Send focused feedback and set the next drill for your athletes.</p><a href="#about">Explore coach feedback ${icon("arrow")}</a></div></article>
      </div>
    </section>
    <section class="steps-section" id="how-it-works"><div class="wrap">
      <div class="section-heading"><div><p class="eyebrow">How it works</p><h2>Three steps to a connected team</h2></div><p>From practice to progress, Volleyball Lab makes it simple to coach, track, and support your athletes.</p></div>
      <div class="steps-flow">
        <article class="step-card"><div><b>01</b><span>${icon("team")}</span></div><h3>Register the team</h3><p>Create your coach account and welcome your athletes in minutes.</p></article><i class="step-arrow" aria-hidden="true">${icon("arrow")}</i>
        <article class="step-card"><div><b>02</b><span>${icon("play")}</span></div><h3>Review player sessions</h3><p>Athletes capture practice sessions on their phones. You see the data.</p></article><i class="step-arrow" aria-hidden="true">${icon("arrow")}</i>
        <article class="step-card"><div><b>03</b><span>${icon("chart")}</span></div><h3>Assign feedback and improve</h3><p>Write feedback, assign next drills, and track progress over time.</p></article>
      </div>
    </div></section>
    <section class="results-section" id="about"><div class="wrap results-grid">
      <div class="results-copy"><p class="eyebrow">Built for real coaches</p><h2>From movement to measurable feedback</h2><p>Our analysis turns every rep into clear insight on technique, consistency, and progress—so you can focus on helping your athletes grow.</p><a class="text-link" href="#/register">Start coaching with Volleyball Lab →</a></div>
      <div class="result-preview serving-preview">
        <figure class="serving-photo receiving-photo"><img src="/assets/volleyball-receiving-color-1200.jpg" srcset="/assets/volleyball-receiving-color-640.jpg 640w, /assets/volleyball-receiving-color-1200.jpg 1200w" sizes="(max-width: 900px) calc(100vw - 64px), 55vw" alt="Volleyball player in a low receiving stance on a warm wooden indoor court" width="1200" height="1200" loading="lazy" decoding="async" /><figcaption class="photo-caption"><span>02 / Receiving</span><span>Every rep has a story.</span></figcaption></figure>
        <div class="result-sheet"><div class="result-top"><span>Example session report</span>${icon("chart")}</div><div class="result-main"><div class="result-ring">89%</div><div><strong>Overall score</strong></div></div><div class="result-bars"><span>Platform <i><u style="width:87%"></u></i><b>87%</b></span><span>Knee bend <i><u style="width:92%"></u></i><b>92%</b></span><span>Body alignment <i><u style="width:90%"></u></i><b>90%</b></span></div><div class="result-note"><b>Example cue</b><span>Keep your platform steady and stay low through contact.</span></div></div>
      </div>
    </div></section>
    <section class="final-cta"><div class="wrap"><p class="eyebrow">Ready for the next point?</p><h2>Bring your team into focus.</h2><p>Create your coach account and turn every recorded session into a clear next step.</p><a class="btn btn-primary" href="#/register">Become a coach →</a></div></section>
  </main>${landingFooter()}</div>`;

  root.querySelectorAll<HTMLAnchorElement>('a[href^="#"]:not([href^="#/"])').forEach((link) => {
    link.addEventListener("click", (event) => {
      const target = document.querySelector(link.getAttribute("href") ?? "");
      if (!target) return;
      event.preventDefault();
      const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
      target.scrollIntoView({ behavior: reducedMotion ? "auto" : "smooth", block: "start" });
    });
  });

  disposeMotion = mountLandingMotion(root);
  disposeSession = mountHeroSession(root);
}

export function disposeLanding(): void {
  disposeSession?.();
  disposeSession = null;
  disposeMotion?.();
  disposeMotion = null;
}
