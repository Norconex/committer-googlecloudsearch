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
	<titleField>title</titleField>
	<objectTypeField>document.type</objectTypeField>
	<updateTimeField>Last-Modified</updateTimeField>
	<containerNameField>parentTitle</containerNameField>
	<contentLanguageField>document.language</contentLanguageField>
	<sourceRepositoryUrlField>sourceUrl</sourceRepositoryUrlField>

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

### Option Reference

| Element | Required | Default | Description |
| --- | --- | --- | --- |
| `secretKeyPath` | Yes | None | Absolute or relative path to the Google service account JSON key file used to authenticate indexing requests. |
| `dataSourceId` | Yes | None | Google Cloud Search data source ID. Item names are built as `datasources/{dataSourceId}/items/{itemId}`. |
| `uploadFormat` | No | `raw` | Controls how content is sent to Google Cloud Search. Use `raw` to index binary/original content, or `text` to index the committer request text content. |
| `apiEndpoint` | No | Google production endpoint | Overrides the Google Cloud Search root URL. Use this for local WireMock or other mock endpoints. A trailing slash is optional. |
| `applicationName` | No | `Norconex Google Cloud Search Committer` | Value passed to the Google client as the application name. Useful for logs, monitoring, and distinguishing this connector in HTTP client metadata. |
| `connectorName` | No | Same as `applicationName` | Value sent in Google indexing requests as `connectorName`. Set this when your Cloud Search connector identity must differ from the application name. |
| `sourceIdField` | No | Document reference | Metadata field to use as the source item ID instead of the Norconex request reference. The chosen value is encoded into a safe Google item ID. |
| `keepSourceIdField` | No | `false` | Whether the metadata field referenced by `sourceIdField` should remain in document metadata after it has been used as the item ID source. |
| `titleField` | No | `title` | Metadata field mapped to Google `ItemMetadata.title`. |
| `objectTypeField` | No | `objectType` | Metadata field mapped to Google `ItemMetadata.objectType`. If the field is absent or blank, the committer uses `document`. |
| `updateTimeField` | No | `Last-Modified` | Metadata field mapped to Google `ItemMetadata.updateTime`. Supported formats include ISO-8601 instants and offsets, RFC-1123 dates, and local date-times interpreted as UTC. Unparseable values are ignored. |
| `containerNameField` | No | None | Metadata field mapped to Google `ItemMetadata.containerName`. Useful for parent labels or logical folder/container names. |
| `contentLanguageField` | No | None | Metadata field mapped to Google `ItemMetadata.contentLanguage`. |
| `sourceRepositoryUrlField` | No | Norconex document reference | Metadata field mapped to Google `ItemMetadata.sourceRepositoryUrl`. If omitted, the committer uses the Norconex request reference. |

## ACL Configuration

The optional `acl` section lets you map crawled metadata fields to Google Cloud
Search ACL fields.

### ACL Mapping Elements

`mapping` attributes:

| Attribute | Required | Values | Description |
| --- | --- | --- | --- |
| `fromField` | Yes | Any metadata field name | Metadata field whose values will be converted to Google principals. |
| `target` | Yes | `readers`, `deniedReaders`, `owners` | Google ACL target list to populate. |
| `principalType` | Yes | `user`, `group`, `customer` | Principal conversion mode. `user` maps values to Google Workspace user emails, `group` maps values to Google Workspace group emails, and `customer` grants the entire Google Workspace customer domain. |

### ACL Inheritance Element

`inherit` attributes:

| Attribute | Required | Values | Description |
| --- | --- | --- | --- |
| `fromField` | Yes | Any metadata field name | Metadata field containing the parent item identifier. The value is converted into the same encoded item ID format used for normal documents. |
| `aclInheritanceType` | Yes | `NOT_APPLICABLE`, `CHILD_OVERRIDE`, `PARENT_OVERRIDE`, `BOTH_PERMIT` | Google Cloud Search ACL inheritance behavior for the `inheritAclFrom` relationship. |

### ACL Notes

- ACL fields used by `mapping` and `inherit` are excluded from structured data.
- Blank ACL values are ignored.
- If no ACL mapping produces any principals and no inheritance is configured,
	no ACL block is sent for the item.

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

All metadata fields not reserved by this committer are sent as Google
structured text values.

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