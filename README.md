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

Cards and scheduling state are saved in this browser's `localStorage`. GitHub
Pages has no database or cross-device sync. Export a backup before clearing
browser data or moving to another browser or device; importing replaces the
current cards after confirmation.
