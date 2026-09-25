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
backup.

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
stale review updates from another device.

The FSRS-6 schedule stores its version and learning step alongside stability,
difficulty, due time, and review counts. Each card also stores its deck. Existing
cards are in the Default deck. The shared deck-name field works with **New deck**
and **Rename deck**. **Export backup** includes all cards; **Export current deck**
includes only cards in the selected deck and records its name even when empty.
Both exports preserve the schedule and review history. The FSRS v4 cards were
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
Importing a backup into an account adds cards with new IDs.

Auth mail is sent through Resend as `SRS Cards <no-reply@submergedstructure.com>`.
The domain has verified DKIM and return-path records at name.com. A sign-in
message to an address outside the Supabase organization was delivered and its
link redirected to `https://srs.submergedstructure.com/`; see
[issue #2](https://github.com/jamiepratt/srs/issues/2). Supabase's custom SMTP
rate limit starts at 30 messages per hour.
