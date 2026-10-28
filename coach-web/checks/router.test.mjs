import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { transform } from "esbuild";
const source = await readFile(new URL("../src/lib/router.ts", import.meta.url), "utf8");
const { code } = await transform(source, { loader: "ts", format: "esm" });
const { parseRoute, appPath } = await import(`data:text/javascript;base64,${Buffer.from(code).toString("base64")}`);

test("review links retain the exact player and saved session", () => {
  assert.deepEqual(parseRoute(appPath("player", "player-a", "session-b")), {
    name: "app", tab: "player", playerUid: "player-a", sessionId: "session-b",
  });
});
test("existing dashboard and player bookmarks remain valid", () => {
  assert.deepEqual(parseRoute("#/app/roster"), { name: "app", tab: "roster", playerUid: null, sessionId: null });
  assert.deepEqual(parseRoute("#/app/player/player-a"), { name: "app", tab: "player", playerUid: "player-a", sessionId: null });
  assert.deepEqual(parseRoute("#/login"), { name: "login" });
});
