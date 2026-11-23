#!/usr/bin/env node
// Run JavaScript in the page Lurkr's viewer is showing, through WebView DevTools (debug builds only).
// First: scripts/dev.sh devtools (forwards the WebView to localhost:9222).
//
//   scripts/devtools.mjs 'document.title'
//   scripts/devtools.mjs -f probe.js        run a file
//
// Prints the result as JSON. Uses Node's built-in WebSocket (Node 22+).
import { readFileSync } from "node:fs";

const args = process.argv.slice(2);
const expression = args[0] === "-f" ? readFileSync(args[1], "utf8") : args.join(" ");
if (!expression) {
  console.error("usage: scripts/devtools.mjs <expression> | -f file.js");
  process.exit(1);
}

const pages = await (await fetch("http://localhost:9222/json")).json();
const page = pages.find((p) => p.type === "page" && p.webSocketDebuggerUrl);
if (!page) {
  console.error("no page (is the viewer open? run scripts/dev.sh devtools)");
  process.exit(1);
}
console.error(`# ${page.url}`);

const ws = new WebSocket(page.webSocketDebuggerUrl);
await new Promise((ok, fail) => { ws.onopen = ok; ws.onerror = fail; });
ws.onmessage = (e) => {
  const msg = JSON.parse(e.data);
  if (msg.id !== 1) return;
  const r = msg.result;
  if (r?.exceptionDetails) console.log("ERROR", JSON.stringify(r.exceptionDetails.exception?.description ?? r.exceptionDetails));
  else console.log(JSON.stringify(r?.result?.value, null, 1));
  ws.close();
  process.exit(0);
};
ws.send(JSON.stringify({ id: 1, method: "Runtime.evaluate", params: { expression, returnByValue: true, awaitPromise: true } }));
setTimeout(() => { console.error("timeout"); process.exit(1); }, 15000);
