# Pinry Reborn - API server

This directory contains the API server for the project.  
It is in charge of all the business logic, to be called by clients.

## Running

To start the API locally in dev mode

```sh
./gradlew quarkusDev
```

## Git hooks

This repo ships its git hooks in `.githooks/`. Enable them once per clone:

```sh
git config core.hooksPath .githooks
```

- `pre-commit` regenerates the OpenAPI spec.
- `pre-push` runs `./gradlew check koverVerify` (detekt, tests, and 100% branch coverage).

## Architecture

The API follows the clean architecture principle, with each part in its own submodule.

