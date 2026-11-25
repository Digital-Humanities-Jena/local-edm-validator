# Local EDM Validator Documentation

Local EDM Validator is a fork of METIS Sandbox tailored for WebHDP workflows. It keeps the existing sandbox dataset processing model and adds a fast path for validating individual EDM RDF/XML records without creating a Spring Batch dataset job for every request.

## What The Application Provides

* A REST service for EDM record validation and sandbox dataset operations.
* A command-line client built into the REST module jar.
* Direct single-record validation through `POST /record/validation`.
* Direct multi-record validation through `POST /record/validation/batch`.
* Optional dataset harvest workflows inherited from METIS Sandbox.
* A Docker Compose stack with PostgreSQL, MongoDB, Solr, MinIO, and the REST service.

## Runtime Requirements

* Java 21
* Maven 3.9 or newer
* Docker and Docker Compose

The local Docker stack is the recommended way to run the application because the REST service expects PostgreSQL, MongoDB, Solr, and S3-compatible storage.

## Build

Initialize the Git submodule from the repository root, then build the validator:

```bash
git submodule update --init --recursive
```

The submodule pins [Metis Schema v15](https://github.com/europeana/metis-schema/tree/a1201cd7e6be618bec67f8258348d8bddb93059b). Maven builds the JiBX RDF/XML bindings and packages the schema files as part of the validator build.

```bash
mvn clean package -DskipTests -DskipITs
```

Run unit tests and package the application:

```bash
mvn clean verify -DskipITs
```

Run the full test suite, including integration tests:

```bash
mvn clean verify
```

Integration tests require Docker because the project uses Testcontainers.

## Run Locally With Docker Compose

Start the stack from the repository root:

```bash
docker compose -f metis-sandbox-rest/docker-compose.local.yml up --build
```

The REST API is available at:

```text
http://localhost:18080
```

Check application health and schema information:

```bash
curl http://localhost:18080/actuator/health
curl http://localhost:18080/actuator/info
```

Stop the stack:

```bash
docker compose -f metis-sandbox-rest/docker-compose.local.yml down
```

Local ports:

| Service | Port |
| --- | ---: |
| REST API | 18080 |
| PostgreSQL | 15432 |
| MongoDB | 37017 |
| Solr | 18983 |
| MinIO API | 19000 |
| MinIO console | 19001 |

## Command-Line Client

The CLI is part of the REST module jar. It talks to a running REST API.

Build the jar:

```bash
mvn clean package -DskipTests -DskipITs
```

Start an interactive shell:

```bash
java -jar metis-sandbox-rest/target/metis-sandbox-rest-*.jar --cli --base-url http://localhost:18080
```

Run one command and exit:

```bash
java -jar metis-sandbox-rest/target/metis-sandbox-rest-*.jar --cli --base-url http://localhost:18080 countries
java -jar metis-sandbox-rest/target/metis-sandbox-rest-*.jar --cli --base-url http://localhost:18080 languages
```

Validate one record:

```bash
java -jar metis-sandbox-rest/target/metis-sandbox-rest-*.jar --cli \
  --base-url http://localhost:18080 \
  validate path/to/record.xml \
  --country Europe \
  --language "Multilingual Content"
```

Validate several records:

```bash
java -jar metis-sandbox-rest/target/metis-sandbox-rest-*.jar --cli \
  --base-url http://localhost:18080 \
  validate-batch path/to/record-1.xml path/to/record-2.xml
```

Continue diagnostic steps after a failed validation step:

```bash
java -jar metis-sandbox-rest/target/metis-sandbox-rest-*.jar --cli \
  --base-url http://localhost:18080 \
  validate path/to/record.xml \
  --continue-on-error
```

Inside the interactive shell, run:

```text
help
```

## REST API

### Validate One Record

```bash
curl -sS -X POST http://localhost:18080/record/validation \
  -F "recordToValidate=@path/to/record.xml;type=application/xml" \
  -F "country=Europe" \
  -F "language=Multilingual Content" \
  -F "continueOnError=false"
```

The response is a `ValidationWorkflowReport` with:

* `validationResults`: validation status and messages per workflow step.
* `problemPatternList`: detected problem patterns for the transformed record.
* `stepTimings`: direct validator timing per step, in milliseconds.
* `totalDurationMillis`: total direct validation duration.

### Validate Multiple Records

```bash
curl -sS -X POST http://localhost:18080/record/validation/batch \
  -F "recordsToValidate=@path/to/record-1.xml;type=application/xml" \
  -F "recordsToValidate=@path/to/record-2.xml;type=application/xml" \
  -F "country=Europe" \
  -F "language=Multilingual Content" \
  -F "continueOnError=false"
```

The response is a list of reports. Each item contains the submitted file name and the corresponding `ValidationWorkflowReport`.

### Lookups

```bash
curl -sS http://localhost:18080/dataset/countries
curl -sS http://localhost:18080/dataset/languages
```

### Dataset Status

```bash
curl -sS http://localhost:18080/dataset/<dataset-id>/progress
curl -sS http://localhost:18080/dataset/<dataset-id>/info
```

## Direct Validation Workflow

Direct validation follows the same logical record workflow as the old direct validation endpoint, but executes it in-process instead of creating and polling Spring Batch jobs.

The steps are:

| Step | Purpose |
| --- | --- |
| `HARVEST_FILE` | Marks the submitted XML as the input record. |
| `VALIDATE_EXTERNAL` | Sorts and validates the record against `EDM-EXTERNAL`. |
| `TRANSFORM_INTERNAL` | Applies the configured default transform with the selected country and language. |
| `VALIDATE_INTERNAL` | Validates the transformed record against `EDM-INTERNAL`. |
| `PROBLEM_PATTERNS` | Runs pattern analysis on the transformed record. |

By default, validation stops after the first failed validation step. Use `continueOnError=true` or `--continue-on-error` to collect later diagnostic information where possible.

## Configuration

Local runtime configuration lives in:

```text
metis-sandbox-rest/docker/local/application.yml
```

Important settings:

| Setting | Purpose |
| --- | --- |
| `sandbox.validation.direct.enabled` | Enables direct validation. Batch validation requires this to be `true`. |
| `sandbox.validation.edm-sorter-url` | XSLT used to sort EDM record content before external validation. |
| `sandbox.validation.predefinedSchemas` | External and internal EDM schema definitions. |
| `sandbox.transformation.xslt-url` | Default transformation XSLT lookup endpoint. |
| `sandbox.enrichment.enabled` | Keeps enrichment disabled in the local stack. |
| `sandbox.batch-job-executor.job-state-poll-interval-millis` | Polling interval for remaining batch-backed workflows. |

The local configuration uses Europeana’s July 20, 2026 EDM archive for external and internal XSD and Schematron validation. It uses the June 30, 2026 sorter and Metis Framework v18 default transformation. [Europeana schema archive](http://ftp.eanadev.org/schema_zips/) · [Metis Framework v18](https://github.com/europeana/metis-framework/releases/tag/v18)

`GET /actuator/info` reports the configured archive date under `app.edm-schema-version` and the packaged Metis Schema dependency under `build.metis-schema-version`. When replacing the runtime configuration, set `info.app.edm-schema-version` to the date of the archive it uses.

The local credentials in `application.yml` and `docker-compose.local.yml` are development placeholders.

## Local Input Files

Keep ad hoc validation files under `test_input/` if you want a local working directory for XML samples. The directory is ignored by Git.

## Troubleshooting

### The REST Service Does Not Become Healthy

Check running containers:

```bash
docker compose -f metis-sandbox-rest/docker-compose.local.yml ps
```

Check application logs:

```bash
docker logs metis-sandbox-local
```

### Validation Rejects A File Immediately

The validation endpoints expect uploaded files to have an XML content type. With `curl`, include `type=application/xml` in the file part.

### The CLI Cannot Reach The API

Check the base URL:

```bash
java -jar metis-sandbox-rest/target/metis-sandbox-rest-*.jar --cli base-url
```

For the Docker stack, use:

```text
http://localhost:18080
```

### A Full Build Is Slow On A New Machine

The first Maven run resolves dependencies from Maven Central and Europeana Artifactory. If Artifactory is unavailable, use an existing Maven cache with the dependencies already resolved. A fully populated cache supports `mvn -o clean verify -DskipITs`. The local Dockerfile reuses its Maven cache across builds.

## Publishing Notes

This repository is a modified fork of `europeana/metis-sandbox`. Keep `LICENSE.md` and attribution notices intact when distributing modified versions.

Before publishing local changes:

```bash
git status --short --branch
git diff --check
mvn clean verify -DskipITs
```
