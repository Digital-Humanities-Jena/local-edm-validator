# Local EDM Validator

This fork accelerates METIS Sandbox operations required by WebHDP and provides a simple CLI.

It is based on the Europeana METIS Sandbox and keeps the original dataset-processing capabilities, while adding a faster local path for validating EDM RDF/XML records and a command-line client for repeatable local workflows.

## Main Features

* Direct validation of one EDM RDF/XML record without creating a full dataset job.
* Batch validation of multiple EDM RDF/XML records with one report per input file.
* CLI commands for validation, batch validation, dataset harvests, dataset progress, dataset info, and country/language lookup.
* Local Docker Compose stack for the REST service and required infrastructure.

## Documentation

See [Local EDM Validator documentation](docs/local-edm-validator.md) for setup, CLI usage, API requests, configuration, and troubleshooting notes.

## Validation Speedup

The original direct validation path still ran through four Spring Batch jobs: `HARVEST_FILE`, `VALIDATE_EXTERNAL`, `TRANSFORM`, and `VALIDATE_INTERNAL`. Each job waited on the fixed 5-second polling interval in `BatchJobExecutor.waitForCompletion`, so a single `/record/validation` request took about 20-21 seconds even though the actual batch work completed much faster.

Observed local timings:

| Path | Timing |
| --- | ---: |
| Previous `/record/validation` runs | 21.29s, 20.29s, 20.27s |
| New direct validation, cold | 0.835s |
| New direct validation, warm | 0.028s |
| New direct validation, steady average | 0.007607s |
| New direct validation, steady min/max | 0.005541s / 0.025311s |

Using the previous average of about 20.62s, the direct validator is approximately 25x faster for the cold run, 736x faster for the warm run, and 2700x faster for the measured steady average.

## Requirements

* Java 21
* Maven 3.9+
* Docker, for the local stack and integration tests

## Build And Test

Initialize the pinned Metis Schema source before the first Maven or Docker build:

```bash
git submodule update --init --recursive
```

A fresh clone can use `git clone --recurse-submodules https://github.com/Digital-Humanities-Jena/local-edm-validator.git` instead.

Run unit and integration tests:

```bash
mvn clean verify
```

Skip integration tests:

```bash
mvn clean verify -DskipITs
```

Skip all tests:

```bash
mvn clean verify -DskipTests -DskipITs
```

## Local Docker Stack

Start the local application and dependencies from the repository root:

```bash
docker compose -f metis-sandbox-rest/docker-compose.local.yml up --build
```

The REST API is exposed at:

```text
http://localhost:18080
```

The local stack includes PostgreSQL, MongoDB, Solr, and MinIO. Local credentials in `metis-sandbox-rest/docker/local/application.yml` are placeholders for development only.

Useful local service ports:

* REST API: `18080`
* PostgreSQL: `15432`
* MongoDB: `37017`
* Solr: `18983`
* MinIO API: `19000`
* MinIO console: `19001`

## CLI

Build the REST module first:

```bash
mvn clean package -DskipTests -DskipITs
```

Start an interactive shell against the local Docker stack:

```bash
java -jar metis-sandbox-rest/target/metis-sandbox-rest-*.jar --cli --base-url http://localhost:18080
```

Run one-shot commands:

```bash
java -jar metis-sandbox-rest/target/metis-sandbox-rest-*.jar --cli --base-url http://localhost:18080 countries
java -jar metis-sandbox-rest/target/metis-sandbox-rest-*.jar --cli --base-url http://localhost:18080 languages
java -jar metis-sandbox-rest/target/metis-sandbox-rest-*.jar --cli --base-url http://localhost:18080 validate path/to/record.xml --country Europe --language "Multilingual Content"
java -jar metis-sandbox-rest/target/metis-sandbox-rest-*.jar --cli --base-url http://localhost:18080 validate-batch path/to/record-1.xml path/to/record-2.xml
java -jar metis-sandbox-rest/target/metis-sandbox-rest-*.jar --cli --base-url http://localhost:18080 progress <dataset-id>
```

Inside the interactive shell, type `help` to list all commands.

## REST API

Validate one record:

```text
POST /record/validation
```

Multipart fields:

* `recordToValidate`: XML file
* `country`: country value, defaults to `Europe` in the CLI
* `language`: language value, defaults to `Multilingual Content` in the CLI
* `continueOnError`: `true` or `false`

Validate multiple records:

```text
POST /record/validation/batch
```

Multipart fields:

* `recordsToValidate`: one or more XML files
* `country`: country value
* `language`: language value
* `continueOnError`: `true` or `false`

Lookup endpoints:

```text
GET /dataset/countries
GET /dataset/languages
GET /dataset/{id}/progress
GET /dataset/{id}/info
```

Refresh the default transformation XSLT from the configured source URL:

```text
POST /update/default-xslt
POST /update/default-xslt?url=<xslt-url>
```

Dataset harvest endpoints are also available:

```text
POST /dataset/{name}/harvestByFile
POST /dataset/{name}/harvestByUrl
POST /dataset/{name}/harvestOaiPmh
```

## Notes For Public Forks

This repository is a modified fork of `europeana/metis-sandbox`. It was modified by Digital Humanities Jena in 2026 for WebHDP-related METIS Sandbox operations and local EDM validation workflows.

Keep `LICENSE.md` and attribution notices intact when distributing modified versions.

Local validation input files should stay outside Git. The repository ignores `test_input/` for that purpose.

## Technologies

* Java 21
* Spring Boot
* Spring Batch
* PostgreSQL
* MongoDB
* Solr
* MinIO/S3-compatible storage
* Docker
* Testcontainers

## Funding

The first version of the Metis Sandbox was developed in 2020 as an MVP for the Common Culture Generic Services project, originally proposed as a Feedback Loop pilot. The Feedback Loop software was financed by Deutsche Digitale Bibliothek - Kultur und Wissen online (German Digital Library).
