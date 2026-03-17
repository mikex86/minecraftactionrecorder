# Upload Server

Standalone Java upload server for chunked recording uploads, powered by Netty.

## Run

From repository root:

```bash
bash ./gradlew -p upload-server run
```

Or from this folder:

```bash
bash ../gradlew run
```

The server listens on `http://0.0.0.0:8081` and stores data in:

- `<working_dir>/upload-server-data/`

`working_dir` is the process working directory (`user.dir`), usually configured in your run configuration.

## Throttle Mode (Testing)

Throttle incoming chunk uploads to make progress behavior easier to observe:

```bash
bash ./gradlew -p upload-server run --args="--throttle-1mbit"
```

Or set a custom throttle:

```bash
bash ./gradlew -p upload-server run --args="--throttle-mbit=2.5"
```
