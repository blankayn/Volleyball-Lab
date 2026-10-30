/* Hash router — zero-config on static hosting, no rewrite rules needed. */

export type Route =
  | { name: "landing" }
  | { name: "login" }
  | { name: "register" }
  | { name: "app"; tab: string; playerUid: string | null; sessionId: string | null };

export function parseRoute(hash: string): Route {
  const parts = hash.replace(/^#\/?/, "").split("/").filter(Boolean);
  if (parts[0] === "login") return { name: "login" };
  if (parts[0] === "register") return { name: "register" };
  if (parts[0] === "app") {
    // Older question links open the conversations now included in Player roster.
    const tab = parts[1] === "questions" ? "roster" : parts[1] ?? "overview";
    const playerUid = parts[2] ?? null;
    const sessionId = parts[3] ?? null;
    return { name: "app", tab, playerUid, sessionId };
  }
  return { name: "landing" };
}

export function go(path: string): void {
  window.location.hash = path.startsWith("/") ? `#${path}` : `#/${path}`;
}

export function appPath(tab = "overview", playerUid: string | null = null, sessionId: string | null = null): string {
  return playerUid ? `#/app/${tab}/${playerUid}${sessionId ? `/${sessionId}` : ""}` : `#/app/${tab}`;
}
