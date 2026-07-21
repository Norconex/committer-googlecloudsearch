/*
 * Copyright © 2023 Norconex Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.norconex.committer.googlecloudsearch;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpHeaders;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import javax.print.attribute.standard.Media;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.api.client.googleapis.batch.BatchRequest;
import com.google.api.client.googleapis.batch.json.JsonBatchCallback;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.googleapis.json.GoogleJsonError;
import com.google.api.client.http.ByteArrayContent;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.cloudsearch.v1.CloudSearch;
import com.google.api.services.cloudsearch.v1.model.DateValues;
import com.google.api.services.cloudsearch.v1.model.DoubleValues;
import com.google.api.services.cloudsearch.v1.model.EnumValues;
import com.google.api.services.cloudsearch.v1.model.GSuitePrincipal;
import com.google.api.services.cloudsearch.v1.model.IndexItemRequest;
import com.google.api.services.cloudsearch.v1.model.IntegerValues;
import com.google.api.services.cloudsearch.v1.model.Item;
import com.google.api.services.cloudsearch.v1.model.ItemAcl;
import com.google.api.services.cloudsearch.v1.model.ItemContent;
import com.google.api.services.cloudsearch.v1.model.ItemMetadata;
import com.google.api.services.cloudsearch.v1.model.ItemStructuredData;
import com.google.api.services.cloudsearch.v1.model.NamedProperty;
import com.google.api.services.cloudsearch.v1.model.Operation;
import com.google.api.services.cloudsearch.v1.model.StartUploadItemRequest;
import com.google.api.services.cloudsearch.v1.model.StructuredDataObject;
import com.google.api.services.cloudsearch.v1.model.TextValues;
import com.google.api.services.cloudsearch.v1.model.TimestampValues;
import com.google.api.services.cloudsearch.v1.model.UploadItemRef;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.norconex.committer.core3.CommitterException;
import com.norconex.committer.core3.CommitterUtil;
import com.norconex.committer.core3.DeleteRequest;
import com.norconex.committer.core3.ICommitterRequest;
import com.norconex.committer.core3.UpsertRequest;
import com.norconex.committer.core3.batch.AbstractBatchCommitter;
import com.norconex.commons.lang.xml.XML;

/**
 * <p>
 * Commits documents to Google Cloud Search using the official generated Google
 * API client. The client root URL is configurable, which makes it possible to
 * run the committer entirely against a local mock server during development and
 * tests.
 * </p>
 *
 * <p>
 * Raw uploads use the original binary content captured by
 * {@link BinaryContentTagger}. Text uploads use the committer request content.
 * Index and delete operations are grouped through Google's batch request API.
 * </p>
 *
 * <p>
 * Cloud Search requires every indexed item to carry an ACL. When no
 * {@code <acl>} mapping resolves to a reader for a given document (or none is
 * configured at all) and no ACL is being inherited, the item defaults to being
 * readable by the entire Google Workspace domain.
 * </p>
 *
 * {@nx.include com.norconex.committer.core3.AbstractCommitter#restrictTo}
 *
 * {@nx.include com.norconex.committer.core3.AbstractCommitter#fieldMappings}
 *
 * <p>
 * Complete configuration example showing all Google Cloud Search-specific
 * options supported by this committer:
 * </p>
 *
 * {@nx.xml.usage
 * <committer
 * class="com.norconex.committer.googlecloudsearch.GoogleCloudSearchCommitter">
 * <secretKeyPath>/path/to/google-service-account.json</secretKeyPath>
 * <dataSourceId>your-datasource-id</dataSourceId>
 * <applicationName>Norconex GCS Connector</applicationName>
 * <connectorName>norconex-gcs-connector</connectorName>
 * <uploadFormat>raw</uploadFormat>
 * <requestMode>asynchronous</requestMode>
 * <sourceIdField>document.reference</sourceIdField>
 * <keepSourceIdField>false</keepSourceIdField>
 * <typedStructuredData>true</typedStructuredData>
 * <apiEndpoint>http://localhost:8080/</apiEndpoint>
 *
 * <metadata>
 * <mapping fromField="title" toField="title"/>
 * <mapping fromField="objectType" toField="objectType"
 * defaultValue="document"/>
 * <mapping fromField="Last-Modified" toField="updateTime"/>
 * <mapping fromField="collection" toField="containerName"/>
 * <mapping fromField="language" toField="contentLanguage"/>
 * <mapping fromField="origin.url" toField="sourceRepositoryUrl"
 * keepFromField="true"/>
 * </metadata>
 *
 * <acl>
 * <mapping fromField="acl.reader.user" target="readers" principalType="user"/>
 * <mapping fromField="acl.reader.group" target="readers" principalType=
 * "group"/>
 * <mapping fromField="acl.owner" target="owners" principalType="user"/>
 * <inherit fromField="parentReference" aclInheritanceType="BOTH_PERMIT"/>
 * </acl>
 *
 * {@nx.include
 * com.norconex.committer.core3.batch.AbstractBatchCommitter#options}
 * </committer>
 * }
 *
 * <p>
 * Typical setup example for most crawling projects:
 * </p>
 *
 * {@nx.xml.example
 * <committer
 * class="com.norconex.committer.googlecloudsearch.GoogleCloudSearchCommitter">
 * <secretKeyPath>/secrets/gcs-service-account.json</secretKeyPath>
 * <dataSourceId>my-datasource</dataSourceId>
 * <connectorName>website-crawler</connectorName>
 * <uploadFormat>text</uploadFormat>
 *
 * <metadata>
 * <mapping fromField="title" toField="title"/>
 * <mapping fromField="Last-Modified" toField="updateTime"/>
 * </metadata>
 *
 * <acl>
 * <mapping fromField="acl.reader.group" target="readers" principalType="group"/>
 * </acl>
 * </committer>
 * }
 */
@SuppressWarnings("javadoc")
public class GoogleCloudSearchCommitter extends AbstractBatchCommitter {

    private static final Logger log = LoggerFactory.getLogger(
            GoogleCloudSearchCommitter.class);

    static final String FIELD_BINARY_CONTENT = "binaryContent";
    static final String FIELD_CONTENT_TYPE = "document.contentType";

    static final String CONFIG_SECRET_KEY_PATH = "secretKeyPath";
    static final String CONFIG_DATA_SOURCE_ID = "dataSourceId";
    static final String CONFIG_UPLOAD_FORMAT = "uploadFormat";
    static final String CONFIG_REQUEST_MODE = "requestMode";
    static final String CONFIG_API_ENDPOINT = "apiEndpoint";
    static final String CONFIG_APPLICATION_NAME = "applicationName";
    static final String CONFIG_CONNECTOR_NAME = "connectorName";
    static final String CONFIG_SOURCE_ID_FIELD = "sourceIdField";
    static final String CONFIG_KEEP_SOURCE_ID_FIELD = "keepSourceIdField";
    static final String CONFIG_METADATA = "metadata";
    static final String CONFIG_TYPED_STRUCTURED_DATA = "typedStructuredData";

    static final String DEFAULT_APPLICATION_NAME = "Norconex Google Cloud Search Committer";
    static final String DEFAULT_TITLE_SOURCE_FIELD = "title";
    static final String DEFAULT_OBJECT_TYPE_SOURCE_FIELD = "objectType";
    static final String DEFAULT_OBJECT_TYPE = "document";
    static final String DEFAULT_UPDATE_TIME_SOURCE_FIELD = "Last-Modified";
    static final String DEFAULT_TEXT_CONTENT_TYPE = "text/plain";
    static final String DEFAULT_BINARY_CONTENT_TYPE = "application/octet-stream";
    static final int INLINE_CONTENT_MAX_BYTES = 102400;

    private static final String INDEXING_SCOPE = "https://www.googleapis.com/auth/cloud_search.indexing";
    private static final String CONTENT_ITEM_TYPE = "CONTENT_ITEM";

    public enum UploadFormat {
        RAW,
        TEXT
    }

    public enum RequestMode {
        SYNCHRONOUS,
        ASYNCHRONOUS
    }

    public enum AclTarget {
        READERS("readers"),
        DENIED_READERS("deniedReaders"),
        OWNERS("owners");

        private final String xmlValue;

        AclTarget(String xmlValue) {
            this.xmlValue = xmlValue;
        }

        static AclTarget fromXmlValue(String value) {
            for (AclTarget target : values()) {
                if (target.xmlValue.equalsIgnoreCase(value)) {
                    return target;
                }
            }
            throw new IllegalArgumentException(
                    "Unsupported ACL target: " + value);
        }

        String getXmlValue() {
            return xmlValue;
        }
    }

    public enum PrincipalType {
        USER("user"),
        GROUP("group"),
        CUSTOMER("customer");

        private final String xmlValue;

        PrincipalType(String xmlValue) {
            this.xmlValue = xmlValue;
        }

        static PrincipalType fromXmlValue(String value) {
            for (PrincipalType principalType : values()) {
                if (principalType.xmlValue.equalsIgnoreCase(value)) {
                    return principalType;
                }
            }
            throw new IllegalArgumentException(
                    "Unsupported ACL principalType: " + value);
        }

        String getXmlValue() {
            return xmlValue;
        }
    }

    public enum AclInheritanceType {
        NOT_APPLICABLE,
        CHILD_OVERRIDE,
        PARENT_OVERRIDE,
        BOTH_PERMIT
    }

    /**
     * Supported Google Cloud Search item metadata targets.
     * See Google reference:
     * https://developers.google.com/workspace/cloud-search/docs/reference/rest/v1/ItemMetadata
     */
    public enum MetadataField {
        TITLE("title"),
        OBJECT_TYPE("objectType"),
        MIME_TYPE("mimeType"),
        UPDATE_TIME("updateTime"),
        CREATE_TIME("createTime"),
        CONTAINER_NAME("containerName"),
        CONTENT_LANGUAGE("contentLanguage"),
        SOURCE_REPOSITORY_URL("sourceRepositoryUrl");

        private final String fieldName;

        MetadataField(String fieldName) {
            this.fieldName = fieldName;
        }

        static MetadataField fromXmlValue(String value) {
            if (value == null) {
                return null;
            }
            for (MetadataField field : values()) {
                if (field.fieldName.equalsIgnoreCase(value)) {
                    return field;
                }
            }
            return null;
        }

        @Override
        public String toString() {
            return fieldName;
        }
    }

    private final Helper helper;
    private final List<AclMapping> aclMappings = new ArrayList<>();
    private final List<MetadataMapping> metadataMappings = new ArrayList<>();
    private final AtomicLong versionSequence = new AtomicLong();

    private String secretKeyPath;
    private String dataSourceId;
    private String apiEndpoint;
    private String applicationName = DEFAULT_APPLICATION_NAME;
    private String connectorName = DEFAULT_APPLICATION_NAME;
    private String sourceIdField;
    private boolean keepSourceIdField;
    private boolean typedStructuredData;
    private UploadFormat uploadFormat = UploadFormat.RAW;
    private RequestMode requestMode = RequestMode.ASYNCHRONOUS;
    private AclInheritanceMapping aclInheritance = new AclInheritanceMapping();

    private CloudSearch cloudSearch;

    public String getSecretKeyPath() {
        return secretKeyPath;
    }

    public GoogleCloudSearchCommitter setSecretKeyPath(String secretKeyPath) {
        this.secretKeyPath = secretKeyPath;
        return this;
    }

    public String getDataSourceId() {
        return dataSourceId;
    }

    public GoogleCloudSearchCommitter setDataSourceId(String dataSourceId) {
        this.dataSourceId = dataSourceId;
        return this;
    }

    public String getApiEndpoint() {
        return apiEndpoint;
    }

    public GoogleCloudSearchCommitter setApiEndpoint(String apiEndpoint) {
        this.apiEndpoint = apiEndpoint;
        return this;
    }

    public String getApplicationName() {
        return applicationName;
    }

    public GoogleCloudSearchCommitter setApplicationName(String applicationName) {
        this.applicationName = applicationName;
        return this;
    }

    public String getConnectorName() {
        return connectorName;
    }

    public GoogleCloudSearchCommitter setConnectorName(String connectorName) {
        this.connectorName = connectorName;
        return this;
    }

    public String getSourceIdField() {
        return sourceIdField;
    }

    public GoogleCloudSearchCommitter setSourceIdField(String sourceIdField) {
        this.sourceIdField = sourceIdField;
        return this;
    }

    public boolean isKeepSourceIdField() {
        return keepSourceIdField;
    }

    public GoogleCloudSearchCommitter setKeepSourceIdField(boolean keepSourceIdField) {
        this.keepSourceIdField = keepSourceIdField;
        return this;
    }

    public List<MetadataMapping> getMetadataMappings() {
        return Collections.unmodifiableList(metadataMappings);
    }

    public GoogleCloudSearchCommitter setMetadataMappings(
            List<MetadataMapping> metadataMappings) {
        this.metadataMappings.clear();
        if (metadataMappings != null) {
            this.metadataMappings.addAll(metadataMappings);
        }
        return this;
    }

    public boolean isTypedStructuredData() {
        return typedStructuredData;
    }

    public GoogleCloudSearchCommitter setTypedStructuredData(
            boolean typedStructuredData) {
        this.typedStructuredData = typedStructuredData;
        return this;
    }

    public UploadFormat getUploadFormat() {
        return uploadFormat;
    }

    public GoogleCloudSearchCommitter setUploadFormat(UploadFormat uploadFormat) {
        this.uploadFormat = uploadFormat;
        return this;
    }

    public RequestMode getRequestMode() {
        return requestMode;
    }

    public GoogleCloudSearchCommitter setRequestMode(RequestMode requestMode) {
        this.requestMode = requestMode;
        return this;
    }

    public List<AclMapping> getAclMappings() {
        return Collections.unmodifiableList(aclMappings);
    }

    public GoogleCloudSearchCommitter setAclMappings(List<AclMapping> aclMappings) {
        this.aclMappings.clear();
        if (aclMappings != null) {
            this.aclMappings.addAll(aclMappings);
        }
        return this;
    }

    public AclInheritanceMapping getAclInheritance() {
        return aclInheritance;
    }

    public GoogleCloudSearchCommitter setAclInheritance(
            AclInheritanceMapping aclInheritance) {
        this.aclInheritance = aclInheritance != null
                ? aclInheritance
                : new AclInheritanceMapping();
        return this;
    }

    public GoogleCloudSearchCommitter() {
        this(new Helper());
    }

    GoogleCloudSearchCommitter(Helper helper) {
        this.helper = helper;
    }

    @Override
    protected void initBatchCommitter() throws CommitterException {
        validateConfiguration();
        try {
            cloudSearch = helper.createCloudSearch(
                    applicationName, secretKeyPath, apiEndpoint);
        } catch (IOException | GeneralSecurityException e) {
            throw new CommitterException(
                    "Could not initialize Google Cloud Search client.", e);
        }
    }

    @Override
    protected void closeBatchCommitter() {
        cloudSearch = null;
    }

    @Override
    protected void commitBatch(Iterator<ICommitterRequest> it)
            throws CommitterException {
        BatchFailureCollector failures = new BatchFailureCollector();
        int operationCount = 0;
        try {
            BatchRequest batch = helper.createBatchRequest(cloudSearch);
            while (it.hasNext()) {
                ICommitterRequest request = it.next();
                if (request instanceof UpsertRequest) {
                    queueUpsert(batch, (UpsertRequest) request, failures);
                } else if (request instanceof DeleteRequest) {
                    queueDelete(batch, (DeleteRequest) request, failures);
                } else {
                    throw new CommitterException(
                            "Unsupported request type: " + request);
                }
                operationCount++;
            }
            if (operationCount > 0) {
                helper.executeBatch(batch);
                failures.throwIfAny();
                log.info("Sent {} commit operations to Google Cloud Search.",
                        operationCount);
            }
        } catch (CommitterException e) {
            throw e;
        } catch (Exception e) {
            throw new CommitterException(
                    "Could not commit batch to Google Cloud Search.", e);
        }
    }

    private void queueUpsert(BatchRequest batch, UpsertRequest request,
            BatchFailureCollector failures)
            throws IOException, CommitterException {
        String sourceId = CommitterUtil.extractSourceIdValue(
                request, sourceIdField, keepSourceIdField);
        String itemName = buildItemName(sourceId);
        String contentType = resolveContentType(request.getMetadata());

        Item item = new Item()
                .setName(itemName)
                .setItemType(CONTENT_ITEM_TYPE)
                .encodeVersion(nextVersion().getBytes(UTF_8))
                .setMetadata(buildMetadata(request, contentType))
                .setStructuredData(buildStructuredData(request.getMetadata()))
                .setAcl(buildAcl(request.getMetadata()));

        ItemContent itemContent = buildItemContent(request, itemName, contentType);
        if (itemContent != null) {
            item.setContent(itemContent);
        }

        IndexItemRequest indexRequest = new IndexItemRequest()
                .setConnectorName(connectorName)
                .setItem(item)
                .setMode(requestMode.name());

        cloudSearch.indexing()
                .datasources()
                .items()
                .index(itemName, indexRequest)
                .queue(batch, failures);
    }

    private void queueDelete(BatchRequest batch, DeleteRequest request,
            BatchFailureCollector failures)
            throws IOException, CommitterException {
        String sourceId = CommitterUtil.extractSourceIdValue(
                request, sourceIdField, keepSourceIdField);
        String itemName = buildItemName(sourceId);
        cloudSearch.indexing()
                .datasources()
                .items()
                .delete(itemName)
                .setMode(requestMode.name())
                .queue(batch, failures);
    }

    private ItemMetadata buildMetadata(UpsertRequest request,
            String contentType) {
        Properties metadata = request.getMetadata();
        ItemMetadata itemMetadata = new ItemMetadata();

        String title = resolveMetadataValue(metadata, MetadataField.TITLE,
                request.getReference(), contentType);
        if (StringUtils.isNotBlank(title)) {
            itemMetadata.setTitle(title);
        }

        String objectType = resolveMetadataValue(metadata,
                MetadataField.OBJECT_TYPE, request.getReference(), contentType);
        if (StringUtils.isNotBlank(objectType)) {
            itemMetadata.setObjectType(objectType);
        }

        String mimeType = resolveMetadataValue(metadata,
                MetadataField.MIME_TYPE, request.getReference(), contentType);
        if (StringUtils.isNotBlank(mimeType)) {
            itemMetadata.setMimeType(mimeType);
        }

        String containerName = resolveMetadataValue(metadata,
                MetadataField.CONTAINER_NAME, request.getReference(),
                contentType);
        if (StringUtils.isNotBlank(containerName)) {
            itemMetadata.setContainerName(containerName);
        }

        String contentLanguage = resolveMetadataValue(metadata,
                MetadataField.CONTENT_LANGUAGE, request.getReference(),
                contentType);
        if (StringUtils.isNotBlank(contentLanguage)) {
            itemMetadata.setContentLanguage(contentLanguage);
        }

        String updateTime = resolveMetadataValue(metadata, MetadataField.UPDATE_TIME,
                request.getReference(), contentType);
        if (StringUtils.isNotBlank(updateTime)) {
            String parsedTime = toRfc3339(updateTime);
            if (parsedTime != null) {
                itemMetadata.setUpdateTime(parsedTime);
            }
        }

        String createTime = resolveMetadataValue(metadata, MetadataField.CREATE_TIME,
                request.getReference(), contentType);
        if (StringUtils.isNotBlank(createTime)) {
            String parsedTime = toRfc3339(createTime);
            if (parsedTime != null) {
                itemMetadata.setCreateTime(parsedTime);
            }
        }

        String sourceRepositoryUrl = resolveMetadataValue(metadata,
                MetadataField.SOURCE_REPOSITORY_URL, request.getReference(),
                contentType);
        if (StringUtils.isNotBlank(sourceRepositoryUrl)) {
            itemMetadata.setSourceRepositoryUrl(sourceRepositoryUrl);
        }
        return itemMetadata;
    }

    private String resolveMetadataValue(Properties metadata,
            MetadataField field,
            String reference,
            String contentType) {
        MetadataMapping mapping = findMetadataMapping(field);
        if (mapping != null) {
            String value = metadataValue(metadata, mapping.getFromField());
            value = StringUtils.defaultIfBlank(value,
                    mapping.getDefaultValue());
            if (StringUtils.isNotBlank(value)) {
                return value;
            }
        }

        switch (field) {
            case TITLE:
                return metadataValue(metadata, DEFAULT_TITLE_SOURCE_FIELD);
            case OBJECT_TYPE:
                return StringUtils.defaultIfBlank(
                        metadataValue(metadata,
                                DEFAULT_OBJECT_TYPE_SOURCE_FIELD),
                        DEFAULT_OBJECT_TYPE);
            case MIME_TYPE:
                return contentType;
            case UPDATE_TIME:
                return metadataValue(metadata,
                        DEFAULT_UPDATE_TIME_SOURCE_FIELD);
            case CREATE_TIME:
            case CONTAINER_NAME:
            case CONTENT_LANGUAGE:
                return null;
            case SOURCE_REPOSITORY_URL:
                return reference;
            default:
                return null;
        }
    }

    private MetadataMapping findMetadataMapping(MetadataField targetField) {
        for (MetadataMapping mapping : metadataMappings) {
            if (mapping == null || mapping.getToField() == null) {
                continue;
            }
            if (mapping.getToField() == targetField) {
                return mapping;
            }
        }
        return null;
    }

    private ItemStructuredData buildStructuredData(Properties metadata) {
        List<NamedProperty> properties = new ArrayList<>();
        Set<String> excludedFields = buildStructuredDataExclusions();

        for (Entry<String, List<String>> entry : metadata.entrySet()) {
            if (excludedFields.contains(entry.getKey())
                    || entry.getValue() == null
                    || entry.getValue().isEmpty()) {
                continue;
            }
            properties.add(toNamedProperty(entry.getKey(), entry.getValue()));
        }
        if (properties.isEmpty()) {
            return null;
        }
        return new ItemStructuredData().setObject(
                new StructuredDataObject().setProperties(properties));
    }

    private NamedProperty toNamedProperty(String name, List<String> values) {
        NamedProperty property = new NamedProperty().setName(name);
        if (!typedStructuredData) {
            property.setTextValues(
                    new TextValues().setValues(new ArrayList<>(values)));
            return property;
        }

        List<com.google.api.services.cloudsearch.v1.model.Date> dates = parseAllDates(values);
        if (dates != null) {
            property.setDateValues(new DateValues().setValues(dates));
            return property;
        }

        if (allMatch(values, this::isRfc3339Timestamp)) {
            property.setTimestampValues(
                    new TimestampValues().setValues(new ArrayList<>(values)));
            return property;
        }

        if (allMatch(values, this::isLong)) {
            property.setIntegerValues(
                    new IntegerValues().setValues(toLongs(values)));
            return property;
        }

        if (allMatch(values, this::isDouble)) {
            property.setDoubleValues(
                    new DoubleValues().setValues(toDoubles(values)));
            return property;
        }

        if (allMatch(values, this::isEnumLike)) {
            property.setEnumValues(
                    new EnumValues().setValues(new ArrayList<>(values)));
            return property;
        }

        property.setTextValues(
                new TextValues().setValues(new ArrayList<>(values)));
        return property;
    }

    private boolean allMatch(List<String> values,
            java.util.function.Predicate<String> predicate) {
        for (String value : values) {
            if (StringUtils.isBlank(value) || !predicate.test(value)) {
                return false;
            }
        }
        return !values.isEmpty();
    }

    private List<Long> toLongs(List<String> values) {
        List<Long> longs = new ArrayList<>(values.size());
        for (String value : values) {
            longs.add(Long.valueOf(value));
        }
        return longs;
    }

    private List<Double> toDoubles(List<String> values) {
        List<Double> doubles = new ArrayList<>(values.size());
        for (String value : values) {
            doubles.add(Double.valueOf(value));
        }
        return doubles;
    }

    private List<com.google.api.services.cloudsearch.v1.model.Date> parseAllDates(List<String> values) {
        List<com.google.api.services.cloudsearch.v1.model.Date> dates = new ArrayList<>(values.size());
        for (String value : values) {
            com.google.api.services.cloudsearch.v1.model.Date date = toDateValue(value);
            if (date == null) {
                return null;
            }
            dates.add(date);
        }
        return dates;
    }

    private com.google.api.services.cloudsearch.v1.model.Date toDateValue(
            String value) {
        try {
            var date = java.time.LocalDate.parse(value);
            return new com.google.api.services.cloudsearch.v1.model.Date()
                    .setYear(date.getYear())
                    .setMonth(date.getMonthValue())
                    .setDay(date.getDayOfMonth());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private boolean isLong(String value) {
        try {
            Long.parseLong(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean isDouble(String value) {
        try {
            Double.parseDouble(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean isEnumLike(String value) {
        return !"true".equalsIgnoreCase(value)
                && !"false".equalsIgnoreCase(value)
                && value.matches("[A-Za-z0-9_\\-]+")
                && value.length() <= 256;
    }

    private boolean isRfc3339Timestamp(String value) {
        try {
            Instant.parse(value);
            return true;
        } catch (DateTimeParseException e) {
            try {
                OffsetDateTime.parse(value);
                return true;
            } catch (DateTimeParseException ex) {
                return false;
            }
        }
    }

    private Set<String> buildStructuredDataExclusions() {
        Set<String> excludedFields = new HashSet<>();
        excludedFields.add(FIELD_BINARY_CONTENT);
        if (StringUtils.isNotBlank(sourceIdField)) {
            excludedFields.add(sourceIdField);
        }
        for (AclMapping mapping : aclMappings) {
            excludedFields.add(mapping.getFromField());
        }
        if (StringUtils.isNotBlank(aclInheritance.getFromField())) {
            excludedFields.add(aclInheritance.getFromField());
        }
        for (MetadataMapping mapping : metadataMappings) {
            if (mapping != null
                    && !mapping.isKeepFromField()
                    && StringUtils.isNotBlank(mapping.getFromField())) {
                excludedFields.add(mapping.getFromField());
            }
        }
        return excludedFields;
    }

    private ItemAcl buildAcl(Properties metadata) throws CommitterException {
        List<Principal> readers = new ArrayList<>();
        List<Principal> deniedReaders = new ArrayList<>();
        List<Principal> owners = new ArrayList<>();

        for (AclMapping mapping : aclMappings) {
            List<String> values = metadata.getStrings(mapping.getFromField());
            if (values == null || values.isEmpty()) {
                continue;
            }
            for (String value : values) {
                Principal principal = toPrincipal(mapping.getPrincipalType(), value);
                if (principal == null) {
                    continue;
                }
                switch (mapping.getTarget()) {
                    case READERS:
                        readers.add(principal);
                        break;
                    case DENIED_READERS:
                        deniedReaders.add(principal);
                        break;
                    case OWNERS:
                        owners.add(principal);
                        break;
                    default:
                        throw new CommitterException(
                                "Unsupported ACL target: "
                                        + mapping.getTarget());
                }
            }
        }

        String parentValue = metadataValue(metadata, aclInheritance.getFromField());
        boolean hasInheritance = StringUtils.isNotBlank(parentValue);

        ItemAcl acl = new ItemAcl();
        if (!readers.isEmpty()) {
            acl.setReaders(readers);
        } else if (!hasInheritance) {
            // Cloud Search rejects items with no ACL at all ("Missing Acl in
            // request"), and inheritance alone does not grant access unless a
            // parent is set. When no reader mapping resolved to a value and
            // there is no ACL to inherit from, default to the entire domain
            // being able to read the item, matching the original Google-built
            // Norconex connector's documented default of granting read access
            // to everyone when no ACL information is supplied.
            acl.setReaders(Collections.singletonList(
                    toPrincipal(PrincipalType.CUSTOMER, null)));
        }
        if (!deniedReaders.isEmpty()) {
            acl.setDeniedReaders(deniedReaders);
        }
        if (!owners.isEmpty()) {
            acl.setOwners(owners);
        }
        if (hasInheritance) {
            acl.setInheritAclFrom(buildItemName(parentValue));
            acl.setAclInheritanceType(aclInheritance.getType().name());
        }
        return acl;
    }

    private Principal toPrincipal(PrincipalType principalType, String value) {
        if (principalType == PrincipalType.CUSTOMER) {
            return new Principal().setGsuitePrincipal(
                    new GSuitePrincipal().setGsuiteDomain(true));
        }
        if (StringUtils.isBlank(value)) {
            return null;
        }
        GSuitePrincipal gsuitePrincipal = new GSuitePrincipal();
        if (principalType == PrincipalType.USER) {
            gsuitePrincipal.setGsuiteUserEmail(value);
        } else if (principalType == PrincipalType.GROUP) {
            gsuitePrincipal.setGsuiteGroupEmail(value);
        }
        return new Principal().setGsuitePrincipal(gsuitePrincipal);
    }

    private ItemContent buildItemContent(UpsertRequest request, String itemName,
            String contentType) throws IOException, CommitterException {
        if (uploadFormat == UploadFormat.RAW) {
            return uploadContent(itemName, contentType, loadRawContent(request),
                    "RAW");
        }

        byte[] textBytes = IOUtils.toString(request.getContent(), UTF_8)
                .getBytes(UTF_8);
        if (textBytes.length <= INLINE_CONTENT_MAX_BYTES) {
            return new ItemContent()
                    .setContentFormat("TEXT")
                    .encodeInlineContent(textBytes);
        }
        return uploadContent(itemName, contentType, textBytes, "TEXT");
    }

    private ItemContent uploadContent(String itemName, String contentType,
            byte[] content, String contentFormat) throws IOException {
        UploadItemRef uploadItemRef = cloudSearch.indexing()
                .datasources()
                .items()
                .upload(itemName, new StartUploadItemRequest()
                        .setConnectorName(connectorName))
                .execute();

        CloudSearch.Media.Upload uploadRequest = cloudSearch.media().upload(
                uploadItemRef.getName(),
                new Media().setResourceName(uploadItemRef.getName()),
                new ByteArrayContent(contentType, content));
        uploadRequest.getMediaHttpUploader().setDirectUploadEnabled(true);
        // The real Cloud Search media.upload endpoint returns an empty body
        // on success (unlike the Media-typed response the client stub
        // declares), so executeUnparsed() is used to avoid failing to parse
        // an empty response as JSON. The Media result isn't needed here.
        uploadRequest.executeUnparsed().disconnect();

        return new ItemContent()
                .setContentFormat(contentFormat)
                .setContentDataRef(uploadItemRef);
    }

    private byte[] loadRawContent(UpsertRequest request) throws IOException {
        String encoded = request.getMetadata().getString(FIELD_BINARY_CONTENT);
        if (encoded != null) {
            return Base64.getDecoder().decode(encoded);
        }
        log.warn("Raw upload selected but '{}' is missing. Falling back to "
                + "request content for {}.", FIELD_BINARY_CONTENT,
                request.getReference());
        return IOUtils.toByteArray(request.getContent());
    }

    private String resolveContentType(Properties metadata) {
        String contentType = metadata.getString(FIELD_CONTENT_TYPE);
        if (StringUtils.isNotBlank(contentType)) {
            return contentType;
        }
        return uploadFormat == UploadFormat.RAW
                ? DEFAULT_BINARY_CONTENT_TYPE
                : DEFAULT_TEXT_CONTENT_TYPE;
    }

    private String buildItemName(String sourceId) throws CommitterException {
        if (StringUtils.isBlank(sourceId)) {
            throw new CommitterException("Document id cannot be empty.");
        }
        return "datasources/" + dataSourceId + "/items/"
                + encodeItemId(sourceId);
    }

    private String encodeItemId(String sourceId) {
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(sourceId.getBytes(UTF_8));
        if (encoded.length() <= 1500) {
            return encoded;
        }
        return "sha256-" + sha256Hex(sourceId);
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(UTF_8));
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                builder.append(Character.forDigit((b >> 4) & 0xF, 16));
                builder.append(Character.forDigit(b & 0xF, 16));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available.", e);
        }
    }

    private String nextVersion() {
        long millis = helper.currentTimeMillis();
        long sequence = versionSequence.incrementAndGet();
        return String.format("%019d-%06d", millis, sequence);
    }

    private String toRfc3339(String value) {
        try {
            return Instant.parse(value).toString();
        } catch (DateTimeParseException e) {
            // Try other supported formats.
        }
        try {
            return OffsetDateTime.parse(value).toInstant().toString();
        } catch (DateTimeParseException e) {
            // Try other supported formats.
        }
        try {
            return ZonedDateTime
                    .parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant().toString();
        } catch (DateTimeParseException e) {
            // Try other supported formats.
        }
        try {
            return LocalDateTime.parse(value).toInstant(ZoneOffset.UTC)
                    .toString();
        } catch (DateTimeParseException e) {
            log.debug("Ignoring unparsable updateTime value '{}'.", value);
        }
        return null;
    }

    private String metadataValue(Properties metadata, String field) {
        if (StringUtils.isBlank(field)) {
            return null;
        }
        return metadata.getString(field);
    }

    private void validateConfiguration() throws CommitterException {
        if (StringUtils.isBlank(secretKeyPath)) {
            throw new CommitterException(
                    "Missing required configuration entry: "
                            + CONFIG_SECRET_KEY_PATH);
        }
        if (StringUtils.isBlank(dataSourceId)) {
            throw new CommitterException(
                    "Missing required configuration entry: "
                            + CONFIG_DATA_SOURCE_ID);
        }
        if (StringUtils.isBlank(connectorName)) {
            connectorName = applicationName;
        }
        for (MetadataMapping mapping : metadataMappings) {
            if (mapping == null || mapping.getToField() == null) {
                throw new CommitterException(
                        "Each metadata mapping must declare a non-blank toField.");
            }
        }
    }

    @Override
    protected void loadBatchCommitterFromXML(XML xml) {
        secretKeyPath = xml.getString(CONFIG_SECRET_KEY_PATH, secretKeyPath);
        dataSourceId = xml.getString(CONFIG_DATA_SOURCE_ID, dataSourceId);
        apiEndpoint = xml.getString(CONFIG_API_ENDPOINT, apiEndpoint);
        applicationName = xml.getString(
                CONFIG_APPLICATION_NAME, applicationName);
        connectorName = xml.getString(CONFIG_CONNECTOR_NAME, connectorName);
        sourceIdField = xml.getString(CONFIG_SOURCE_ID_FIELD, sourceIdField);
        keepSourceIdField = xml.getBoolean(
                CONFIG_KEEP_SOURCE_ID_FIELD, keepSourceIdField);
        typedStructuredData = xml.getBoolean(
                CONFIG_TYPED_STRUCTURED_DATA, typedStructuredData);

        String uploadFormatValue = xml.getString(
                CONFIG_UPLOAD_FORMAT, uploadFormat.name());
        uploadFormat = UploadFormat.valueOf(uploadFormatValue.toUpperCase());

        String requestModeValue = xml.getString(
                CONFIG_REQUEST_MODE, requestMode.name());
        requestMode = RequestMode.valueOf(requestModeValue.toUpperCase());

        metadataMappings.clear();
        for (XML mappingXml : xml.getXMLList(CONFIG_METADATA + "/mapping")) {
            metadataMappings.add(new MetadataMapping(
                    mappingXml.getString("@fromField", null),
                    MetadataField.fromXmlValue(
                            mappingXml.getString("@toField", null)),
                    mappingXml.getString("@defaultValue", null),
                    mappingXml.getBoolean("@keepFromField", false)));
        }

        aclMappings.clear();
        for (XML mappingXml : xml.getXMLList("acl/mapping")) {
            aclMappings.add(new AclMapping(
                    mappingXml.getString("@fromField", null),
                    AclTarget.fromXmlValue(
                            mappingXml.getString("@target", null)),
                    PrincipalType.fromXmlValue(mappingXml.getString(
                            "@principalType",
                            PrincipalType.USER.getXmlValue()))));
        }
        aclInheritance = new AclInheritanceMapping();
        xml.ifXML("acl/inherit",
                x -> aclInheritance = new AclInheritanceMapping(
                        x.getString("@fromField", null),
                        AclInheritanceType.valueOf(x.getString(
                                "@aclInheritanceType",
                                AclInheritanceType.NOT_APPLICABLE.name())
                                .toUpperCase())));
    }

    @Override
    protected void saveBatchCommitterToXML(XML xml) {
        xml.addElement(CONFIG_SECRET_KEY_PATH, secretKeyPath);
        xml.addElement(CONFIG_DATA_SOURCE_ID, dataSourceId);
        xml.addElement(CONFIG_UPLOAD_FORMAT, uploadFormat.name().toLowerCase());
        xml.addElement(CONFIG_REQUEST_MODE, requestMode.name().toLowerCase());
        xml.addElement(CONFIG_API_ENDPOINT, apiEndpoint);
        xml.addElement(CONFIG_APPLICATION_NAME, applicationName);
        xml.addElement(CONFIG_CONNECTOR_NAME, connectorName);
        xml.addElement(CONFIG_SOURCE_ID_FIELD, sourceIdField);
        xml.addElement(CONFIG_KEEP_SOURCE_ID_FIELD, keepSourceIdField);
        xml.addElement(CONFIG_TYPED_STRUCTURED_DATA, typedStructuredData);

        if (!metadataMappings.isEmpty()) {
            XML metadataXml = xml.addElement(CONFIG_METADATA);
            for (MetadataMapping mapping : metadataMappings) {
                XML mappingXml = metadataXml.addElement("mapping")
                        .setAttribute("toField", mapping.getToField())
                        .setAttribute("keepFromField",
                                mapping.isKeepFromField());
                if (StringUtils.isNotBlank(mapping.getFromField())) {
                    mappingXml.setAttribute("fromField",
                            mapping.getFromField());
                }
                if (StringUtils.isNotBlank(mapping.getDefaultValue())) {
                    mappingXml.setAttribute("defaultValue",
                            mapping.getDefaultValue());
                }
            }
        }

        if (!aclMappings.isEmpty()
                || StringUtils.isNotBlank(aclInheritance.getFromField())) {
            XML aclXml = xml.addElement("acl");
            for (AclMapping aclMapping : aclMappings) {
                aclXml.addElement("mapping")
                        .setAttribute("fromField", aclMapping.getFromField())
                        .setAttribute("target",
                                aclMapping.getTarget().getXmlValue())
                        .setAttribute("principalType",
                                aclMapping.getPrincipalType().getXmlValue());
            }
            if (StringUtils.isNotBlank(aclInheritance.getFromField())) {
                aclXml.addElement("inherit")
                        .setAttribute("fromField",
                                aclInheritance.getFromField())
                        .setAttribute("aclInheritanceType",
                                aclInheritance.getType());
            }
        }
    }

    static class Helper {
        CloudSearch createCloudSearch(String applicationName,
                String secretKeyPath,
                String apiEndpoint)
                throws IOException, GeneralSecurityException {
            HttpRequestInitializer initializer = createRequestInitializer(secretKeyPath);
            CloudSearch.Builder builder = new CloudSearch.Builder(
                    createHttpTransport(), createJsonFactory(), initializer)
                    .setApplicationName(applicationName);
            if (StringUtils.isNotBlank(apiEndpoint)) {
                builder.setRootUrl(ensureTrailingSlash(apiEndpoint));
            }
            return builder.build();
        }

        HttpTransport createHttpTransport()
                throws GeneralSecurityException, IOException {
            return GoogleNetHttpTransport.newTrustedTransport();
        }

        JsonFactory createJsonFactory() {
            return GsonFactory.getDefaultInstance();
        }

        HttpRequestInitializer createRequestInitializer(String secretKeyPath)
                throws IOException {
            try (InputStream input = new FileInputStream(secretKeyPath)) {
                GoogleCredentials credentials = ServiceAccountCredentials
                        .fromStream(input)
                        .createScoped(Collections.singleton(INDEXING_SCOPE));
                return new HttpCredentialsAdapter(credentials);
            }
        }

        BatchRequest createBatchRequest(CloudSearch cloudSearch)
                throws IOException {
            return cloudSearch.batch();
        }

        void executeBatch(BatchRequest batchRequest) throws IOException {
            batchRequest.execute();
        }

        long currentTimeMillis() {
            return System.currentTimeMillis();
        }

        private String ensureTrailingSlash(String value) {
            return value.endsWith("/") ? value : value + "/";
        }
    }

    public static class AclMapping {
        private String fromField;
        private AclTarget target;
        private PrincipalType principalType;

        public AclMapping() {
        }

        AclMapping(String fromField, AclTarget target,
                PrincipalType principalType) {
            this.fromField = fromField;
            this.target = target;
            this.principalType = principalType;
        }

        public String getFromField() {
            return fromField;
        }

        public AclMapping setFromField(String fromField) {
            this.fromField = fromField;
            return this;
        }

        public AclTarget getTarget() {
            return target;
        }

        public AclMapping setTarget(AclTarget target) {
            this.target = target;
            return this;
        }

        public PrincipalType getPrincipalType() {
            return principalType;
        }

        public AclMapping setPrincipalType(PrincipalType principalType) {
            this.principalType = principalType;
            return this;
        }
    }

    /**
     * Maps crawler metadata fields to Google Cloud Search predefined
     * metadata fields. Mapped source fields are excluded from structured
     * data unless {@code keepFromField} is set to {@code true}.
     *
     * See Google reference:
     * https://developers.google.com/workspace/cloud-search/docs/reference/rest/v1/ItemMetadata
     */
    public static class MetadataMapping {
        private String fromField;
        private MetadataField toField;
        private String defaultValue;
        private boolean keepFromField;

        public MetadataMapping() {
        }

        MetadataMapping(
                String fromField,
                MetadataField toField,
                String defaultValue,
                boolean keepFromField) {
            this.fromField = fromField;
            this.toField = toField;
            this.defaultValue = defaultValue;
            this.keepFromField = keepFromField;
        }

        public String getFromField() {
            return fromField;
        }

        public MetadataMapping setFromField(String fromField) {
            this.fromField = fromField;
            return this;
        }

        public MetadataField getToField() {
            return toField;
        }

        public MetadataMapping setToField(MetadataField toField) {
            this.toField = toField;
            return this;
        }

        public String getDefaultValue() {
            return defaultValue;
        }

        public MetadataMapping setDefaultValue(String defaultValue) {
            this.defaultValue = defaultValue;
            return this;
        }

        public boolean isKeepFromField() {
            return keepFromField;
        }

        public MetadataMapping setKeepFromField(boolean keepFromField) {
            this.keepFromField = keepFromField;
            return this;
        }
    }

    public static class AclInheritanceMapping {
        private String fromField;
        private AclInheritanceType type = AclInheritanceType.NOT_APPLICABLE;

        public AclInheritanceMapping() {
        }

        AclInheritanceMapping(String fromField, AclInheritanceType type) {
            this.fromField = fromField;
            this.type = type;
        }

        public String getFromField() {
            return fromField;
        }

        public AclInheritanceMapping setFromField(String fromField) {
            this.fromField = fromField;
            return this;
        }

        public AclInheritanceType getType() {
            return type;
        }

        public AclInheritanceMapping setType(AclInheritanceType type) {
            this.type = type != null ? type : AclInheritanceType.NOT_APPLICABLE;
            return this;
        }
    }

    private static final class BatchFailureCollector
            extends JsonBatchCallback<Operation> {
        private final List<String> failures = new ArrayList<>();

        @Override
        public void onSuccess(Operation operation,
                HttpHeaders responseHeaders) {
            // NOOP
        }

        @Override
        public void onFailure(GoogleJsonError e, HttpHeaders responseHeaders) {
            try {
                failures.add(e.toPrettyString());
            } catch (IOException ioe) {
                failures.add(String.valueOf(e));
            }
        }

        void throwIfAny() throws CommitterException {
            if (!failures.isEmpty()) {
                throw new CommitterException(
                        "Google Cloud Search returned batch failures: "
                                + StringUtils.join(failures, "\n"));
            }
        }
    }
}