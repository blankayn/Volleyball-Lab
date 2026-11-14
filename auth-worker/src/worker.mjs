import { connect } from "cloudflare:sockets";
import { createRecoveryWorker } from "./recovery.mjs";
import { sendGmailCode } from "./gmail.mjs";
import { firebaseResetAction } from "./firebase.mjs";

export default createRecoveryWorker({
  sendCode: message => sendGmailCode(message, connect),
  resetAction: firebaseResetAction,
});
