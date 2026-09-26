const { test } = require("node:test");
const assert = require("node:assert/strict");
const { JSDOM } = require("jsdom");
const fs = require("node:fs");

function app() {
  const w = new JSDOM('<div id="app"></div>', {
    url: "https://example.test/#study",
    runScripts: "dangerously",
  }).window;
  w.eval(fs.readFileSync("docs/js/purify.min.js", "utf8"));
  w.eval(fs.readFileSync("docs/js/main.js", "utf8"));
  return w;
}

function button(w, text) {
  return [...w.document.querySelectorAll("button")].find(
    (node) => node.textContent === text,
  );
}

function manage(w) {
  w.location.hash = "#manage-decks";
  w.dispatchEvent(new w.Event("hashchange"));
}

function readBlob(w, blob) {
  return new Promise((resolve, reject) => {
    const reader = new w.FileReader();
    reader.onload = () => resolve(JSON.parse(reader.result));
    reader.onerror = () => reject(reader.error);
    reader.readAsText(blob);
  });
}

async function chooseFile(w, backup) {
  const input = w.document.querySelector('input[type="file"]');
  const file = new w.File([JSON.stringify(backup)], "cards.json", { type: "application/json" });
  Object.defineProperty(input, "files", { configurable: true, value: [file] });
  input.dispatchEvent(new w.Event("change"));
  await new Promise((resolve) => w.setTimeout(resolve, 30));
}

test("both export buttons follow the scheduling checkbox and unscheduled imports start fresh", async () => {
  const w = app();
  const inputs = w.document.querySelectorAll("textarea");
  inputs[0].value = "Question";
  inputs[1].value = "Answer";
  w.document.querySelector("form").dispatchEvent(new w.Event("submit", { cancelable: true }));
  button(w, "Show answer · Space").click();
  button(w, "3 Good").click();
  const original = JSON.parse(w.localStorage.getItem("jamiepratt.srs.cards.v2")).cards[0];
  assert.equal(original.reviews.length, 1);

  manage(w);
  let exported;
  w.URL.createObjectURL = (blob) => { exported = blob; return "blob:export"; };
  w.URL.revokeObjectURL = () => {};
  w.HTMLAnchorElement.prototype.click = () => {};
  const checkbox = w.document.querySelector(".export-option input");
  assert.equal(checkbox.checked, true);
  button(w, "Export selected deck").click();
  const scheduled = await readBlob(w, exported);
  assert.equal(scheduled.deck_name, "Default");
  assert.deepEqual(scheduled.cards[0].reviews, original.reviews);
  assert.deepEqual(scheduled.cards[0].schedule, original.schedule);

  checkbox.checked = false;
  checkbox.dispatchEvent(new w.Event("change"));
  button(w, "Export selected deck").click();
  const selected = await readBlob(w, exported);
  assert.equal(selected.scheduling_data, false);
  assert.equal(selected.deck_name, "Default");
  assert.deepEqual(Object.keys(selected.cards[0]).sort(), ["back", "deck", "front", "id"]);
  button(w, "Export all cards").click();
  const all = await readBlob(w, exported);
  assert.equal(all.scheduling_data, false);
  assert.equal(all.deck_name, undefined);
  assert.deepEqual(all.cards, selected.cards);

  const restored = app();
  manage(restored);
  restored.confirm = () => true;
  const input = restored.document.querySelector('input[type="file"]');
  const file = new restored.File([JSON.stringify(selected)], "deck.json", { type: "application/json" });
  Object.defineProperty(input, "files", { value: [file] });
  input.dispatchEvent(new restored.Event("change"));
  await new Promise((resolve) => restored.setTimeout(resolve, 30));
  assert.equal(restored.localStorage.getItem("jamiepratt.srs.cards.v2"), null);
  const destination = restored.document.querySelector(".import-destination");
  assert.ok(destination, "import asks for a destination");
  destination.value = "source";
  destination.dispatchEvent(new restored.Event("change"));
  button(restored, "Import cards").click();
  const card = JSON.parse(restored.localStorage.getItem("jamiepratt.srs.cards.v2")).cards[0];
  assert.equal(card.front, original.front);
  assert.equal(card.back, original.back);
  assert.equal(card.deck, "Default");
  assert.equal(card.schedule.reps, 0);
  assert.equal(card.schedule.state, "new");
  assert.deepEqual(card.reviews, []);
  restored.close();
  w.close();
});

test("account import waits for a destination and routes cards to existing or new deck", async () => {
  const w = new JSDOM('<div id="app"></div>', {
    url: "https://example.test/#manage-decks",
    runScripts: "dangerously",
  }).window;
  const inserted = [];
  w.SRS_CONFIG = { supabaseUrl: "https://cloud.test", supabasePublishableKey: "test" };
  w.supabase = {
    createClient: () => ({
      rpc(name) {
        if (name === "shared_deck_catalog") return Promise.resolve({ data: [] });
      },
      auth: {
        onAuthStateChange(handler) {
          handler("INITIAL_SESSION", { user: { id: "user-1", email: "test@example.test" } });
        },
      },
      from(table) {
        return {
          select() {
            return {
              order() {
                return table === "decks"
                  ? Promise.resolve({ data: [{ name: "Default" }] })
                  : { range: () => Promise.resolve({ data: [] }) };
              },
            };
          },
          insert(rows) {
            inserted.push(...rows);
            return { select: () => Promise.resolve({ data: rows }) };
          },
        };
      },
    }),
  };
  w.eval(fs.readFileSync("docs/js/purify.min.js", "utf8"));
  w.eval(fs.readFileSync("docs/js/main.js", "utf8"));
  await new Promise((resolve) => w.setTimeout(resolve, 30));

  const backup = {
    version: 2, scheduling_data: false, deck_name: "Source",
    cards: [{ id: "old-id", front: "Question", back: "Answer", deck: "Source" }],
  };
  await chooseFile(w, backup);
  assert.equal(inserted.length, 0);
  assert.equal(w.document.querySelector(".import-destination").value, "");
  button(w, "Import cards").click();
  assert.match(w.document.body.textContent, /Choose where to import/);
  const destination = w.document.querySelector(".import-destination");
  destination.value = "deck:Default";
  destination.dispatchEvent(new w.Event("change"));
  button(w, "Import cards").click();
  await new Promise((resolve) => w.setTimeout(resolve, 30));
  assert.equal(inserted.length, 1, w.document.body.textContent);
  assert.equal(inserted[0].deck, "Default");
  assert.equal(inserted[0].user_id, "user-1");
  assert.notEqual(inserted[0].id, "old-id");
  assert.equal(w.document.querySelector(".import-destination"), null);
  assert.equal(w.document.querySelector("select").value, "Default");

  await chooseFile(w, backup);
  const newDestination = w.document.querySelector(".import-destination");
  newDestination.value = "new";
  newDestination.dispatchEvent(new w.Event("change"));
  w.document.querySelector(".import-preview input").value = "Fresh deck";
  w.document.querySelector(".import-preview input").dispatchEvent(new w.Event("input"));
  button(w, "Import cards").click();
  await new Promise((resolve) => w.setTimeout(resolve, 30));
  assert.equal(inserted.length, 2);
  assert.equal(inserted[1].deck, "Fresh deck");
  assert.equal(w.document.querySelector("select").value, "Fresh deck");
  w.close();
});

test("browser import adds cards to a chosen new deck without replacing existing cards", async () => {
  const w = app();
  const fields = w.document.querySelectorAll("textarea");
  fields[0].value = "Existing question";
  fields[1].value = "Existing answer";
  w.document.querySelector("form").dispatchEvent(new w.Event("submit", { cancelable: true }));
  const before = JSON.parse(w.localStorage.getItem("jamiepratt.srs.cards.v2")).cards[0];
  manage(w);
  await chooseFile(w, {
    version: 2, scheduling_data: false, deck_name: "Source",
    cards: [{ id: before.id, front: "Imported question", back: "Imported answer", deck: "Source" }],
  });
  const destination = w.document.querySelector(".import-destination");
  destination.value = "new";
  destination.dispatchEvent(new w.Event("change"));
  const name = w.document.querySelector(".import-preview input");
  name.value = "New home";
  name.dispatchEvent(new w.Event("input"));
  button(w, "Import cards").click();
  const cards = JSON.parse(w.localStorage.getItem("jamiepratt.srs.cards.v2")).cards;
  assert.equal(cards.length, 2);
  assert.equal(cards[0].id, before.id);
  assert.equal(cards[0].deck, "Default");
  assert.notEqual(cards[1].id, before.id);
  assert.equal(cards[1].deck, "New home");
  assert.equal(w.document.querySelector("select").value, "New home");
  w.close();
});
