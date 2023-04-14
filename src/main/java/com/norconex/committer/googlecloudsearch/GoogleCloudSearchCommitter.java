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

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.api.client.http.AbstractInputStreamContent;
import com.google.api.client.http.ByteArrayContent;
import com.google.api.client.http.InputStreamContent;
import com.google.api.services.cloudsearch.v1.model.Item;
import com.google.api.services.cloudsearch.v1.model.ItemAcl;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.google.enterprise.cloudsearch.sdk.config.Configuration;
import com.google.enterprise.cloudsearch.sdk.indexing.Acl;
import com.google.enterprise.cloudsearch.sdk.indexing.DefaultAcl;
import com.google.enterprise.cloudsearch.sdk.indexing.IndexingItemBuilder;
import com.google.enterprise.cloudsearch.sdk.indexing.IndexingItemBuilder.FieldOrValue;
import com.google.enterprise.cloudsearch.sdk.indexing.IndexingItemBuilder.ItemType;
import com.google.enterprise.cloudsearch.sdk.indexing.IndexingService;
import com.google.enterprise.cloudsearch.sdk.indexing.IndexingService.ContentFormat;
import com.google.enterprise.cloudsearch.sdk.indexing.IndexingService.RequestMode;
import com.google.enterprise.cloudsearch.sdk.indexing.IndexingServiceImpl;
import com.google.enterprise.cloudsearch.sdk.indexing.StructuredData;
import com.norconex.committer.core3.CommitterException;
import com.norconex.committer.core3.DeleteRequest;
import com.norconex.committer.core3.ICommitterRequest;
import com.norconex.committer.core3.UpsertRequest;
import com.norconex.committer.core3.batch.AbstractBatchCommitter;
import com.norconex.commons.lang.map.Properties;
import com.norconex.commons.lang.xml.XML;

/**
 * --------------------- TODO: --------------------
 * 
 * Commits documents to Google Cloud Search using the Google Cloud Search
 * Connector SDK.
 *
 * <h3>Configuration</h3>
 *
 * <p>
 * The committer is using the Connector SDK to communicate with the Google Cloud
 * Search API, therefore a valid Connector SDK configuration file is required.
 * The location of this file must be set in the Norconex plugin configuration.
 * The configuration file must contain the
 * {@value IndexingServiceImpl#SOURCE_ID} and 'api.serviceAccountPrivateKeyFile'
 * entries. For a complete list of SDK configuration options, please refer to
 * the <a href="https://gsuite.google.com/products/cloud-search/">Connector SDK
 * Documentation</a>
 *
 * <p>
 * No additional configuration is required, but there are optional settings (see
 * below) that you might want to use.
 *
 * <h3>XML configuration usage</h3>
 *
 * <pre>
 *  &lt;committer class="com.norconex.committer.googlecloudsearch.GoogleCloudSearchCommitter"&gt;
 *
 *      &lt;!-- Mandatory: --&gt;
 *      &lt;{@value #CONFIG_KEY_CONFIG_FILE}&gt;
 *          (Absolute path of the GCS SDK configuration file)
 *      &lt;/{@value #CONFIG_KEY_CONFIG_FILE}&gt;
 *
 *      &lt;!-- Optional settings: --&gt;
 *      &lt;{@value #CONFIG_KEY_UPLOAD_FORMAT}&gt;
 *          ("raw": send documents' original binary content or
 *           "text": send documents' parsed content
 *           Default value: "raw", which requires configuring the {@link BinaryContentTagger})
 *      &lt;{@value #CONFIG_KEY_UPLOAD_FORMAT}&gt;
 *      &lt;sourceReferenceField keep="[false|true]"&gt;
 *         (Optional name of field that contains the document reference, when
 *         the default document reference is not used. The reference value
 *         will be mapped to Google Cloud Search "id" field, which is mandatory.
 *         Once re-mapped, this metadata source field is deleted, unless "keep"
 *         is set to <code>true</code>.)
 *      &lt;/sourceReferenceField&gt;
 *      &lt;sourceContentField keep="[false|true]"&gt;
 *         (If you wish to use a metadata field to act as the document
 *         "content", you can specify that field here. Default does not take
 *         a metadata field, but rather the document content. Only effective with
 *         <code>uploadFormat: text</code>!
 *         Once re-mapped, the metadata source field is deleted, unless "keep"
 *         is set to <code>true</code>.)
 *      &lt;/sourceContentField&gt;
 *      &lt;commitBatchSize&gt;
 *          (Max number of documents to send to Google Cloud Search at once. If you experience
 *           memory problems, lower this number. Default is 100.)
 *      &lt;/commitBatchSize&gt;
 *      &lt;queueDir&gt;(Optional path where to store queued files)&lt;/queueDir&gt;
 *      &lt;queueSize&gt;
 *          (Max queue size before committing. Default is 1000.)
 *      &lt;/queueSize&gt;
 *      &lt;maxRetries&gt;
 *          (Max retries upon commit failures. Default is 0.)
 *      &lt;/maxRetries&gt;
 *      &lt;maxRetryWait&gt;
 *          (Max delay in milliseconds between retries. Default is 0.)
 *      &lt;/maxRetryWait&gt;
 *  &lt;/committer&gt;
 * </pre>
 */
public class GoogleCloudSearchCommitter extends AbstractBatchCommitter {
    private static final Logger LOG = LoggerFactory
            .getLogger(MethodHandles.lookup().lookupClass());

    /** Path to the connector configuration file. Required. */
    static final String CONFIG_KEY_CONFIG_FILE = "configFilePath";

    /**
     * Content format used, one of the {@link #UploadFormat} names. Default is
     * "raw".
     */
    static final String CONFIG_KEY_UPLOAD_FORMAT = "uploadFormat";

    /**
     * Our internal field name, used to pass the raw content from the tagger to
     * the committer.
     */
    static final String FIELD_BINARY_CONTENT = "binaryContent";

    /** Norconex field name, always non-null. */
    static final String FIELD_CONTENT_TYPE = "document.contentType";

    /**
     * Norconex field name, set to the document title if one is found in the
     * parsed document.
     */
    static final String ITEM_METADATA_TITLE_DEFAULT = "title";

    /**
     * Norconex field name, may have multiple values derived from the HTTP
     * header and document content.
     */
    static final String ITEM_METADATA_UPDATE_TIME_DEFAULT = "Last-Modified";

    enum UploadFormat {
        RAW, TEXT
    }

    private final Helper helper;
    private String configFilePath;
    private UploadFormat uploadFormat = UploadFormat.RAW;
    private IndexingService indexingService;
    private DefaultAcl defaultAcl;
    private AtomicInteger referenceCount = new AtomicInteger(0);

    public GoogleCloudSearchCommitter() {
        this(new Helper());
    }

    @VisibleForTesting
    GoogleCloudSearchCommitter(Helper helper) {
        this.helper = helper;
    }

    @Override
    protected void loadBatchCommitterFromXML(XML xml) {
        configFilePath = xml.getString(CONFIG_KEY_CONFIG_FILE, null);
        if(StringUtils.isBlank(configFilePath)) {
            throw new RuntimeException(
                    "Missing required plugin configuration entry: " + 
            CONFIG_KEY_CONFIG_FILE);
        }
        
        String uploadFormatValue = xml.getString(CONFIG_KEY_UPLOAD_FORMAT,
                UploadFormat.RAW.name());
        if (!StringUtils.isBlank(uploadFormatValue)) {
            try {
                uploadFormat = UploadFormat
                        .valueOf(uploadFormatValue.toUpperCase());
            } catch (IllegalArgumentException e) {
                LOG.error("Unknown value for '{}'", CONFIG_KEY_UPLOAD_FORMAT);
            }
        }
    }

    @Override
    protected void saveBatchCommitterToXML(XML writer) {
        writer.addElement(CONFIG_KEY_CONFIG_FILE, configFilePath);
        writer.addElement(CONFIG_KEY_UPLOAD_FORMAT,
                uploadFormat.name().toLowerCase());
    }

    private synchronized void init() throws CommitterException {
        if (indexingService != null && indexingService.isRunning()) {
            referenceCount.incrementAndGet();
            LOG.info("Indexing Service reference count: {}",
                    referenceCount.get());
            return;
        }
        LOG.info("Starting up!");
        String[] args = { "-Dconfig=" + configFilePath };
        if (!helper.isConfigInitialized()) {
            try {
                helper.initConfig(args);
            } catch (IOException e) {
                throw new CommitterException(
                        "Initialization of SDK configuration failed.", e);
            }
        }
        indexingService = createIndexingService();
        indexingService.startAsync().awaitRunning();
        referenceCount.set(1);
        defaultAcl = helper.initDefaultAclFromConfig(indexingService);
        synchronized (this) {
            if (!StructuredData.isInitialized()) {
                StructuredData.initFromConfiguration(indexingService);
            }
        }
        LOG.info("Indexing Service reference count: {}", referenceCount.get());
    }

    private IndexingService createIndexingService() throws CommitterException {
//        IndexingService indexingService = null;
        try {
            indexingService = helper.createIndexingService();
        } catch (GeneralSecurityException | IOException e) {
            throw new CommitterException("failed to create IndexingService", e);
        }
        LOG.info("Created indexingService: {}", referenceCount.get());
        return indexingService;
    }

    @Override
    protected void commitBatch(Iterator<ICommitterRequest> it)
            throws CommitterException {        
        init();
        int docCount = 0;
        try {
            while (it.hasNext()) {
                Stopwatch stopWatch = Stopwatch.createStarted();

                ICommitterRequest r = it.next();
                if (r instanceof UpsertRequest) {
                    String url = r.getReference();
                    String contentType = r.getMetadata()
                            .getString(FIELD_CONTENT_TYPE);
                    if (StringUtils.isBlank(contentType)) {
                        throw new CommitterException("Content type field ('"
                                + FIELD_CONTENT_TYPE + "') is missing!");
                    }
                    try {
                        AbstractInputStreamContent contentStream = 
                                getInputStreamContent(
                                        (UpsertRequest) r, 
                                        contentType);
                        addItem(url, 
                                contentType, 
                                contentStream,
                                r.getMetadata(), 
                                stopWatch);
                    } catch (CommitterException e) {
                        LOG.warn("Exception caught while committing: {}. {}", 
                                url,
                                e);
                    }
                } else if (r instanceof DeleteRequest) {
                    deleteItem(r.getReference(), stopWatch);
                } else {
                    throw new CommitterException("Unsupported operation: " + r);
                }
                docCount++;
            }
            LOG.info("Sent {} committer operations to Google Cloudsearch",
                    docCount);
        } finally {
            // Shutdown IndexingService, flush remaining batch queue
            try {
                close();
            } catch (CommitterException e) {
                LOG.error("Unable to shutdown IndexingService. {}", e);
            }
        }
    }

//    @Override
//    protected void commitComplete() {
//        super.commitComplete();
//    }

    private void addItem(String url, String contentType,
            AbstractInputStreamContent contentStream, Properties properties,
            Stopwatch stopWatch) {
        try {
            Item item = createItem(url, contentType, properties);
            // Try DefaultAcl, grant customer's GSuite domain if unavailable
            if (!defaultAcl.applyToIfEnabled(item)) {
                item.setAcl(new ItemAcl().setReaders(
                        Collections.singletonList(Acl.getCustomerPrincipal())));
            }
            indexingService.indexItemAndContent(item, contentStream, null, // hash,
                                                                           // since
                                                                           // push
                                                                           // queues
                                                                           // are
                                                                           // not
                                                                           // used
                    uploadFormat == UploadFormat.RAW ? ContentFormat.RAW
                            : ContentFormat.TEXT,
                    RequestMode.ASYNCHRONOUS);

            String size = ((contentStream.getLength() == -1) ? "Unknown length"
                    : FileUtils
                            .byteCountToDisplaySize(contentStream.getLength()));
            LOG.info("Document ({}) indexed ({}) / {} ms: {}", contentType,
                    size, stopWatch.elapsed(TimeUnit.MILLISECONDS), url);
        } catch (IOException | RuntimeException e) {
            LOG.warn("Exception caught while indexing: {}. {}", url, e);
        }
    }

    private AbstractInputStreamContent getInputStreamContent(UpsertRequest add,
            String contentType) throws CommitterException {
        if (uploadFormat == UploadFormat.RAW) {
            String encoded = add.getMetadata().getString(FIELD_BINARY_CONTENT);
            try {
                return new ByteArrayContent(contentType,
                        Base64.getDecoder().decode(encoded));
            } catch (IllegalArgumentException | NullPointerException e) {
                throw new CommitterException(
                        "Binary content field is missing or invalid. Please configure BinaryContentTagger"
                                + " and make sure the '" + FIELD_BINARY_CONTENT
                                + "' field is left untouched (e.g. watch out for KeepOnlyTagger)");
            }
        }
//        try {
        return new InputStreamContent(contentType, add.getContent());
//        } catch (IOException e) {
//            throw new CommitterException(
//                    "Text content ('content') field is missing, please enable the index-basic plugin!");
//        }
    }

    private Item createItem(String url, String contentType,
            Properties properties) throws IOException {
        Multimap<String, Object> multimap = ArrayListMultimap.create();
        for (Map.Entry<String, List<String>> entry : properties.entrySet()) {
            multimap.putAll(entry.getKey(), entry.getValue());
        }
        return IndexingItemBuilder.fromConfiguration(url)
                .setItemType(ItemType.CONTENT_ITEM)
//                .setMimeType(FieldOrValue.withValue(contentType))
                .setMimeType(contentType)
                .setSourceRepositoryUrl(FieldOrValue.withValue(url))
                .setValues(multimap)
                .setTitle(FieldOrValue.withField(ITEM_METADATA_TITLE_DEFAULT))
                .setUpdateTime(FieldOrValue
                        .withField(ITEM_METADATA_UPDATE_TIME_DEFAULT))
                .build();
    }

    private void deleteItem(String url, Stopwatch stopWatch)
            throws CommitterException {
        if (StringUtils.isBlank(url)) {
            throw new CommitterException(
                    "Delete operation failed: passed url is null or empty!");
        }
        try {
            indexingService.deleteItem(url, Long
                    .toString(helper.getCurrentTimeMillis()).getBytes(UTF_8),
                    RequestMode.ASYNCHRONOUS);
            LOG.info("Document deleted ({} ms): {}",
                    stopWatch.elapsed(TimeUnit.MILLISECONDS), url);
        } catch (IOException e) {
            LOG.warn("Exception caught while indexing (delete): ", e);
        }
    }

    @Override
    protected synchronized void closeBatchCommitter() {
        com.google.common.base.Stopwatch stopWatch = Stopwatch.createStarted();
        if (indexingService != null && indexingService.isRunning()) {
            LOG.info("Indexing Service release reference count: {}",
                    referenceCount.get());
            if (referenceCount.decrementAndGet() == 0) {
                LOG.info("Stopping indexingService: {}", referenceCount.get());
                IndexingService temp = indexingService;
                indexingService = null;
                temp.stopAsync().awaitTerminated();
            }
        }
        stopWatch.stop();
        LOG.info("Shutting down (took: {} ms",
                stopWatch.elapsed(TimeUnit.MILLISECONDS));
        LOG.info("Indexing Service reference count: {}", referenceCount.get());
    }

    /**
     * This method is not supported and will throw an
     * {@link UnsupportedOperationException} if invoked. With Google Cloud
     * Search, the target reference id cannot be set.
     *
     * @param targetReferenceField the target field
     */
//    @Override
//    public void setTargetReferenceField(String targetReferenceField) {
//        if (!Strings.isNullOrEmpty(targetReferenceField)) {
//            throw new UnsupportedOperationException(
//                    "Target reference id cannot be set for Google Cloud Search!");
//        }
//    }

    /**
     * This method is not supported and will throw an
     * {@link UnsupportedOperationException} if invoked. With Google Cloud
     * Search, the target content field cannot be set.
     *
     * @param targetContentField the target field
     */
//    @Override
//    public void setTargetContentField(String targetContentField) {
//        if (!Strings.isNullOrEmpty(targetContentField)) {
//            throw new UnsupportedOperationException(
//                    "Target content field cannot be set for Google Cloud Search!");
//        }
//    }

    // TODO(sfruhwald) Find out if equals/hashcode/toString impls are needed (as
    // in other committers)

    static class Helper {
        boolean isConfigInitialized() {
            return Configuration.isInitialized();
        }

        void initConfig(String[] args) throws IOException {
            Configuration.initConfig(args);
        }

        DefaultAcl initDefaultAclFromConfig(IndexingService indexingService) {
            return DefaultAcl.fromConfiguration(indexingService);
        }

        long getCurrentTimeMillis() {
            return System.currentTimeMillis();
        }

        IndexingService createIndexingService()
                throws IOException, GeneralSecurityException {
            return IndexingServiceImpl.Builder.fromConfiguration(
                    Optional.empty(), this.getClass().getName()).build();
        }
    }
}
