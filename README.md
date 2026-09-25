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
