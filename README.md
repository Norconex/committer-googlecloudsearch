# Norconex Committer Google Cloud Search

Google Cloud Search implementation of Norconex Committer built on top of
Norconex Committer Core 3.x.

This committer uses the official generated Google Cloud Search Java client,
supports the Norconex batch committer lifecycle, and can be pointed at a local
mock endpoint for development and tests.

## What It Does

- Upserts Norconex documents into Google Cloud Search.
- Deletes Google Cloud Search items using the Norconex document reference or a
  mapped source ID.
- Supports `raw` and `text` upload modes.
- Maps Norconex metadata to Google `ItemMetadata`, `ItemStructuredData`, and
  optional ACLs.
- Supports local integration testing with WireMock through `apiEndpoint`.

## Requirements

- Java compatible with this module build.
- A Google service account JSON key file with Cloud Search indexing access.
- A Google Cloud Search data source ID.

## Inherited Core Configuration

This committer inherits the common Norconex Committer Core configuration from
`AbstractCommitter` and `AbstractBatchCommitter`, notably:

- `restrictTo`
- `fieldMappings`
- `queue`

See the committer-core documentation for those shared options. This README only
documents the configuration that is specific to this Google Cloud Search
committer.

## Committer-Specific Configuration

### Minimal Example

```xml
<committer class="com.norconex.committer.googlecloudsearch.GoogleCloudSearchCommitter">
	<secretKeyPath>/path/to/google-service-account.json</secretKeyPath>
	<dataSourceId>your-datasource-id</dataSourceId>
</committer>
```

### Full Example

```xml
<committer class="com.norconex.committer.googlecloudsearch.GoogleCloudSearchCommitter">
	<secretKeyPath>/path/to/google-service-account.json</secretKeyPath>
	<dataSourceId>your-datasource-id</dataSourceId>
	<uploadFormat>raw</uploadFormat>
	<apiEndpoint>http://localhost:8080/</apiEndpoint>
	<applicationName>Norconex Google Cloud Search Committer</applicationName>
	<connectorName>Norconex GCS Connector</connectorName>
	<sourceIdField>document.reference</sourceIdField>
	<keepSourceIdField>false</keepSourceIdField>
	<httpConnectTimeoutMillis>30000</httpConnectTimeoutMillis>
	<httpReadTimeoutMillis>120000</httpReadTimeoutMillis>
	<httpMaxRetries>3</httpMaxRetries>
	<httpBackoffInitialIntervalMillis>500</httpBackoffInitialIntervalMillis>
	<httpBackoffMaxIntervalMillis>60000</httpBackoffMaxIntervalMillis>
	<httpBackoffMaxElapsedTimeMillis>900000</httpBackoffMaxElapsedTimeMillis>
	<metadata>
		<mapping fromField="title" toField="title"/>
		<mapping fromField="document.type" toField="objectType" defaultValue="webpage"/>
		<mapping fromField="Last-Modified" toField="updateTime"/>
		<mapping fromField="created" toField="createTime"/>
		<mapping fromField="parentTitle" toField="containerName"/>
		<mapping fromField="document.language" toField="contentLanguage" defaultValue="en-US"/>
		<mapping fromField="sourceUrl" toField="sourceRepositoryUrl"/>
		<mapping fromField="checksum" toField="hash"/>
		<mapping fromField="tags" toField="keywords"/>
		<mapping fromField="quality" toField="searchQualityMetadata" defaultValue="0.0"/>
	</metadata>

	<structuredData>
		<mapping field="rating" type="double"/>
		<mapping field="viewCount" type="integer"/>
	</structuredData>

	<acl>
		<mapping fromField="acl.reader.user" target="readers" principalType="user"/>
		<mapping fromField="acl.reader.group" target="readers" principalType="group"/>
		<mapping fromField="acl.denied.user" target="deniedReaders" principalType="user"/>
		<mapping fromField="acl.owner" target="owners" principalType="user"/>
		<mapping fromField="acl.customer" target="readers" principalType="customer"/>
		<inherit fromField="parentReference" aclInheritanceType="BOTH_PERMIT"/>
	</acl>

	<!-- Inherited batch queue options also apply here. -->
</committer>
```

### Google HTTP Resilience Options (Extra to Core)

These options are **Google Cloud Search committer-specific** and are not part
of the generic options every Norconex committer inherits from
`AbstractCommitter` / `AbstractBatchCommitter`.

Use `-1` to keep Google HTTP client defaults for an option.

| Element                            | Required | Default | Description                                                                                                               |
| ---------------------------------- | -------- | ------- | ------------------------------------------------------------------------------------------------------------------------- |
| `httpConnectTimeoutMillis`         | No       | `-1`    | HTTP connect timeout applied to Google API requests.                                                                      |
| `httpReadTimeoutMillis`            | No       | `-1`    | HTTP read timeout applied to Google API requests.                                                                         |
| `httpMaxRetries`                   | No       | `-1`    | Per-request retry count on the Google HTTP client.                                                                        |
| `httpBackoffInitialIntervalMillis` | No       | `-1`    | Enables exponential backoff when set (or when either backoff max value is set) and configures the initial retry interval. |
| `httpBackoffMaxIntervalMillis`     | No       | `-1`    | Maximum per-step interval used by exponential backoff handlers.                                                           |
| `httpBackoffMaxElapsedTimeMillis`  | No       | `-1`    | Maximum total elapsed time allowed for exponential backoff retries.                                                       |

When any backoff option is configured, the committer installs Google HTTP
client backoff handlers for both IO failures and unsuccessful HTTP responses
(`5xx` retries).

### Option Reference

| Element                            | Required | Default                                  | Description                                                                                                                                                                                                                                                                                                                                                                                       |
| ---------------------------------- | -------- | ---------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `secretKeyPath`                    | Yes      | None                                     | Absolute or relative path to the Google service account JSON key file used to authenticate indexing requests.                                                                                                                                                                                                                                                                                     |
| `dataSourceId`                     | Yes      | None                                     | Google Cloud Search data source ID. Item names are built as `datasources/{dataSourceId}/items/{itemId}`.                                                                                                                                                                                                                                                                                          |
| `uploadFormat`                     | No       | `raw`                                    | Controls how content is sent to Google Cloud Search. Use `raw` to index binary/original content, or `text` to index the committer request text content.                                                                                                                                                                                                                                           |
| `apiEndpoint`                      | No       | Google production endpoint               | Overrides the Google Cloud Search root URL. Use this for local WireMock or other mock endpoints. A trailing slash is optional.                                                                                                                                                                                                                                                                    |
| `applicationName`                  | No       | `Norconex Google Cloud Search Committer` | Value passed to the Google client as the application name. Useful for logs, monitoring, and distinguishing this connector in HTTP client metadata.                                                                                                                                                                                                                                                |
| `connectorName`                    | No       | Same as `applicationName`                | Value sent in Google indexing requests as `connectorName`. Set this when your Cloud Search connector identity must differ from the application name.                                                                                                                                                                                                                                              |
| `sourceIdField`                    | No       | Document reference                       | Metadata field to use as the source item ID instead of the Norconex request reference. The chosen value is encoded into a safe Google item ID.                                                                                                                                                                                                                                                    |
| `keepSourceIdField`                | No       | `false`                                  | Whether the metadata field referenced by `sourceIdField` should remain in document metadata after it has been used as the item ID source.                                                                                                                                                                                                                                                         |
| `httpConnectTimeoutMillis`         | No       | `-1`                                     | Google-specific HTTP connect timeout in milliseconds (`-1` keeps Google client defaults).                                                                                                                                                                                                                                                                                                         |
| `httpReadTimeoutMillis`            | No       | `-1`                                     | Google-specific HTTP read timeout in milliseconds (`-1` keeps Google client defaults).                                                                                                                                                                                                                                                                                                            |
| `httpMaxRetries`                   | No       | `-1`                                     | Google-specific HTTP retry count (`-1` keeps Google client defaults).                                                                                                                                                                                                                                                                                                                             |
| `httpBackoffInitialIntervalMillis` | No       | `-1`                                     | Google-specific exponential backoff initial interval in milliseconds; setting this (or either backoff max option) enables HTTP backoff handlers.                                                                                                                                                                                                                                                  |
| `httpBackoffMaxIntervalMillis`     | No       | `-1`                                     | Google-specific exponential backoff maximum interval in milliseconds.                                                                                                                                                                                                                                                                                                                             |
| `httpBackoffMaxElapsedTimeMillis`  | No       | `-1`                                     | Google-specific exponential backoff maximum elapsed time in milliseconds.                                                                                                                                                                                                                                                                                                                         |
| `metadata`                         | No       | Built-in defaults                        | Mapping section for Google predefined metadata fields. Each `mapping` supports `fromField` (optional), `toField` (required), `defaultValue` (optional), and `keepFromField` (optional). Supported `toField` values are `title`, `objectType`, `mimeType`, `updateTime`, `createTime`, `containerName`, `contentLanguage`, `sourceRepositoryUrl`, `hash`, `keywords`, and `searchQualityMetadata`. |
| `structuredData`                   | No       | All fields sent as `text`                | Mapping section declaring the Google-native value type for individual structured data fields. See [Structured Data Mapping](#structured-data-mapping) below.                                                                                                                                                                                                                                      |

Google reference for predefined metadata fields:
https://developers.google.com/workspace/cloud-search/docs/reference/rest/v1/indexing.datasources.items#ItemMetadata

Two `ItemMetadata` fields are intentionally **not** supported by a simple
mapping: `contextAttributes` and `interactions`. Both are lists of nested
objects (a ranking-context attribute, and a user-interaction record with a
principal and timestamp) rather than a single value derived from one crawled
field, so they don't fit this committer's flat `fromField`/`toField` model.

## ACL Configuration

The optional `acl` section lets you map crawled metadata fields to Google Cloud
Search ACL fields.

### ACL Mapping Elements

`mapping` attributes:

| Attribute       | Required | Values                               | Description                                                                                                                                                                                             |
| --------------- | -------- | ------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `fromField`     | Yes      | Any metadata field name              | Metadata field whose values will be converted to Google principals.                                                                                                                                     |
| `target`        | Yes      | `readers`, `deniedReaders`, `owners` | Google ACL target list to populate.                                                                                                                                                                     |
| `principalType` | Yes      | `user`, `group`, `customer`          | Principal conversion mode. `user` maps values to Google Workspace user emails, `group` maps values to Google Workspace group emails, and `customer` grants the entire Google Workspace customer domain. |

### ACL Inheritance Element

`inherit` attributes:

| Attribute            | Required | Values                                                               | Description                                                                                                                                  |
| -------------------- | -------- | -------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------- |
| `fromField`          | Yes      | Any metadata field name                                              | Metadata field containing the parent item identifier. The value is converted into the same encoded item ID format used for normal documents. |
| `aclInheritanceType` | Yes      | `NOT_APPLICABLE`, `CHILD_OVERRIDE`, `PARENT_OVERRIDE`, `BOTH_PERMIT` | Google Cloud Search ACL inheritance behavior for the `inheritAclFrom` relationship.                                                          |

### ACL Notes

- ACL fields used by `mapping` and `inherit` are excluded from structured data.
- Blank ACL values are ignored.
- If no ACL mapping produces any principals and no inheritance is configured,
  the committer defaults to a customer-domain reader principal so the item
  remains accessible and satisfies Cloud Search ACL requirements.

## Content Upload Behavior

### `uploadFormat=raw`

- Intended for original/binary content.
- Reads the `binaryContent` metadata field populated by
  `BinaryContentTagger`.
- If `binaryContent` is missing, the committer falls back to the request
  content stream and logs a warning.
- Uses Google upload-session plus media-upload endpoints.

When using `raw`, add `BinaryContentTagger` in a pre-parse handler chain:

```xml
<tagger class="com.norconex.committer.googlecloudsearch.BinaryContentTagger"/>
```

### `uploadFormat=text`

- Uses the Norconex request content as UTF-8 text.
- Inlines content up to 100 KiB directly in the indexing request.
- Uses the Google media upload flow automatically for larger text payloads.

## Structured Data Mapping

All metadata fields not reserved by this committer (see `metadata` and the
exclusions below) are sent as Google structured data properties, using the
field's own name as the property name.

By default, every structured data field is sent as `text`, since that's the
only type guaranteed to be accepted regardless of how the property is
declared in your Cloud Search data source schema. To send a field using a
more specific Google-native type, declare it explicitly in `structuredData`:

```xml
<structuredData>
	<mapping field="rating" type="double"/>
	<mapping field="viewCount" type="integer"/>
	<mapping field="publishDate" type="date"/>
	<mapping field="lastCrawled" type="timestamp"/>
	<mapping field="isFeatured" type="boolean"/>
	<mapping field="status" type="enum"/>
	<mapping field="rawHtmlSnippet" type="html"/>
</structuredData>
```

`mapping` attributes: `field` (required, the metadata/structured-data field
name) and `type` (optional, defaults to `text`).

### Supported `type` values

Each corresponds to one Google Cloud Search structured data value type
(`NamedProperty` in the API). Match this to whatever the property is
actually declared as in your Cloud Search data source schema
(`textPropertyOptions`, `datePropertyOptions`, etc.):

| `type`      | Google API field  | Expected value format                                                           | Matching schema property option |
| ----------- | ----------------- | ------------------------------------------------------------------------------- | ------------------------------- |
| `text`      | `textValues`      | Any string.                                                                     | `textPropertyOptions`           |
| `integer`   | `integerValues`   | A whole number, e.g. `123`.                                                     | `integerPropertyOptions`        |
| `double`    | `doubleValues`    | A decimal number, e.g. `1.5`.                                                   | `doublePropertyOptions`         |
| `date`      | `dateValues`      | A calendar date with no time component, ISO-8601: `2026-07-14`.                 | `datePropertyOptions`           |
| `timestamp` | `timestampValues` | A specific point in time (date **and** time), RFC-3339: `2026-07-14T12:30:00Z`. | `timestampPropertyOptions`      |
| `boolean`   | `booleanValue`    | `true` or `false`.                                                              | `booleanPropertyOptions`        |
| `enum`      | `enumValues`      | Any string, but see the 32-value cap below.                                     | `enumPropertyOptions`           |
| `html`      | `htmlValues`      | A string containing HTML markup.                                                | `htmlPropertyOptions`           |

If you're unsure whether a date-like field should be `date` or `timestamp`:
use `date` when the field is a calendar day with no meaningful time-of-day
(e.g., a publish date), and `timestamp` when the time actually matters (e.g.,
a last-crawled instant). Sending a full timestamp string as `type="date"` (or
vice-versa) will not parse and falls back to `text` with a warning rather
than failing the batch — see below.

### Multi-valued (repeatable) fields

If your Cloud Search schema declares a property as repeatable
(`isRepeatable: true`), no special configuration is needed on this
committer's side — every metadata field is already a list of values
internally, and all of the types above except `boolean` accept a list
natively (`textValues`, `integerValues`, etc. are all repeated fields in
Google's API). Just map the field's `type` as usual and every value
collected for that field on the document gets sent.

`boolean` is the one exception: Google models `booleanValue` as a single
scalar, not a repeatable list (Cloud Search does not support repeatable
boolean properties), so only the _first_ value collected for a field mapped
as `type="boolean"` is sent; if your schema declares a boolean property as
repeatable, only its first value will ever reach Cloud Search this way — use
`enum` instead if you truly need a repeatable true/false-like field with
more than one value.

**Important:** the declared `type` must match what your Cloud Search data
source schema actually registers for that property name — this committer
has no way to inspect your schema and verify it for you. Getting this wrong
is a common source of indexing failures, and one type deserves a specific
warning: Google Cloud Search caps `enum` properties at **32 values per
item**. A repeatable field (e.g., tags or keywords) that ends up with more
than 32 values for a document will fail the whole batch if mapped as `enum`
— to guard against that, this committer falls back to `text` and logs a
warning instead of failing outright, but the safest choice remains: only use
`type="enum"` for fields your schema actually declares as an enum, and leave
everything else as `text` (integer/double/date/timestamp are comparatively
safe since there's no ambiguity in what a correctly-formatted value like
`2026-07-14` or `123` represents).

Values that don't parse as the declared type (e.g., `type="integer"` on a
field containing non-numeric text) are logged as a warning and sent as
`text` instead, rather than failing the batch.

Excluded from structured data:

- `binaryContent`
- `sourceIdField`, when configured
- Any ACL `mapping` source fields
- The ACL `inherit` source field

This means you can usually forward most Norconex metadata to Google Cloud
Search without additional mapping code.

## Local Mock Testing With WireMock

Google does not provide a local Cloud Search emulator. This committer is
designed so you can test it against a mock server instead.

Use `apiEndpoint` to point the committer at a local WireMock instance, then
stub these endpoints:

- OAuth token exchange: `/oauth2/v4/token`
- Upload session creation:
  `/v1/indexing/datasources/{dataSourceId}/items/{itemId}:upload`
- Media upload: `/upload/v1/media/{uploadItemRef}`
- Batch indexing and delete execution: `/batch`

This project already includes tests covering that flow with WireMock and mock
HTTP transport.

## Validation

Module validation command:

```powershell
mvn --% -Dgpg.skip=true -DskipITs clean test
```

## Notes

- Item IDs are URL-safe Base64 encoded from the chosen source ID. Very large
  IDs are reduced to a SHA-256 form to stay within Cloud Search limits.
- Deletes use the same ID derivation logic as upserts, so `sourceIdField`
  should remain stable across crawl runs.
