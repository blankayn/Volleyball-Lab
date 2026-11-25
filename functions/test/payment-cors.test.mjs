import test from "node:test";
import assert from "node:assert/strict";
import worker from "../../payment-worker/src/worker.mjs";

const url = "https://payments.example/createGcashCheckout";
const ask = (method, origin, path = url) => worker.fetch(new Request(path, { method, headers: origin ? { Origin: origin } : {} }), {});

test("the coach website may open checkout and other sites may not read it", async () => {
  const preflight = await ask("OPTIONS", "https://volleyball-lab.pages.dev");
  assert.equal(preflight.status, 204);
  assert.equal(preflight.headers.get("access-control-allow-origin"), "https://volleyball-lab.pages.dev");
  assert.match(preflight.headers.get("access-control-allow-headers"), /Authorization/);
  const answer = await ask("POST", "https://volleyball-lab.pages.dev");
  assert.equal(answer.status, 503);
  assert.equal(answer.headers.get("access-control-allow-origin"), "https://volleyball-lab.pages.dev");
  assert.equal((await ask("POST", "https://evil.example")).headers.get("access-control-allow-origin"), null);
  assert.equal((await ask("POST", null)).status, 503);
});
test("the webhook is never opened to websites", async () => {
  const response = await ask("OPTIONS", "https://volleyball-lab.pages.dev", "https://payments.example/xenditWebhook");
  assert.equal(response.status, 405);
  assert.equal(response.headers.get("access-control-allow-origin"), null);
});
