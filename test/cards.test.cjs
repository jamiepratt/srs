const { test } = require("node:test");
const assert = require("node:assert/strict");
const { JSDOM } = require("jsdom");
const fs = require("node:fs");
const key = "jamiepratt.srs.cards.v2";
const button = (w, text) =>
  [...w.document.querySelectorAll("button")].find(
    (b) => b.textContent === text,
  );
function app() {
  const w = new JSDOM('<div id="app"></div>', {
    url: "https://example.test/#study",
    runScripts: "dangerously",
  }).window;
  w.eval(fs.readFileSync("docs/js/purify.min.js", "utf8"));
  w.eval(fs.readFileSync("docs/js/main.js", "utf8"));
  const inputs = w.document.querySelectorAll("textarea");
  inputs[0].value = "Question";
  inputs[1].value = "Answer";
  w.document
    .querySelector("form")
    .dispatchEvent(new w.Event("submit", { cancelable: true }));
  return w;
}
function edit(w, front, back) {
  assert.ok(button(w, "Edit card"), "edit control exists");
  button(w, "Edit card").click();
  const fields = w.document.querySelectorAll(".edit-card textarea");
  assert.equal(fields.length, 2);
  fields[0].value = front;
  fields[1].value = back;
  fields.forEach((f) => f.dispatchEvent(new w.Event("input")));
  button(w, "Save changes").click();
}
test("card controls and add form start collapsed in the sidebar", async () => {
  const w = app();
  const options = w.document.querySelector(".sidebar .card-options-panel");
  const add = w.document.querySelector(".sidebar .add-card-panel");
  assert.ok(options);
  assert.ok(add);
  assert.equal(options.open, false);
  assert.equal(add.open, false);
  assert.match(options.querySelector("summary").textContent, /Card options.*Question/);
  assert.equal(w.document.querySelector(".study-card .card-actions"), null);
  assert.ok(options.querySelector(".move-label select"));
  assert.ok(options.querySelector(".card-actions button"));
  options.querySelector("summary").click();
  add.querySelector("summary").click();
  assert.equal(options.open, true);
  assert.equal(add.open, true);
  await new Promise((resolve) => setTimeout(resolve, 0));
  button(w, "Show answer · Space").click();
  assert.equal(w.document.querySelector(".card-options-panel").open, true);
  assert.equal(w.document.querySelector(".add-card-panel").open, true);
  w.close();
});
test("song audio starts per card and deck, can replay, and stops off the card", () => {
  const seed = app();
  const saved = JSON.parse(seed.localStorage.getItem(key));
  seed.close();
  saved.cards[0].front = "noc";
  saved.cards[0].deck = "Noc Komety";
  saved.cards.push({ ...saved.cards[0], id: "second-card", front: "kometa" });
  saved.cards.push({ ...saved.cards[0], id: "third-card", deck: "Takie tango" });
  const w = new JSDOM('<div id="app"></div>', {
    url: "https://example.test/#study",
    runScripts: "dangerously",
  }).window;
  w.localStorage.setItem(key, JSON.stringify(saved));
  w.localStorage.setItem("jamiepratt.srs.decks.v1", JSON.stringify(["Noc Komety", "Takie tango"]));
  w.SRS_CARD_AUDIO = {
    "Noc Komety": { noc: "audio/noc-komety/noc.mp3", kometa: "audio/noc-komety/kometa.mp3" },
    "Takie tango": { noc: "audio/takie-tango/noc.mp3" },
  };
  w.SRS_CARD_BACK_AUDIO = {
    "Noc Komety": { noc: "audio/noc-komety-phrases/phrase.mp3" },
  };
  const players = [];
  w.Audio = class {
    constructor(src) {
      this.src = src;
      this.plays = 0;
      this.pauses = 0;
      players.push(this);
    }
    play() { this.plays++; return Promise.resolve(); }
    pause() { this.pauses++; }
  };
  w.eval(fs.readFileSync("docs/js/purify.min.js", "utf8"));
  w.eval(fs.readFileSync("docs/js/main.js", "utf8"));
  assert.equal(players.length, 1);
  assert.equal(players[0].plays, 1);
  button(w, "Show answer · Space").click();
  assert.equal(players[0].plays, 1);
  assert.equal(players[0].pauses, 1);
  assert.equal(button(w, "Play pronunciation"), undefined);
  assert.equal(players[1].src, "audio/noc-komety-phrases/phrase.mp3");
  assert.equal(players[1].plays, 1);
  button(w, "Play song phrase").click();
  assert.equal(players[1].plays, 2);
  button(w, "kometa · due").click();
  assert.equal(players[1].pauses, 1);
  assert.equal(players[2].plays, 1);
  const deck = w.document.querySelector("select");
  deck.value = "Takie tango";
  deck.dispatchEvent(new w.Event("change"));
  assert.equal(players[2].pauses, 1);
  assert.equal(players[3].src, "audio/takie-tango/noc.mp3");
  assert.equal(players[3].plays, 1);
  w.location.hash = "#manage-decks";
  w.dispatchEvent(new w.Event("hashchange"));
  assert.equal(players[3].pauses, 1);
  w.close();
});
test("editing persists both sides while retaining schedule and history, and sanitizes display", () => {
  const w = app();
  button(w, "Show answer · Space").click();
  button(w, "3 Good").click();
  w.document.querySelector(".list-card").click();
  const before = JSON.parse(w.localStorage.getItem(key)).cards[0];
  edit(w, '<b>Updated</b><img src=x onerror="alert(1)">', "New answer");
  const after = JSON.parse(w.localStorage.getItem(key)).cards[0];
  assert.equal(after.front, '<b>Updated</b><img src=x onerror="alert(1)">');
  assert.equal(after.back, "New answer");
  assert.deepEqual(after.schedule, before.schedule);
  assert.deepEqual(after.reviews, before.reviews);
  assert.equal(after.id, before.id);
  assert.equal(
    w.document.querySelector(".card-text img").getAttribute("onerror"),
    null,
  );
  w.close();
});
test("deletion needs confirmation, removes persisted card and leaves the deck", () => {
  const w = app();
  assert.ok(button(w, "Delete card"), "delete control exists");
  w.confirm = () => false;
  button(w, "Delete card").click();
  assert.equal(JSON.parse(w.localStorage.getItem(key)).cards.length, 1);
  w.confirm = () => true;
  button(w, "Delete card").click();
  assert.equal(JSON.parse(w.localStorage.getItem(key)).cards.length, 0);
  assert.match(w.document.body.textContent, /Add a card to get started/);
  assert.equal(w.document.querySelector("select").value, "Default");
  w.close();
});
const settle = () => new Promise((r) => setTimeout(r, 30));
function installCloud() {
  window.supabase = {
    createClient: () => ({
      rpc(name) {
        if (name === "shared_deck_catalog") return Promise.resolve({ data: [] });
      },
      auth: {
        onAuthStateChange(fn) {
          window.authHandler = fn;
          fn("INITIAL_SESSION", { user: { id: "user-1" } });
        },
      },
      from(table) {
        return {
          select() {
            return {
              order() {
                return table === "decks"
                  ? Promise.resolve({ data: [{ name: "Default" }] })
                  : {
                      range: () =>
                        Promise.resolve(
                          window.failLoad
                            ? { error: { message: "Refresh offline" } }
                            : { data: window.rows },
                        ),
                    };
              },
            };
          },
          update(fields) {
            window.fields = fields;
            return mutation("update", fields);
          },
          delete() {
            return mutation("delete");
          },
        };
      },
    }),
  };
}
function cloudMutation(kind, fields) {
  let filters = {};
  const q = {
    eq(k, v) {
      filters[k] = v;
      return q;
    },
    select() {
      window.filters = filters;
      window.calls = (window.calls || 0) + 1;
      if (window.fail)
        return Promise.resolve({ error: { message: "Offline" } });
      const i = window.rows.findIndex(
        (r) => r.id === filters.id && r.revision === filters.revision,
      );
      if (i < 0) return Promise.resolve({ data: [] });
      const row = window.rows[i];
      if (kind === "delete") window.rows.splice(i, 1);
      else window.rows[i] = { ...row, ...fields };
      return Promise.resolve({
        data: [kind === "delete" ? row : window.rows[i]],
      });
    },
  };
  return q;
}
async function cloudApp() {
  const local = app();
  const rows = JSON.parse(local.localStorage.getItem(key)).cards;
  local.close();
  const w = new JSDOM('<div id="app"></div>', {
    url: "https://example.test/#study",
    runScripts: "dangerously",
  }).window;
  w.rows = w.JSON.parse(JSON.stringify(rows));
  w.SRS_CONFIG = {
    supabaseUrl: "https://cloud.test",
    supabasePublishableKey: "test",
  };
  w.eval(
    `window.mutation = ${cloudMutation.toString()}; (${installCloud.toString()})()`,
  );
  w.eval(fs.readFileSync("docs/js/purify.min.js", "utf8"));
  w.eval(fs.readFileSync("docs/js/main.js", "utf8"));
  await settle();
  return w;
}
test("cloud edits guard revision and update only contents, preserving history", async () => {
  const w = await cloudApp();
  const before = structuredClone(w.rows[0]);
  edit(w, "Cloud front", "Cloud back");
  await settle();
  assert.equal(w.rows[0].front, "Cloud front");
  assert.deepEqual(Object.keys(w.fields).sort(), ["back", "front", "revision"]);
  assert.equal(w.filters.revision, 0);
  assert.equal(w.rows[0].revision, 1);
  assert.deepEqual(structuredClone(w.rows[0].schedule), before.schedule);
  assert.deepEqual(structuredClone(w.rows[0].reviews), before.reviews);
  w.close();
});
test("cloud deletion guards revision and preserves concurrently reviewed cards", async () => {
  const w = await cloudApp();
  w.rows[0].revision = 1;
  w.rows[0].reviews.push({ rating: "good" });
  w.confirm = () => true;
  button(w, "Delete card").click();
  await settle();
  assert.equal(w.rows.length, 1);
  assert.equal(w.calls, 1);
  assert.equal(w.filters.revision, 0);
  assert.match(w.document.body.textContent, /changed on another device/);
  assert.equal(w.rows[0].reviews.length, 1);
  button(w, "Delete card").click();
  await settle();
  assert.equal(w.rows.length, 0);
  assert.match(w.document.body.textContent, /Card deleted/);
  w.close();
});
for (const action of ["edit", "delete"])
  test(`browser ${action} refuses stale card and retains other-tab changes`, () => {
    const w = app();
    const latest = JSON.parse(w.localStorage.getItem(key));
    latest.cards[0].back = "Other tab";
    latest.cards[0].reviews.push({ rating: "good" });
    w.localStorage.setItem(key, JSON.stringify(latest));
    if (action === "edit") edit(w, "New front", "New back");
    else {
      w.confirm = () => true;
      button(w, "Delete card").click();
    }
    assert.deepEqual(JSON.parse(w.localStorage.getItem(key)), latest);
    assert.match(w.document.body.textContent, /changed in another tab/);
    w.close();
  });
test("cloud edit conflict refreshes safely without losing draft or hiding feedback", async () => {
  const w = await cloudApp();
  w.rows[0].revision = 1;
  w.rows[0].back = "Reviewed elsewhere";
  w.rows[0].reviews.push({ rating: "good" });
  edit(w, "My draft", "My answer");
  await settle();
  assert.equal(w.rows[0].back, "Reviewed elsewhere");
  assert.equal(w.rows[0].reviews.length, 1);
  assert.match(w.document.body.textContent, /changed on another device/);
  assert.equal(
    w.document.querySelector(".edit-card textarea").value,
    "My draft",
  );
  button(w, "Cancel editing").click();
  button(w, "Edit card").click();
  assert.equal(
    w.document.querySelectorAll(".edit-card textarea")[1].value,
    "Reviewed elsewhere",
  );
  w.close();
});
for (const action of ["edit", "delete"])
  test(`cloud ${action} failure retains card and reports error`, async () => {
    const w = await cloudApp();
    w.fail = true;
    if (action === "edit") edit(w, "My draft", "My answer");
    else {
      w.confirm = () => true;
      button(w, "Delete card").click();
    }
    await settle();
    assert.equal(w.rows[0].front, "Question");
    assert.match(w.document.body.textContent, /Offline/);
    if (action === "edit")
      assert.equal(
        w.document.querySelector(".edit-card textarea").value,
        "My draft",
      );
    w.close();
  });
test("editing blocks review keyboard shortcuts and cancel leaves card unchanged", () => {
  const w = app();
  button(w, "Show answer · Space").click();
  const before = w.localStorage.getItem(key);
  button(w, "Edit card").click();
  w.document.dispatchEvent(
    new w.KeyboardEvent("keydown", { code: "Digit3", key: "3" }),
  );
  button(w, "Cancel editing").click();
  assert.equal(w.localStorage.getItem(key), before);
  w.close();
});
for (const action of ["edit", "delete"])
  test(`browser ${action} storage failure preserves card`, () => {
    const w = app();
    const before = w.localStorage.getItem(key);
    w.Storage.prototype.setItem = () => {
      throw new Error("Storage full");
    };
    if (action === "edit") edit(w, "My draft", "My answer");
    else {
      w.confirm = () => true;
      button(w, "Delete card").click();
    }
    assert.equal(w.localStorage.getItem(key), before);
    assert.match(w.document.body.textContent, /Storage full/);
    if (action === "edit")
      assert.equal(
        w.document.querySelector(".edit-card textarea").value,
        "My draft",
      );
    w.close();
  });
test("switching accounts clears editor and ignores an old pending save", async () => {
  const w = await cloudApp();
  w.eval(
    `const original=mutation;mutation=(kind,fields)=>{const q=original(kind,fields);q.select=()=>new Promise(resolve=>window.completeSave=resolve);return q}`,
  );
  edit(w, "Old account draft", "Answer");
  w.authHandler("SIGNED_OUT", null);
  await settle();
  w.completeSave(w.JSON.parse('{"error":{"message":"Old account failure"}}'));
  await settle();
  assert.equal(w.document.querySelector(".edit-card"), null);
  assert.doesNotMatch(w.document.body.textContent, /Old account/);
  w.close();
});

test("conflict refresh failure remains visible without claiming refreshed cards", async () => {
  const w = await cloudApp();
  w.rows[0].revision = 1;
  w.failLoad = true;
  w.confirm = () => true;
  button(w, "Delete card").click();
  await settle();
  assert.match(w.document.body.textContent, /Refresh offline/);
  assert.doesNotMatch(w.document.body.textContent, /Cards refreshed/);
  assert.doesNotMatch(w.document.body.textContent, /Cancel editing/);
  assert.equal(w.rows.length, 1);
  w.close();
});
test("choosing another card cancels the editor without applying its draft", () => {
  const w = app();
  const inputs = w.document.querySelectorAll("textarea");
  inputs[0].value = "Second question";
  inputs[1].value = "Second answer";
  button(w, "Add card").click();
  const before = w.localStorage.getItem(key);
  button(w, "Edit card").click();
  const field = w.document.querySelector(".edit-card textarea");
  field.value = "Unsaved draft";
  field.dispatchEvent(new w.Event("input"));
  w.document.querySelector(".list-card").click();
  assert.equal(w.document.querySelector(".edit-card"), null);
  assert.equal(w.localStorage.getItem(key), before);
  w.close();
});
test("blank edit stays open and leaves saved contents intact", () => {
  const w = app();
  const before = w.localStorage.getItem(key);
  edit(w, "   ", "Answer");
  assert.match(w.document.body.textContent, /cannot be empty/);
  assert.ok(w.document.querySelector(".edit-card"));
  assert.equal(w.localStorage.getItem(key), before);
  w.close();
});
