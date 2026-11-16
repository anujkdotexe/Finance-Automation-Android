# Legacy test harness

These files were the root `tests/` folder in the early planning rounds. They are kept for history only.

## What they are

- Python and Java programs that re-implement the accounting and parsing rules (split math, net burn, notification parsing) as a separate model.
- They run against that model, not against the Android app.

## What they are not

- They are not evidence that the app works. A pass here says only that the model agrees with itself.
- They are not run by the build, by CI or by the Android test task.
- The real checks are the JVM unit tests under `app/src/test`, run with `testBetaUnitTest`, plus hand testing on a phone.

## Running them

Run from this folder, with plain Python 3 (do not use isolated mode, the files import each other):

```bash
python run_e2e_tests.py
```

The Java files declare `package tests;` and are not wired into any build.

## Rules

- Do not add new tests here. Add them under `app/src/test`.
- Do not cite a result from this folder in a report as proof of app behaviour.
