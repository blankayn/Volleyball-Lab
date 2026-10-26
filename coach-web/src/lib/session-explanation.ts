import { getFunctions, httpsCallable } from "firebase/functions";
import { esc } from "./format";

interface ExplanationCue { id: string; label: string; measuredReps: number; score: number | null; measured: number | null; unit: string; target: number[]; tip: string; }
interface ExplanationPoint { cueId: string; explanation: string; }
export interface SessionExplanation {
  status: "ready" | "empty" | "insufficient";
  evidence: { repCount: number; limitedCues: string[]; cues: ExplanationCue[] };
  explanation?: { overview: string; strengths: ExplanationPoint[]; priorities: ExplanationPoint[] };
  generatedAt?: number;
}

export async function getSessionExplanation(sessionId: string, generate: boolean): Promise<SessionExplanation> {
  const call = httpsCallable<{ sessionId: string; generate: boolean }, SessionExplanation>(getFunctions(undefined, "asia-southeast1"), "explainTrainingSession", { timeout: 65_000 });
  return (await call({ sessionId, generate })).data;
}

export function sessionExplanationPanel(sessionId: string): string {
  return `<section class="session-explanation" data-explanation-panel="${esc(sessionId)}" aria-label="AI session explanation"><div class="explanation-heading"><span class="eyebrow">Session insight</span><span class="explanation-badge">AI explanation</span></div><div data-explanation-content><p>Turn the saved measurements into a clearer explanation and a next practice focus.</p></div><div class="explanation-actions"><button class="btn btn-ghost btn-sm" type="button" data-explanation-generate="${esc(sessionId)}">Generate explanation</button><span class="explanation-consent">Sends anonymous session measurements to NVIDIA.</span></div><p class="explanation-status" data-explanation-status role="status" aria-live="polite"></p></section>`;
}

function explanationHtml(result: SessionExplanation): string {
  if (result.status === "insufficient") return result.evidence.cues.some(cue => cue.measuredReps >= 3)
    ? `<p>The measured cues sit between the strength and priority thresholds. Review the recorded scores above for this session.</p>`
    : `<p>Record at least three measurable reps of a cue before generating an explanation. The session measurements remain available above.</p>`;
  if (!result.explanation) return "";
  const section = (title: string, points: ExplanationPoint[], practice = false) => points.length ? `<div class="explanation-section"><h4>${title}</h4>${points.map(point => {
    const cue = result.evidence.cues.find(item => item.id === point.cueId);
    if (!cue) return "";
    return `<article><strong>${esc(cue.label)}</strong><p>${esc(point.explanation)}</p><small>${cue.measuredReps} measured reps${cue.score !== null ? ` · Score ${cue.score}/100` : ""}</small>${practice ? `<p class="explanation-practice"><b>Try next:</b> ${esc(cue.tip)}</p>` : ""}</article>`;
  }).join("")}</div>` : "";
  return `<p class="explanation-overview">${esc(result.explanation.overview)}</p><div class="explanation-sections">${section("What went well", result.explanation.strengths)}${section("Focus for the next set", result.explanation.priorities, true)}</div>${result.evidence.limitedCues.length ? '<p class="explanation-coverage">Cues with fewer than three measured reps were kept out of the findings.</p>' : ""}`;
}

export function wireSessionExplanations(root: HTMLElement): () => void {
  let disposed = false;
  root.querySelectorAll<HTMLElement>("[data-explanation-panel]").forEach(panel => {
    const sessionId = panel.dataset.explanationPanel!;
    const button = panel.querySelector<HTMLButtonElement>("[data-explanation-generate]")!;
    const content = panel.querySelector<HTMLElement>("[data-explanation-content]")!;
    const status = panel.querySelector<HTMLElement>("[data-explanation-status]")!;
    let busy = false;
    const load = async (generate: boolean) => {
      if (busy) return;
      busy = true;
      button.disabled = true;
      if (generate) { status.textContent = "Preparing your explanation…"; status.classList.remove("is-error"); }
      try {
        const result = await getSessionExplanation(sessionId, generate);
        if (disposed || !panel.isConnected) return;
        if (result.status !== "empty") {
          content.innerHTML = explanationHtml(result);
          button.hidden = true;
          panel.querySelector<HTMLElement>(".explanation-consent")!.hidden = true;
          status.textContent = result.status === "ready" ? "Based on the saved session measurements" : "";
        }
      } catch (error) {
        if (disposed || !panel.isConnected || !generate) return;
        const code = error && typeof error === "object" && "code" in error ? String(error.code) : "";
        status.textContent = code.includes("resource-exhausted") ? "Please wait before trying again, or try tomorrow if your daily limit was reached."
          : code.includes("permission-denied") ? "Your access to this session has changed."
          : "AI explanation is unavailable right now. Your session measurements and coach notes still work.";
        status.classList.add("is-error");
      } finally { busy = false; if (!disposed && panel.isConnected) button.disabled = false; }
    };
    button.addEventListener("click", () => void load(true));
    void load(false);
  });
  return () => { disposed = true; };
}
