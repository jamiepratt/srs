const { test } = require("node:test");
const assert = require("node:assert/strict");
const { JSDOM } = require("jsdom");
const fs = require("node:fs");
function app(decks = ["Default", "Unused"], client) {
  const dom = new JSDOM('<div id="app"></div>', {
    url: "https://example.test/#manage-decks",
    runScripts: "dangerously",
  });
  if (client) {
    dom.window.SRS_CONFIG = {
      supabaseUrl: "https://cloud.test",
      supabasePublishableKey: "test",
    };
    dom.window.assert = assert;
    dom.window.supabase = {
      createClient: () =>
        dom.window.eval(
          `(${cloudClient.toString()})(${JSON.stringify(client.error)})`,
        ),
    };
  }
  dom.window.localStorage.setItem(
    "jamiepratt.srs.decks.v1",
    JSON.stringify(decks),
  );
  dom.window.eval(fs.readFileSync("docs/js/purify.min.js", "utf8"));
  dom.window.eval(fs.readFileSync("docs/js/main.js", "utf8"));
  return dom.window;
}
function button(w, text) {
  return [...w.document.querySelectorAll("button")].find(
    (b) => b.textContent === text,
  );
}
function select(w, name) {
  const s = w.document.querySelector("select");
  s.value = name;
  s.dispatchEvent(new w.Event("change"));
}
test("delete empty deck requires confirmation, persists and selects remaining deck", () => {
  const w = app();
  select(w, "Unused");
  let prompts = 0;
  w.confirm = () => {
    prompts++;
    return false;
  };
  assert.ok(button(w, "Delete selected deck"), "delete control exists");
  button(w, "Delete selected deck").click();
  assert.equal(prompts, 1);
  assert.equal(w.document.querySelector("select").value, "Unused");
  w.confirm = () => true;
  button(w, "Delete selected deck").click();
  assert.deepEqual(
    JSON.parse(w.localStorage.getItem("jamiepratt.srs.decks.v1")),
    ["Default"],
  );
  assert.equal(w.document.querySelector("select").value, "Default");
  w.close();
});
test("last deck cannot be deleted", () => {
  const w = app(["Only"]);
  assert.equal(button(w, "Delete selected deck").disabled, true);
  assert.match(w.document.body.textContent, /Keep at least one deck/);
  w.close();
});
test("nonempty deck cannot be deleted and cards remain intact", () => {
  const w = app();
  w.location.hash = "#study";
  w.dispatchEvent(new w.Event("hashchange"));
  const inputs = w.document.querySelectorAll("textarea");
  inputs[0].value = "Question";
  inputs[1].value = "Answer";
  w.document
    .querySelector("form")
    .dispatchEvent(new w.Event("submit", { cancelable: true }));
  const before = w.localStorage.getItem("jamiepratt.srs.cards.v2");
  w.location.hash = "#manage-decks";
  w.dispatchEvent(new w.Event("hashchange"));
  assert.equal(button(w, "Delete selected deck").disabled, true);
  assert.match(w.document.body.textContent, /Move all cards/);
  assert.equal(w.localStorage.getItem("jamiepratt.srs.cards.v2"), before);
  w.close();
});

function cloudClient(error = null) {
  let decks = ["Default", "Unused"];
  return {
    error,
    auth: {
      onAuthStateChange(handler) {
        handler("INITIAL_SESSION", {
          user: { id: "user-1", email: "test@example.test" },
        });
      },
    },
    from(table) {
      return {
        select() {
          return {
            order() {
              return table === "decks"
                ? Promise.resolve({ data: decks.map((name) => ({ name })) })
                : {
                    range() {
                      return Promise.resolve({ data: [] });
                    },
                  };
            },
          };
        },
      };
    },
    rpc(name, args) {
      window.rpcCalls = (window.rpcCalls || 0) + 1;
      assert.equal(name, "delete_deck");
      assert.equal(args.deck_name, "Unused");
      if (!error) decks = ["Default"];
      return Promise.resolve(
        error ? { error: { message: error } } : { data: null },
      );
    },
  };
}
const settle = () => new Promise((resolve) => setTimeout(resolve, 30));
test("cloud deletion calls authenticated RPC and refreshes selected deck", async () => {
  const w = app(undefined, cloudClient());
  await settle();
  select(w, "Unused");
  w.confirm = () => true;
  button(w, "Delete selected deck").click();
  await settle();
  assert.equal(w.rpcCalls, 1);
  assert.deepEqual(
    [...w.document.querySelector("select").options].map((o) => o.value),
    ["Default"],
  );
  assert.equal(w.document.querySelector("select").value, "Default");
  w.close();
});
test("cloud rejection preserves deck and reports why", async () => {
  const w = app(undefined, cloudClient("Deck is not empty."));
  await settle();
  select(w, "Unused");
  w.confirm = () => true;
  button(w, "Delete selected deck").click();
  await settle();
  assert.match(
    w.document.body.textContent,
    /Could not delete deck: Deck is not empty/,
  );
  assert.equal(w.document.querySelector("select").value, "Unused");
  w.close();
});
test("browser storage failure keeps the selected deck and shows an error", () => {
  const w = app();
  select(w, "Unused");
  w.confirm = () => true;
  w.Storage.prototype.setItem = () => {
    throw new Error("Storage full");
  };
  button(w, "Delete selected deck").click();
  assert.equal(w.document.querySelector("select").value, "Unused");
  assert.match(
    w.document.body.textContent,
    /Could not delete deck: Storage full/,
  );
  w.close();
});
test("a card added by another tab prevents deleting its deck", () => {
  const w = app();
  const other = app();
  other.location.hash = "#study";
  other.dispatchEvent(new other.Event("hashchange"));
  const inputs = other.document.querySelectorAll("textarea");
  inputs[0].value = "New question";
  inputs[1].value = "New answer";
  other.document
    .querySelector("form")
    .dispatchEvent(new other.Event("submit", { cancelable: true }));
  const saved = other.localStorage.getItem("jamiepratt.srs.cards.v2");
  w.localStorage.setItem("jamiepratt.srs.cards.v2", saved);
  w.confirm = () => true;
  button(w, "Delete selected deck").click();
  assert.deepEqual(
    JSON.parse(w.localStorage.getItem("jamiepratt.srs.decks.v1")),
    ["Default", "Unused"],
  );
  assert.equal(w.localStorage.getItem("jamiepratt.srs.cards.v2"), saved);
  assert.equal(w.document.querySelector("select").value, "Default");
  assert.match(w.document.body.textContent, /Move all cards/);
  w.close();
  other.close();
});
