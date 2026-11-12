import { Buffer } from "node:buffer";
import { randomUUID } from "node:crypto";

/** Submit only the fixed recovery message through Gmail's TLS SMTP endpoint. */
export async function sendGmailCode({ env, email, code }, connect) {
  const sender = env.GMAIL_SENDER;
  const password = env.GMAIL_APP_PASSWORD?.replace(/\s/g, "");
  if (!/^[a-z0-9._%+-]+@gmail\.com$/i.test(sender ?? "") || !/^[a-z0-9]{16}$/i.test(password ?? "") ||
      !/^\d{6}$/.test(code) || /[\r\n<>]/.test(email)) throw Error("mail_not_configured");
  const socket = connect({ hostname: "smtp.gmail.com", port: 465 }, { secureTransport: "on" });
  socket.closed.catch(() => {});
  const timeout = setTimeout(() => { socket.close().catch(() => {}); }, 15_000);
  let reader, writer;
  try {
    await socket.opened;
    reader = socket.readable.getReader();
    writer = socket.writable.getWriter();
    const encoder = new TextEncoder();
    const decoder = new TextDecoder();
    let buffered = "";
    async function reply(expected) {
      let received = 0;
      while (true) {
        const end = buffered.indexOf("\r\n");
        if (end < 0) {
          const { value, done } = await reader.read();
          if (done) throw Error("mail_disconnected");
          received += value.length;
          if (received > 16_384) throw Error("mail_reply_too_large");
          buffered += decoder.decode(value, { stream: true });
          continue;
        }
        const line = buffered.slice(0, end);
        buffered = buffered.slice(end + 2);
        const match = /^(\d{3})([ -])/.exec(line);
        if (!match || Number(match[1]) !== expected) throw Error("mail_rejected");
        if (match[2] === " ") return;
      }
    }
    async function command(value, expected) {
      await writer.write(encoder.encode(value + "\r\n"));
      await reply(expected);
    }
    await reply(220);
    await command("EHLO volleyball-lab-auth.workers.dev", 250);
    await command("AUTH LOGIN", 334);
    await command(Buffer.from(sender).toString("base64"), 334);
    await command(Buffer.from(password).toString("base64"), 235);
    await command("MAIL FROM:<" + sender + ">", 250);
    await command("RCPT TO:<" + email + ">", 250);
    await command("DATA", 354);
    const message = [
      "From: Volleyball Lab <" + sender + ">",
      "To: <" + email + ">",
      "Subject: Your Volleyball Lab reset code",
      "Date: " + new Date().toUTCString(),
      "Message-ID: <" + randomUUID() + "@gmail.com>",
      "MIME-Version: 1.0",
      'Content-Type: text/plain; charset="UTF-8"',
      "Content-Transfer-Encoding: 8bit",
      "",
      "Your Volleyball Lab password reset code is:",
      "",
      code,
      "",
      "Enter this code in the Volleyball Lab app. It expires in 10 minutes.",
      "If you did not request a password reset, you can ignore this email.",
    ].join("\r\n");
    await command(message.replace(/(^|\r\n)\./g, "$1..") + "\r\n.", 250);
    // Acknowledged DATA is success, even if the server closes during QUIT.
    try { await writer.write(encoder.encode("QUIT\r\n")); } catch {}
  } finally {
    clearTimeout(timeout);
    reader?.releaseLock();
    writer?.releaseLock();
    await socket.close().catch(() => {});
  }
}
