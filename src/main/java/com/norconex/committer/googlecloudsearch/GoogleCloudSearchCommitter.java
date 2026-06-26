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
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.api.client.googleapis.batch.BatchRequest;
import com.google.api.client.googleapis.batch.json.JsonBatchCallback;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.googleapis.json.GoogleJsonError;
import com.google.api.client.http.ByteArrayContent;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.cloudsearch.v1.CloudSearch;
import com.google.api.services.cloudsearch.v1.model.GSuitePrincipal;
import com.google.api.services.cloudsearch.v1.model.IndexItemRequest;
import com.google.api.services.cloudsearch.v1.model.Item;
import com.google.api.services.cloudsearch.v1.model.ItemAcl;
import com.google.api.services.cloudsearch.v1.model.ItemContent;
import com.google.api.services.cloudsearch.v1.model.ItemMetadata;
import com.google.api.services.cloudsearch.v1.model.ItemStructuredData;
import com.google.api.services.cloudsearch.v1.model.Media;
import com.google.api.services.cloudsearch.v1.model.NamedProperty;
import com.google.api.services.cloudsearch.v1.model.Operation;
import com.google.api.services.cloudsearch.v1.model.Principal;
import com.google.api.services.cloudsearch.v1.model.StartUploadItemRequest;
import com.google.api.services.cloudsearch.v1.model.StructuredDataObject;
import com.google.api.services.cloudsearch.v1.model.TextValues;
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
import com.norconex.commons.lang.map.Properties;
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
 * {@nx.include com.norconex.committer.core3.AbstractCommitter#restrictTo}
 *
 * {@nx.include com.norconex.committer.core3.AbstractCommitter#fieldMappings}
 *
 * {@nx.xml.usage
 * <committer class=
 * "com.norconex.committer.googlecloudsearch.GoogleCloudSearchCommitter">
 * <secretKeyPath>/path/to/google-service-account.json</secretKeyPath>
 * <dataSourceId>your-datasource-id</dataSourceId>
 * <uploadFormat>raw</uploadFormat>
 * <apiEndpoint>http://localhost:8080/</apiEndpoint>
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
 */
@SuppressWarnings("javadoc")
public class GoogleCloudSearchCommitter extends AbstractBatchCommitter {

    static final String FIELD_BINARY_CONTENT = "binaryContent";
    static final String FIELD_CONTENT_TYPE = "document.contentType";

    static final String CONFIG_SECRET_KEY_PATH = "secretKeyPath";
    static final String CONFIG_DATA_SOURCE_ID = "dataSourceId";
    static final String CONFIG_UPLOAD_FORMAT = "uploadFormat";
    static final String CONFIG_API_ENDPOINT = "apiEndpoint";
    static final String CONFIG_APPLICATION_NAME = "applicationName";
    static final String CONFIG_CONNECTOR_NAME = "connectorName";
    static final String CONFIG_SOURCE_ID_FIELD = "sourceIdField";
    static final String CONFIG_KEEP_SOURCE_ID_FIELD = "keepSourceIdField";
    static final String CONFIG_TITLE_FIELD = "titleField";
    static final String CONFIG_OBJECT_TYPE_FIELD = "objectTypeField";
    static final String CONFIG_UPDATE_TIME_FIELD = "updateTimeField";
    static final String CONFIG_CONTAINER_NAME_FIELD = "containerNameField";
    static final String CONFIG_CONTENT_LANGUAGE_FIELD = "contentLanguageField";
    static final String CONFIG_SOURCE_REPOSITORY_URL_FIELD = "sourceRepositoryUrlField";

    static final String DEFAULT_APPLICATION_NAME = "Norconex Google Cloud Search Committer";
    static final String DEFAULT_TITLE_FIELD = "title";
    static final String DEFAULT_OBJECT_TYPE_FIELD = "objectType";
    static final String DEFAULT_OBJECT_TYPE = "document";
    static final String DEFAULT_UPDATE_TIME_FIELD = "Last-Modified";
    static final String DEFAULT_TEXT_CONTENT_TYPE = "text/plain";
    static final String DEFAULT_BINARY_CONTENT_TYPE = "application/octet-stream";
    static final int INLINE_CONTENT_MAX_BYTES = 102400;

    private static final String INDEXING_SCOPE = "https://www.googleapis.com/auth/cloud_search.indexing";
    private static final String CONTENT_ITEM_TYPE = "CONTENT_ITEM";
    private static final Logger LOG = LoggerFactory.getLogger(
            GoogleCloudSearchCommitter.class);

    enum UploadFormat {
        RAW,
        TEXT
    }

    enum AclTarget {
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
            throw new IllegalArgumentException("Unsupported ACL target: " + value);
        }

        String getXmlValue() {
            return xmlValue;
        }
    }

    enum PrincipalType {
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

    enum AclInheritanceType {
        NOT_APPLICABLE,
        CHILD_OVERRIDE,
        PARENT_OVERRIDE,
        BOTH_PERMIT
    }

    private final Helper helper;
    private final List<AclMapping> aclMappings = new ArrayList<>();
    private final AtomicLong versionSequence = new AtomicLong();

    private String secretKeyPath;
    private String dataSourceId;
    private String apiEndpoint;
    private String applicationName = DEFAULT_APPLICATION_NAME;
    private String connectorName = DEFAULT_APPLICATION_NAME;
    private String sourceIdField;
    private boolean keepSourceIdField;
    private String titleField = DEFAULT_TITLE_FIELD;
    private String objectTypeField = DEFAULT_OBJECT_TYPE_FIELD;
    private String updateTimeField = DEFAULT_UPDATE_TIME_FIELD;
    private String containerNameField;
    private String contentLanguageField;
    private String sourceRepositoryUrlField;
    private UploadFormat uploadFormat = UploadFormat.RAW;
    private AclInheritanceMapping aclInheritance = new AclInheritanceMapping();

    private CloudSearch cloudSearch;

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
                LOG.info("Sent {} commit operations to Google Cloud Search.",
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
            BatchFailureCollector failures) throws IOException, CommitterException {
        String sourceId = CommitterUtil.extractSourceIdValue(
                request, sourceIdField, keepSourceIdField);
        String itemName = buildItemName(sourceId);
        String contentType = resolveContentType(request.getMetadata());

        Item item = new Item()
                .setName(itemName)
                .setItemType(CONTENT_ITEM_TYPE)
                .setVersion(nextVersion())
                .setMetadata(buildMetadata(request, contentType))
                .setStructuredData(buildStructuredData(request.getMetadata()))
                .setAcl(buildAcl(request.getMetadata()));

        ItemContent itemContent = buildItemContent(request, itemName, contentType);
        if (itemContent != null) {
            item.setContent(itemContent);
        }

        IndexItemRequest indexRequest = new IndexItemRequest()
                .setConnectorName(connectorName)
                .setItem(item);

        cloudSearch.indexing()
                .datasources()
                .items()
                .index(itemName, indexRequest)
                .queue(batch, failures);
    }

    private void queueDelete(BatchRequest batch, DeleteRequest request,
            BatchFailureCollector failures) throws IOException, CommitterException {
        String sourceId = CommitterUtil.extractSourceIdValue(
                request, sourceIdField, keepSourceIdField);
        String itemName = buildItemName(sourceId);
        cloudSearch.indexing()
                .datasources()
                .items()
                .delete(itemName)
                .queue(batch, failures);
    }

    private ItemMetadata buildMetadata(UpsertRequest request, String contentType) {
        Properties metadata = request.getMetadata();
        ItemMetadata itemMetadata = new ItemMetadata();

        String title = metadata.getString(titleField);
        if (StringUtils.isNotBlank(title)) {
            itemMetadata.setTitle(title);
        }

        String objectType = metadata.getString(objectTypeField);
        itemMetadata.setObjectType(StringUtils.defaultIfBlank(
                objectType, DEFAULT_OBJECT_TYPE));
        itemMetadata.setMimeType(contentType);

        String containerName = metadataValue(metadata, containerNameField);
        if (StringUtils.isNotBlank(containerName)) {
            itemMetadata.setContainerName(containerName);
        }

        String contentLanguage = metadataValue(metadata, contentLanguageField);
        if (StringUtils.isNotBlank(contentLanguage)) {
            itemMetadata.setContentLanguage(contentLanguage);
        }

        String updateTime = metadataValue(metadata, updateTimeField);
        if (StringUtils.isNotBlank(updateTime)) {
            String parsedTime = toRfc3339(updateTime);
            if (parsedTime != null) {
                itemMetadata.setUpdateTime(parsedTime);
            }
        }

        String sourceRepositoryUrl = StringUtils.isNotBlank(sourceRepositoryUrlField)
                ? metadataValue(metadata, sourceRepositoryUrlField)
                : request.getReference();
        if (StringUtils.isNotBlank(sourceRepositoryUrl)) {
            itemMetadata.setSourceRepositoryUrl(sourceRepositoryUrl);
        }
        return itemMetadata;
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
            properties.add(new NamedProperty()
                    .setName(entry.getKey())
                    .setTextValues(new TextValues()
                            .setValues(new ArrayList<>(entry.getValue()))));
        }
        if (properties.isEmpty()) {
            return null;
        }
        return new ItemStructuredData().setObject(
                new StructuredDataObject().setProperties(properties));
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
                                "Unsupported ACL target: " + mapping.getTarget());
                }
            }
        }

        String parentValue = metadataValue(metadata, aclInheritance.getFromField());
        boolean hasInheritance = StringUtils.isNotBlank(parentValue);
        if (readers.isEmpty() && deniedReaders.isEmpty() && owners.isEmpty()
                && !hasInheritance) {
            return null;
        }

        ItemAcl acl = new ItemAcl();
        if (!readers.isEmpty()) {
            acl.setReaders(readers);
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
            return uploadContent(itemName, contentType, loadRawContent(request), "RAW");
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
        uploadRequest.execute();

        return new ItemContent()
                .setContentFormat(contentFormat)
                .setContentDataRef(uploadItemRef);
    }

    private byte[] loadRawContent(UpsertRequest request) throws IOException {
        String encoded = request.getMetadata().getString(FIELD_BINARY_CONTENT);
        if (encoded != null) {
            return Base64.getDecoder().decode(encoded);
        }
        LOG.warn("Raw upload selected but '{}' is missing. Falling back to "
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
        return "datasources/" + dataSourceId + "/items/" + encodeItemId(sourceId);
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
            return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant().toString();
        } catch (DateTimeParseException e) {
            // Try other supported formats.
        }
        try {
            return LocalDateTime.parse(value).toInstant(ZoneOffset.UTC).toString();
        } catch (DateTimeParseException e) {
            LOG.debug("Ignoring unparsable updateTime value '{}'.", value);
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
        titleField = xml.getString(CONFIG_TITLE_FIELD, titleField);
        objectTypeField = xml.getString(CONFIG_OBJECT_TYPE_FIELD, objectTypeField);
        updateTimeField = xml.getString(CONFIG_UPDATE_TIME_FIELD, updateTimeField);
        containerNameField = xml.getString(
                CONFIG_CONTAINER_NAME_FIELD, containerNameField);
        contentLanguageField = xml.getString(
                CONFIG_CONTENT_LANGUAGE_FIELD, contentLanguageField);
        sourceRepositoryUrlField = xml.getString(
                CONFIG_SOURCE_REPOSITORY_URL_FIELD, sourceRepositoryUrlField);

        String uploadFormatValue = xml.getString(
                CONFIG_UPLOAD_FORMAT, uploadFormat.name());
        uploadFormat = UploadFormat.valueOf(uploadFormatValue.toUpperCase());

        aclMappings.clear();
        for (XML mappingXml : xml.getXMLList("acl/mapping")) {
            aclMappings.add(new AclMapping(
                    mappingXml.getString("@fromField", null),
                    AclTarget.fromXmlValue(mappingXml.getString("@target", null)),
                    PrincipalType.fromXmlValue(mappingXml.getString(
                            "@principalType", PrincipalType.USER.getXmlValue()))));
        }
        aclInheritance = new AclInheritanceMapping();
        xml.ifXML("acl/inherit", x -> aclInheritance = new AclInheritanceMapping(
                x.getString("@fromField", null),
                AclInheritanceType.valueOf(x.getString(
                        "@aclInheritanceType",
                        AclInheritanceType.NOT_APPLICABLE.name()).toUpperCase())));
    }

    @Override
    protected void saveBatchCommitterToXML(XML xml) {
        xml.addElement(CONFIG_SECRET_KEY_PATH, secretKeyPath);
        xml.addElement(CONFIG_DATA_SOURCE_ID, dataSourceId);
        xml.addElement(CONFIG_UPLOAD_FORMAT, uploadFormat.name().toLowerCase());
        xml.addElement(CONFIG_API_ENDPOINT, apiEndpoint);
        xml.addElement(CONFIG_APPLICATION_NAME, applicationName);
        xml.addElement(CONFIG_CONNECTOR_NAME, connectorName);
        xml.addElement(CONFIG_SOURCE_ID_FIELD, sourceIdField);
        xml.addElement(CONFIG_KEEP_SOURCE_ID_FIELD, keepSourceIdField);
        xml.addElement(CONFIG_TITLE_FIELD, titleField);
        xml.addElement(CONFIG_OBJECT_TYPE_FIELD, objectTypeField);
        xml.addElement(CONFIG_UPDATE_TIME_FIELD, updateTimeField);
        xml.addElement(CONFIG_CONTAINER_NAME_FIELD, containerNameField);
        xml.addElement(CONFIG_CONTENT_LANGUAGE_FIELD, contentLanguageField);
        xml.addElement(CONFIG_SOURCE_REPOSITORY_URL_FIELD, sourceRepositoryUrlField);

        if (!aclMappings.isEmpty()
                || StringUtils.isNotBlank(aclInheritance.getFromField())) {
            XML aclXml = xml.addElement("acl");
            for (AclMapping aclMapping : aclMappings) {
                aclXml.addElement("mapping")
                        .setAttribute("fromField", aclMapping.getFromField())
                        .setAttribute("target", aclMapping.getTarget().getXmlValue())
                        .setAttribute("principalType", aclMapping.getPrincipalType().getXmlValue());
            }
            if (StringUtils.isNotBlank(aclInheritance.getFromField())) {
                aclXml.addElement("inherit")
                        .setAttribute("fromField", aclInheritance.getFromField())
                        .setAttribute("aclInheritanceType", aclInheritance.getType());
            }
        }
    }

    static class Helper {
        CloudSearch createCloudSearch(String applicationName, String secretKeyPath,
                String apiEndpoint) throws IOException, GeneralSecurityException {
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

    private static final class AclMapping {
        private final String fromField;
        private final AclTarget target;
        private final PrincipalType principalType;

        private AclMapping(String fromField, AclTarget target,
                PrincipalType principalType) {
            this.fromField = fromField;
            this.target = target;
            this.principalType = principalType;
        }

        String getFromField() {
            return fromField;
        }

        AclTarget getTarget() {
            return target;
        }

        PrincipalType getPrincipalType() {
            return principalType;
        }
    }

    private static final class AclInheritanceMapping {
        private final String fromField;
        private final AclInheritanceType type;

        private AclInheritanceMapping() {
            this(null, AclInheritanceType.NOT_APPLICABLE);
        }

        private AclInheritanceMapping(String fromField,
                AclInheritanceType type) {
            this.fromField = fromField;
            this.type = type;
        }

        String getFromField() {
            return fromField;
        }

        AclInheritanceType getType() {
            return type;
        }
    }

    private static final class BatchFailureCollector
            extends JsonBatchCallback<Operation> {
        private final List<String> failures = new ArrayList<>();

        @Override
        public void onSuccess(Operation operation, HttpHeaders responseHeaders) {
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