# Spaced Repetition System

This repository will contain a spaced repetition system.

The [cljc-fsrs fork](https://github.com/jamiepratt/cljc-fsrs) scheduler is
included as a Git submodule and used as a local Clojure dependency. It implements
FSRS v4. The [FSRS-6 update](https://github.com/jamiepratt/cljc-fsrs/issues/1)
is tracked in the fork.

After cloning this repository, initialize the submodule:

```sh
git submodule update --init --recursive
```

## Card viewer

The card viewer is written in ClojureScript. It shows the front and back of a
card, records one of the four FSRS ratings, and selects the next due card. It
also lets you add cards and export or import a JSON backup.

Build the static site with:

```sh
npm ci
npm run build
```

Serve the `docs/` directory with a local HTTP server to use it. For GitHub
Pages, publish the `main` branch's `/docs` directory. The built JavaScript is
committed so Pages does not need to run ClojureScript tooling.

## User accounts and storage

The app uses Supabase Auth email links and a Postgres `cards` table. Each row
contains one user's card text, FSRS scheduling state, and review history. Row
level security limits reads and writes to that user. A `revision` field rejects
stale review updates from another device.

To connect a new Supabase project:

1. Create a project at [Supabase](https://supabase.com/dashboard).
2. Apply `supabase/migrations/202609250001_create_cards.sql` in the project's
   SQL editor. Apply the schema before adding the browser key.
3. Under Authentication > URL Configuration, set the Site URL to the eventual
   GitHub Pages address and add its exact URL to the redirect allowlist. Add
   `http://localhost:8765/` for local sign-in testing.
4. Copy the project's URL and **publishable** key from Project Settings > API
   Keys into `docs/config.js`. Never put a secret or service-role key there.
5. Rebuild with `npm run build`, then serve `docs/` and sign in.

Until `docs/config.js` is filled in, the viewer remains in local preview mode
and stores cards in this browser's `localStorage`. Live project activation is
tracked in [issue #1](https://github.com/jamiepratt/srs/issues/1). Once cloud storage is
configured, sign in and use **Move browser cards** to copy those cards into your
account. Export/import JSON backups remain available. Importing a backup into
an account adds cards with new IDs; importing in local mode replaces the local
cards after confirmation.
