import test from "node:test";
import assert from "node:assert/strict";
import { sendGmailCode } from "../src/gmail.mjs";
import { firebaseResetAction } from "../src/firebase.mjs";

test("Gmail submission uses TLS, the Volleyball Lab sender name and a six-digit code", async () => {
  const commands = [];
  let controller;
  let closed = false;
  const encoder = new TextEncoder();
  const replies = [250, 334, 334, 235, 250, 250, 354, 250];
  const connect = (address, options) => {
    assert.deepEqual(address, { hostname: "smtp.gmail.com", port: 465 });
    assert.equal(options.secureTransport, "on");
    return {
      opened: Promise.resolve({}),
      closed: Promise.resolve(),
      readable: new ReadableStream({ start(value) { controller = value; controller.enqueue(encoder.encode("220 Gmail ready\r\n")); } }),
      writable: new WritableStream({
        write(bytes) {
          const value = new TextDecoder().decode(bytes);
          commands.push(value);
          if (value !== "QUIT\r\n") controller.enqueue(encoder.encode(replies.shift() + " OK\r\n"));
        },
      }),
      async close() { if (!closed) { closed = true; controller.close(); } },
    };
  };
  await sendGmailCode({
    env: { GMAIL_SENDER: "volleyballlab1@gmail.com", GMAIL_APP_PASSWORD: "abcdefghijklmnop" },
    email: "athlete@example.com", code: "012345",
  }, connect);
  assert.equal(commands[0], "EHLO volleyball-lab-auth.workers.dev\r\n");
  assert.equal(commands[1], "AUTH LOGIN\r\n");
  assert.equal(commands[2], Buffer.from("volleyballlab1@gmail.com").toString("base64") + "\r\n");
  assert.equal(commands[3], Buffer.from("abcdefghijklmnop").toString("base64") + "\r\n");
  assert.match(commands[7], /From: Volleyball Lab <volleyballlab1@gmail.com>/);
  assert.match(commands[7], /\r\n012345\r\n/);
  assert.equal(commands[7].includes("abcdefghijklmnop"), false);
  assert.equal(closed, true);
});

test("Gmail adapter rejects header injection and missing app passwords before opening a socket", async () => {
  let connected = false;
  const connect = () => { connected = true; throw Error(); };
  for (const input of [
    { env: { GMAIL_SENDER: "volleyballlab1@gmail.com" }, email: "athlete@example.com", code: "123456" },
    { env: { GMAIL_SENDER: "volleyballlab1@gmail.com", GMAIL_APP_PASSWORD: "abcdefghijklmnop" }, email: "athlete@example.com\r\nBcc: other@example.com", code: "123456" },
  ]) await assert.rejects(sendGmailCode(input, connect));
  assert.equal(connected, false);
});

test("restricted Firebase credential generates an action without sending a Firebase email", async () => {
  const pair = await crypto.subtle.generateKey({
    name: "RSASSA-PKCS1-v1_5", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256",
  }, true, ["sign", "verify"]);
  const key = Buffer.from(await crypto.subtle.exportKey("pkcs8", pair.privateKey)).toString("base64");
  const env = { FIREBASE_AUTH_SERVICE_ACCOUNT: JSON.stringify({
    project_id: "volleyball-lab", client_email: "recovery@volleyball-lab.iam.gserviceaccount.com",
    private_key_id: "test-key", private_key: "-----BEGIN PRIVATE KEY-----\n" + key + "\n-----END PRIVATE KEY-----",
  }) };
  const fetchImpl = async (url, options) => {
    if (url === "https://oauth2.googleapis.com/token") {
      const parts = options.body.get("assertion").split(".");
      const claims = JSON.parse(Buffer.from(parts[1], "base64url"));
      assert.equal(claims.scope, "https://www.googleapis.com/auth/identitytoolkit");
      assert.equal(await crypto.subtle.verify("RSASSA-PKCS1-v1_5", pair.publicKey,
        Buffer.from(parts[2], "base64url"), Buffer.from(parts[0] + "." + parts[1])), true);
      return Response.json({ access_token: "test-token", expires_in: 300 });
    }
    assert.equal(url, "https://identitytoolkit.googleapis.com/v1/projects/volleyball-lab/accounts:sendOobCode");
    assert.equal(options.headers.Authorization, "Bearer test-token");
    assert.deepEqual(JSON.parse(options.body), { requestType: "PASSWORD_RESET", email: "athlete@example.com", returnOobLink: true });
    return Response.json({ oobLink: "https://volleyball-lab.firebaseapp.com/__/auth/action?mode=resetPassword&oobCode=test-code" });
  };
  assert.equal(await firebaseResetAction({ env, email: "athlete@example.com" }, fetchImpl), "test-code");
});
