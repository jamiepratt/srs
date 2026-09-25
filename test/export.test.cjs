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
