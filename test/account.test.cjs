const { test } = require("node:test");
const assert = require("node:assert/strict");
const { JSDOM } = require("jsdom");
const fs = require("node:fs");

function app(signedIn = false, deletionError = null) {
  const w = new JSDOM('<div id="app"></div>', {
    url: "https://example.test/#manage-decks",
    runScripts: "dangerously",
  }).window;
  w.SRS_CONFIG = { supabaseUrl: "https://cloud.test", supabasePublishableKey: "test" };
  w.calls = { links: 0, deletion: 0, signOut: 0 };
  w.supabase = { createClient: () => ({
    auth: {
      onAuthStateChange(handler) {
        handler("INITIAL_SESSION", signedIn ? {
          user: { id: "user-1", email: "test@example.test" },
        } : null);
      },
      signInWithOtp() { w.calls.links++; return Promise.resolve({ data: {} }); },
      signOut() { w.calls.signOut++; return Promise.resolve({ error: null }); },
    },
    from(table) { return { select() { return { order() {
      return table === "decks" ? Promise.resolve({ data: [{ name: "Default" }] }) :
        { range() { return Promise.resolve({ data: [] }); } };
    } }; } }; },
    rpc(name) {
      if (name === "shared_deck_catalog") return Promise.resolve({ data: [] });
      assert.equal(name, "delete_account");
      w.calls.deletion++;
      return Promise.resolve(deletionError ? { error: { message: deletionError } } : { data: null });
    },
  }) };
  w.eval(fs.readFileSync("docs/js/purify.min.js", "utf8"));
  w.eval(fs.readFileSync("docs/js/main.js", "utf8"));
  return w;
}

const settle = () => new Promise((resolve) => setTimeout(resolve, 30));
const button = (w, label) => [...w.document.querySelectorAll("button")]
  .find((node) => node.textContent === label);

test("sign-in link requires explicit Terms agreement", async () => {
  const w = app();
  await settle();
  const form = w.document.querySelector(".login-form");
  const email = form.querySelector('input[type="email"]');
  const terms = form.querySelector('input[type="checkbox"]');
  assert.equal(terms.checked, false);
  assert.equal(terms.required, true);
  assert.equal(form.querySelector('a[href="terms.html"]').textContent, "Terms of Service");
  email.value = "test@example.test";
  form.dispatchEvent(new w.Event("submit", { cancelable: true }));
  assert.equal(w.calls.links, 0);
  terms.checked = true;
  form.dispatchEvent(new w.Event("submit", { cancelable: true }));
  await settle();
  assert.equal(w.calls.links, 1);
  w.close();
});

test("account deletion needs both confirmation steps and reports RPC failures", async () => {
  const w = app(true, "server unavailable");
  await settle();
  const deletion = button(w, "Delete and close account");
  assert.ok(deletion.classList.contains("danger"));
  assert.equal(deletion.previousSibling.textContent, "Sign out");
  w.confirm = () => false;
  deletion.click();
  assert.equal(w.calls.deletion, 0);
  w.confirm = () => true;
  w.prompt = () => "wrong";
  deletion.click();
  assert.equal(w.calls.deletion, 0);
  w.prompt = () => "test@example.test";
  deletion.click();
  await settle();
  assert.equal(w.calls.deletion, 1);
  assert.equal(w.calls.signOut, 0);
  assert.match(w.document.body.textContent, /Account deletion failed: server unavailable/);
  w.close();
});

test("successful account deletion signs out", async () => {
  const w = app(true);
  await settle();
  w.confirm = () => true;
  w.prompt = () => "test@example.test";
  button(w, "Delete and close account").click();
  await settle();
  assert.equal(w.calls.deletion, 1);
  assert.equal(w.calls.signOut, 1);
  assert.match(w.document.body.textContent, /Your account was deleted/);
  w.close();
});
