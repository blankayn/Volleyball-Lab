import { createHash } from "node:crypto";

const escapeHtml = (value) => String(value).replace(/[&<>"']/g, char => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[char]));
const short = (value, length = 120) => String(value ?? "").replace(/[\r\n]+/g, " ").slice(0, length);

export function submissionEmailCategory(settings, profile) {
  if (settings?.emailNotifications !== true) return null;
  if (settings.submissionAlerts !== false && profile?.notifySubmissions !== false) return "submission";
  return settings.trainingNotifications !== false ? "training" : null;
}

export function deadlineIsDue(assignment, now) {
  return assignment?.status !== "Completed" && Number.isFinite(assignment?.dueAt)
    && assignment.dueAt > now && assignment.dueAt <= now + 86_400_000;
}

export function emailDeliveryId(uid, eventKey) {
  return createHash("sha256").update(`${uid}\n${eventKey}`).digest("hex");
}

export function emailMessage({ category, playerName, assignmentTitle, dueAt, portalUrl }) {
  const url = new URL(portalUrl);
  if (url.protocol !== "https:" || url.username || url.password) throw new Error("invalid_portal_url");
  url.hash = category === "deadline" ? "/app/assignments" : "/app/queue";
  const title = category === "deadline" ? "An assignment is due soon" : category === "training" ? "New training shared with you" : "A player session is ready to review";
  const description = category === "deadline"
    ? `${short(assignmentTitle)} for ${short(playerName, 60)} is due ${new Date(dueAt).toISOString().replace("T", " at ").slice(0, 22)} UTC.`
    : `${short(playerName, 60)} shared a training session with you. Open your coaching workspace to see the session and leave feedback.`;
  const account = new URL(portalUrl); account.hash = "/app/account";
  return {
    subject: `Volleyball Lab · ${title}`,
    text: `${title}\n\n${description}\n\nOpen your dashboard: ${url.href}\n\nManage notification preferences: ${account.href}`,
    html: `<div style="font-family:Arial,sans-serif;max-width:560px;margin:auto;padding:32px;color:#26313b"><p style="font-size:12px;letter-spacing:2px;color:#a44723">VOLLEYBALL LAB</p><h1 style="font-size:25px;line-height:1.3">${escapeHtml(title)}</h1><p style="line-height:1.7">${escapeHtml(description)}</p><p style="margin:28px 0"><a href="${escapeHtml(url.href)}" style="background:#bd4318;color:white;padding:13px 20px;text-decoration:none;border-radius:6px">Open dashboard</a></p><p style="font-size:12px"><a href="${escapeHtml(account.href)}" style="color:#596673">Notification preferences</a></p></div>`,
  };
}

export async function sendResend({ apiKey, from, to, id, message, fetchImpl = fetch }) {
  if (!apiKey || !from || /[\r\n]/.test(from) || !to) throw new Error("coach_email_not_configured");
  const response = await fetchImpl("https://api.resend.com/emails", {
    method: "POST", signal: AbortSignal.timeout(15_000),
    headers: { Authorization: `Bearer ${apiKey}`, "Content-Type": "application/json", "Idempotency-Key": `coach-alert/${id}` },
    body: JSON.stringify({ from, to: [to], ...message }),
  });
  if (!response.ok) throw new Error(`resend_delivery_failed_${response.status}`);
  const result = await response.json();
  if (typeof result.id !== "string") throw new Error("resend_invalid_response");
  return result.id;
}
