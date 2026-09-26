# Spaced Repetition System

The [cljc-fsrs fork](https://github.com/jamiepratt/cljc-fsrs) scheduler is
included as a Git submodule and used as a local Clojure dependency. It
implements [FSRS-6](https://github.com/jamiepratt/cljc-fsrs/pull/2).

After cloning this repository, initialize the submodule:

```sh
git submodule update --init --recursive
```

## Card viewer

The card viewer is written in ClojureScript. It shows the front and back of a
card, records one of the four FSRS ratings, and selects the next due card. It
also lets you organize cards into decks, add cards, and export or import a JSON
backup. Select any card in the list, then use **Edit card** to change its front
and back without resetting its schedule or review history. **Cancel editing**
discards the draft. **Delete card** asks for confirmation and permanently removes
the card and its history. Both actions work with browser storage and Supabase.
Cloud conflicts refresh the cards and keep an unsaved edit visible; cancel editing
to inspect the latest version before retrying. Browser edits and deletions also
check the saved card before writing, preserving changes already saved by another tab.

Card fronts and backs accept HTML fragments such as `<strong>`, lists, links,
and images. HTML is sanitized when displayed; scripts and unsafe attributes are
removed. Plain text and line breaks continue to display normally. The card
list shows text extracted from each front.

Build the static site with:

```sh
npm ci
npm run build
```

Serve the `docs/` directory with a local HTTP server to use it. GitHub Pages
publishes the `main` branch's `/docs` directory at
[srs.submergedstructure.com](https://srs.submergedstructure.com/). The built
JavaScript is committed so Pages does not need to run ClojureScript tooling.

## User accounts and storage

The app uses Supabase Auth email links and a Postgres `cards` table. Each row
contains one user's card text, FSRS scheduling state, and review history. Row
level security limits reads and writes to that user. A `revision` field rejects
stale reviews, edits, moves, and deletions from another device.
Sending a sign-in link requires agreement to the public Terms of Service in
`docs/terms.html`. Manage decks offers account deletion beside Sign out. It
requires confirmation and the account email, then calls the authenticated
`delete_account` database function to remove the auth user and cloud data.
Migration `202609260002_delete_account.sql` provides the deletion function.

The FSRS-6 schedule stores its version and learning step alongside stability,
difficulty, due time, and review counts. Each card also stores its deck. Existing
cards are in the Default deck. The study screen keeps the deck selector. The
**Manage decks** page has separate fields to create and rename decks, plus
backup and account actions. **Export all cards** includes all cards;
**Export selected deck** includes only cards in the selected deck and records
its name even when empty.
**Merge decks** moves every card from the selected deck into another deck,
preserving schedules and review history, then removes the selected deck.
The export checkbox includes schedules and review history by default. Uncheck it
to export card content and deck names only; importing that file starts each card
with a new schedule and empty review history. The FSRS v4 cards were
deleted during the FSRS-6 upgrade because their memory values are incompatible.
Browser storage and JSON backups use version 2; older cards and backups are ignored.

The [SRS Supabase project](https://supabase.com/dashboard/project/dvnddujfsmtwhfelfpdc)
is in Frankfurt. Its migration is applied. `supabase/config.toml` holds the
public site URL, local redirect URL, and Resend SMTP settings. `docs/config.js`
contains the project URL and publishable browser key. Never put a secret or
service-role key there.

To apply later database migrations, run `supabase db push --linked`. To change
Auth settings, set `SRS_RESEND_API_KEY` to the domain-restricted Resend sending
key, review `supabase config diff`, then run `supabase config push`. The key is
stored in Supabase Auth, not this repo.

Sign in and use **Move browser cards** to copy cards from this browser's
`localStorage` into your account. Export/import JSON backups remain available.
After selecting a backup, choose whether to keep its deck names, add all cards
to an existing deck, or create a new deck. Importing adds cards with new IDs.

Shared decks appear on **Manage decks**. Copying one creates a private deck
with fresh schedules and no review history. Every new account receives private
copies of the published decks automatically. New decks are private by default;
publishing a deck is an explicit database setting. The published song decks are
Noc Komety, Takie tango, Wszystko kwitnie wkoło, and Zacznij od Bacha. Their
song cards include annotated Polish phrases, English translations, and a grammar
x-ray key below the card.

Song card fronts have generated pronunciation audio. Each front plays when
shown, and **Play pronunciation** replays it. Noc Komety also plays its Polish
song phrase when the answer is revealed; **Play song phrase** replays it.
Browsers may block automatic audio
on the first page load until the user interacts with the page. The MP3 files and
front-to-file manifest live in `docs/audio/`. To regenerate them from an exported
backup of all song decks, run `api-shell python3 scripts/generate-song-deck-audio.py
BACKUP.json`.

Auth mail is sent through Resend as `SRS Cards <no-reply@submergedstructure.com>`.
The domain has verified DKIM and return-path records at name.com. A sign-in
message to an address outside the Supabase organization was delivered and its
link redirected to `https://srs.submergedstructure.com/`; see
[issue #2](https://github.com/jamiepratt/srs/issues/2). Supabase's custom SMTP
rate limit starts at 30 messages per hour.

### Deleting decks

Manage decks can delete the selected deck after confirmation. Only empty decks
can be deleted, and at least one deck must remain. Move cards to another deck
from the study screen first. Deletion preserves all cards and their schedules.
The remaining deck is selected automatically. Browser saves are checked again
before deletion; cloud checks run in the database against current data.

Migration `202609250005_delete_deck.sql` adds deck membership constraints and an
authenticated deletion RPC. Apply it before deploying this UI. Concurrent cloud
moves to a deleted deck fail; inserting or importing cards intentionally creates
their named deck, including from older clients. Server rejections refresh decks
and explain the failure, including stale selections or an unsaved initial Default
deck. The database never cascades a deck deletion into card deletion.

### Tests

`npm test` builds and exercises the public UI with jsdom, localStorage, and a
Supabase boundary stand-in. `npm run test:sql` requires local PostgreSQL with
permission to create a temporary database (and test roles if absent). It applies
all migrations, tests authenticated operations and isolation, and exercises
concurrent card insertion/deletion and last-deck deletion. The temporary database
and any roles created by the test are removed afterward.
