/* Hash router — zero-config on static hosting, no rewrite rules needed. */

export type Route =
  | { name: "landing" }
  | { name: "login" }
  | { name: "register" }
  | { name: "app"; tab: string; playerUid: string | null };

export function parseRoute(hash: string): Route {
  const parts = hash.replace(/^#\/?/, "").split("/").filter(Boolean);
  if (parts[0] === "login") return { name: "login" };
  if (parts[0] === "register") return { name: "register" };
  if (parts[0] === "app") {
    const tab = parts[1] ?? "overview";
    const playerUid = parts[2] ?? null;
    return { name: "app", tab, playerUid };
  }
  return { name: "landing" };
}

export function go(path: string): void {
  window.location.hash = path.startsWith("/") ? `#${path}` : `#/${path}`;
}

export function appPath(tab = "overview", playerUid: string | null = null): string {
  return playerUid ? `#/app/${tab}/${playerUid}` : `#/app/${tab}`;
}
