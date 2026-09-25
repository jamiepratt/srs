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
also lets you add cards and export or import a JSON backup.

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
[jamiepratt.github.io/srs](https://jamiepratt.github.io/srs/). The built
JavaScript is committed so Pages does not need to run ClojureScript tooling.

## User accounts and storage

The app uses Supabase Auth email links and a Postgres `cards` table. Each row
contains one user's card text, FSRS scheduling state, and review history. Row
level security limits reads and writes to that user. A `revision` field rejects
stale review updates from another device.

The FSRS-6 schedule stores its version and learning step alongside stability,
difficulty, due time, and review counts. The FSRS v4 cards were deleted during
the FSRS-6 upgrade because their memory values are incompatible. Browser
storage and JSON backups use version 2; older cards and backups are ignored.

The [SRS Supabase project](https://supabase.com/dashboard/project/dvnddujfsmtwhfelfpdc)
is in Frankfurt. Its migration is applied. `supabase/config.toml` holds the
public site URL and local redirect URL. `docs/config.js` contains the project
URL and publishable browser key. Never put a secret or service-role key there.

To apply later database migrations or Auth URL changes, run
`supabase db push --linked` or `supabase config push` after reviewing
`supabase config diff`.

Sign in and use **Move browser cards** to copy cards from this browser's
`localStorage` into your account. Export/import JSON backups remain available.
Importing a backup into an account adds cards with new IDs.

Supabase's default email sender only sends Auth emails to organization members.
Sign-in for other users needs custom SMTP or a Send Email Auth Hook; see
[issue #2](https://github.com/jamiepratt/srs/issues/2).
