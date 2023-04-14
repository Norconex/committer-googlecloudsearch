/* Copyright 2010-2023 Norconex Inc.
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;

import javax.xml.stream.XMLStreamException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import com.google.api.services.cloudsearch.v1.model.Item;
import com.google.api.services.cloudsearch.v1.model.ItemAcl;
import com.google.api.services.cloudsearch.v1.model.ItemMetadata;
import com.google.api.services.cloudsearch.v1.model.Schema;
import com.google.enterprise.cloudsearch.sdk.indexing.Acl;
import com.google.enterprise.cloudsearch.sdk.indexing.DefaultAcl;
import com.google.enterprise.cloudsearch.sdk.indexing.IndexingItemBuilder.ItemType;
import com.google.enterprise.cloudsearch.sdk.indexing.IndexingService;
import com.norconex.committer.core3.CommitterException;
import com.norconex.committer.core3.DeleteRequest;
import com.norconex.committer.core3.ICommitterRequest;
import com.norconex.committer.core3.UpsertRequest;
import com.norconex.committer.googlecloudsearch.GoogleCloudSearchCommitter.Helper;
import com.norconex.commons.lang.map.Properties;
import com.norconex.commons.lang.xml.XML;

class GoogleCloudSearchCommitterTest {
    private static final String CONTENT = "Test1234567890";
    private static final String CONTENT_BASE64 = "VGVzdDEyMzQ1Njc4OTA=";
    private static final String URL = "http://x.yz/abc";
    private static final String MIME_TEXT = "text/plain";
    private static final String MIME_PDF = "text/pdf";
    private static final long CURRENT_MILLIS = 123456789;
    private static final boolean APPLY_DOMAIN_ACLS = true;
    private static final boolean CONTENT_ERROR = true;

//    private SetupConfigRule setupConfig = SetupConfigRule.uninitialized();

    @Mock
    private Helper mockHelper;
    @Mock
    private XML mockConfig;
//    @Mock
//    private XML mockConfigWriter;
    @Mock
    private DefaultAcl mockDefaultAcl;
//    @Mock
//    private Appender appender;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private IndexingService mockIndexingService;
//
//    @Captor
//    private ArgumentCaptor<AbstractInputStreamContent> itemContentCaptor;
//    @Captor
//    private ArgumentCaptor<Item> itemCaptor;
//    @Captor
//    private ArgumentCaptor<LogEvent> loggingCaptor;

    private GoogleCloudSearchCommitter subject;

    @BeforeEach
    void setUp() throws Exception {
//        getLogConfig().addAppender(appender);        
        
        MockitoAnnotations.openMocks(this);
        when(mockHelper.createIndexingService())
                .thenReturn(mockIndexingService);
        when(mockHelper.initDefaultAclFromConfig(mockIndexingService))
                .thenReturn(mockDefaultAcl);
        when(mockHelper.getCurrentTimeMillis()).thenReturn(CURRENT_MILLIS);
        when(mockHelper.isConfigInitialized()).thenReturn(true);
        when(mockConfig.getString(
                eq(GoogleCloudSearchCommitter.CONFIG_KEY_CONFIG_FILE), any()))
                        .thenReturn("/path/to/config");
        when(mockConfig.getString(
                eq(GoogleCloudSearchCommitter.CONFIG_KEY_UPLOAD_FORMAT), any()))
                        .thenReturn("RAW");
        when(mockDefaultAcl.applyToIfEnabled(any())).thenReturn(true);
        when(mockIndexingService.getSchema()).thenReturn(new Schema());
        subject = new GoogleCloudSearchCommitter(mockHelper);
    }

//    @AfterEach
//    void teardown() {
//        getLogConfig().removeAppender(appender.getName());
//    }
//    
//    private AbstractConfiguration getLogConfig() {
//        final LoggerContext logCtx = (LoggerContext) LogManager
//                .getContext(true);
//        return (AbstractConfiguration) logCtx.getConfiguration();
//    }

    @Test
    void verifyConstantValues() {
        assertThat(GoogleCloudSearchCommitter.FIELD_BINARY_CONTENT)
                .isEqualTo("binaryContent");
        assertThat(GoogleCloudSearchCommitter.FIELD_CONTENT_TYPE)
                .isEqualTo("document.contentType");
        assertThat(GoogleCloudSearchCommitter.CONFIG_KEY_CONFIG_FILE)
                .isEqualTo("configFilePath");
        assertThat(GoogleCloudSearchCommitter.CONFIG_KEY_UPLOAD_FORMAT)
                .isEqualTo("uploadFormat");
    }

    @Test
    void helper_smokeTest()
            throws IOException, GeneralSecurityException, CommitterException {
        // TODO(jlacey): We might be able to build a convincing service account
        // JSON stub
        // in order to instantiate the production IndexingService, too, but stub
        // it for now.
        Helper helper = spy(new Helper());
        doReturn(mockIndexingService).when(helper).createIndexingService();

        assertEquals(System.currentTimeMillis(), helper.getCurrentTimeMillis(),
                100.0);

        final ICommitterRequest[] arr = {};
        Iterator<ICommitterRequest> it = Arrays.asList(arr).iterator();

        subject = new GoogleCloudSearchCommitter(helper);
        subject.commitBatch(it);
    }

//    @Test
//    void fullLifecycle() throws IOException, GeneralSecurityException,
//            XMLStreamException, CommitterException {
//        setupConfig.initConfig(new java.util.Properties());
//        when(mockHelper.isConfigInitialized()).thenReturn(false);
//        when(mockIndexingService.isRunning()).thenReturn(true);
//        subject.loadBatchCommitterFromXML(mockConfig);
//        subject.commitBatch(Arrays.asList(addOperation(URL, new Properties()),
//                deleteOperation(URL, new Properties()),
//                addOperation(URL, new Properties())).iterator());
////        subject.commitComplete();
//        subject.saveBatchCommitterToXML(mockConfigWriter);
//        InOrder inOrder = Mockito.inOrder(mockHelper, mockIndexingService);
//        inOrder.verify(mockHelper).isConfigInitialized();
//        inOrder.verify(mockHelper).initConfig(any());
//        inOrder.verify(mockHelper).createIndexingService();
//        inOrder.verify(mockIndexingService).startAsync();
//        inOrder.verify(mockHelper)
//                .initDefaultAclFromConfig(mockIndexingService);
//        inOrder.verify(mockIndexingService, times(1)).indexItemAndContent(any(),
//                any(), any(), any(), any());
//        inOrder.verify(mockIndexingService).deleteItem(any(), any(), any());
//        inOrder.verify(mockIndexingService, times(1)).indexItemAndContent(any(),
//                any(), any(), any(), any());
//        inOrder.verify(mockIndexingService).isRunning();
//        inOrder.verify(mockIndexingService).stopAsync();
//        inOrder.verifyNoMoreInteractions();
//    }

//    @Test
//    void initShouldInitializeConfig() throws IOException, CommitterException {
//        setupConfig.initConfig(new java.util.Properties());
//        when(mockHelper.isConfigInitialized()).thenReturn(false);
//        subject.loadBatchCommitterFromXML(mockConfig);
//        subject.commitBatch(
//                Arrays.asList(addOperationContentStreamThrowsException(URL, new Properties()))
//                        .iterator());
//        verify(mockHelper, times(1)).isConfigInitialized();
//        verify(mockHelper)
//                .initConfig(new String[] { "-Dconfig=/path/to/config" });
//    }
//
//    @Test
//    void initShouldNotReinitializeConfigWhenAlreadyInitialized()
//            throws IOException, CommitterException {
//        setupConfig.initConfig(new java.util.Properties());
//        subject.loadBatchCommitterFromXML(mockConfig);
//        subject.commitBatch(
//                Arrays.asList(addOperationContentStreamThrowsException(URL, new Properties()))
//                        .iterator());
//        verify(mockHelper, times(1)).isConfigInitialized();
//        verify(mockHelper, times(0)).initConfig(any());
//    }
//
//    @Test
//    void initFailWhenSDKConfigInitializationFailsWithIOException()
//            throws Exception {
//        when(mockHelper.isConfigInitialized()).thenReturn(false);
//        doThrow(new IOException()).when(mockHelper).initConfig(any());
////        thrown.expect(CommitterException.class);
////        thrown.expectMessage("Initialization of SDK configuration failed.");
//        subject.loadBatchCommitterFromXML(mockConfig);
//
//        assertThatExceptionOfType(CommitterException.class)
//                .isThrownBy(() -> subject.commitBatch(Arrays
//                        .asList(addOperationContentStreamThrowsException(URL, new Properties()))
//                        .iterator()))
//                .withMessage("Initialization of SDK configuration failed.");
//
//    }
//
//    @Test
//    void loadFromXmlFailsWithMissingConfigPath() {
//        when(mockConfig.getString(
//                GoogleCloudSearchCommitter.CONFIG_KEY_CONFIG_FILE, null))
//                        .thenReturn(null);
////        thrown.expect(CommitterException.class);
////        thrown.expectMessage("Missing required plugin configuration entry: "
////                + GoogleCloudSearchCommitter.CONFIG_KEY_CONFIG_FILE);
//        assertThatExceptionOfType(CommitterException.class)
//                .isThrownBy(() -> subject.loadBatchCommitterFromXML(mockConfig))
//                .withMessage("Missing required plugin configuration entry: "
//                        + GoogleCloudSearchCommitter.CONFIG_KEY_CONFIG_FILE);
//    }
//
//    @Test
//    void initCreatesIndexingService() throws Exception {
//        setupConfig.initConfig(new java.util.Properties());
//        subject.loadBatchCommitterFromXML(mockConfig);
//        subject.commitBatch(
//                Arrays.asList(addOperationContentStreamThrowsException(URL, new Properties()))
//                        .iterator());
//        verify(mockHelper).createIndexingService();
//    }
//
//    @Test
//    void initFailsWhenCreatingIndexingServiceFailsWithSecurityException()
//            throws Exception {
//        when(mockHelper.createIndexingService())
//                .thenThrow(new GeneralSecurityException());
//
//        assertThatExceptionOfType(CommitterException.class)
//                .isThrownBy(() -> subject.commitBatch(Arrays
//                        .asList(addOperationContentStreamThrowsException(URL, new Properties()))
//                        .iterator()))
//                .withMessage("failed to create IndexingService");
//    }
//
//    @Test
//    void initFailsWhenCreatingIndexingServiceFailsWithIOException()
//            throws Exception {
//        when(mockHelper.createIndexingService()).thenThrow(new IOException());
//
//        assertThatExceptionOfType(CommitterException.class)
//                .isThrownBy(() -> subject.commitBatch(Arrays
//                        .asList(addOperationContentStreamThrowsException(URL, new Properties()))
//                        .iterator()))
//                .withMessage("failed to create IndexingService");
//    }
//
//    @Test
//    void loadFromXmlFailsWhenUploadFormatHasInvalidValue() {
//        when(mockConfig.getString(
//                GoogleCloudSearchCommitter.CONFIG_KEY_UPLOAD_FORMAT,
//                UploadFormat.RAW.name())).thenReturn("Invalid_Value");
//
//        assertThatExceptionOfType(CommitterException.class)
//                .isThrownBy(() -> subject.loadBatchCommitterFromXML(mockConfig))
//                .withMessage("Unknown value for '"
//                        + GoogleCloudSearchCommitter.CONFIG_KEY_UPLOAD_FORMAT
//                        + "'");
//    }
//
//    @Test
//    void initStartsIndexingService() throws Exception {
//        setupConfig.initConfig(new java.util.Properties());
//        subject.loadBatchCommitterFromXML(mockConfig);
//        subject.commitBatch(
//                Arrays.asList(addOperationContentStreamThrowsException(
//                        URL, new Properties()))
//                        .iterator());
//        verify(mockIndexingService.startAsync()).awaitRunning();
//    }

    // TODO: verify this
//    @Test
//    void addFailsWhenRawUploadModeIsSelectedAndBinaryContentIsNotInValidBase64()
//            throws CommitterException {
//        setupConfig.initConfig(new java.util.Properties());
//        subject.loadBatchCommitterFromXML(mockConfig);
//        subject.commitBatch(
//                Arrays.asList(addOperation(URL, new Properties())).iterator());
//        verify(appender, atLeastOnce()).append(loggingCaptor.capture());
//        List<LogEvent> logEvents = loggingCaptor.getAllValues();
//        assertEquals("Exception caught while committing: " + URL,
//                logEvents.get(4).getMessage());
//        assertEquals("Binary content field is missing or invalid. "
//                + "Please configure BinaryContentTagger and make sure the '"
//                + GoogleCloudSearchCommitter.FIELD_BINARY_CONTENT
//                + "' field is left untouched "
//                + "(e.g. watch out for KeepOnlyTagger)",
//                logEvents.get(5).getMessage());
//    }

    // TODO: verify this
//    @Test
//    void addFailsWhenRawUploadModeIsSelectedAndBinaryContentFieldIsMissing() 
//            throws CommitterException {
//        setupConfig.initConfig(new java.util.Properties());
//        subject.loadBatchCommitterFromXML(mockConfig);
//        subject.commitBatch(
//                Arrays.asList(addOperation(URL, new Properties())).iterator());
//        verify(appender, atLeastOnce()).append(loggingCaptor.capture());
//        List<LogEvent> loggingEvents = loggingCaptor.getAllValues();
//        assertEquals("Exception caught while committing: " + URL,
//                loggingEvents.get(4).getMessage());
//        assertEquals(
//                "Binary content field is missing or invalid. Please configure BinaryContentTagger"
//                        + " and make sure the '"
//                        + GoogleCloudSearchCommitter.FIELD_BINARY_CONTENT
//                        + "' field is left untouched (e.g. watch out for KeepOnlyTagger)",
//                loggingEvents.get(5).getMessage());
//    }

//    @Test
//    void addFailsWhenTextUploadModeIsSelectedAndTextContentStreamThrowsException() 
//            throws CommitterException, IOException {
//        setupConfig.initConfig(new java.util.Properties());
//        when(mockConfig.getString(
//                eq(GoogleCloudSearchCommitter.CONFIG_KEY_UPLOAD_FORMAT), any()))
//                        .thenReturn("Text");
//        subject.loadBatchCommitterFromXML(mockConfig);
//        subject.commitBatch(
//                Arrays.asList(addOperationContentStreamThrowsException(
//                        URL, new Properties()))
//                .iterator());
//
//        verify(appender, atLeastOnce()).append(loggingCaptor.capture());
//        List<LogEvent> loggingEvents = loggingCaptor.getAllValues();
//        assertEquals("Exception caught while committing: " + URL,
//                loggingEvents.get(4).getMessage());
//        assertEquals(
//                "Text content ('content') field is missing, please enable the index-basic plugin!",
//                loggingEvents.get(5).getMessage());
//    }

//    @Test
//    void addFailsWhenTextUploadModeIsSelectedAndTextContentStreamReadThrowsException()
//            throws Exception {
//        setupConfig.initConfig(new java.util.Properties());
//        when(mockDefaultAcl.applyToIfEnabled(any())).thenReturn(false);
//        when(mockConfig.getString(
//                eq(GoogleCloudSearchCommitter.CONFIG_KEY_UPLOAD_FORMAT), any()))
//                        .thenReturn("Text");
//        subject.loadBatchCommitterFromXML(mockConfig);
//        
//        assertThatExceptionOfType(IOException.class)
//                .isThrownBy(() -> subject.commitBatch(
//                        Arrays.asList(
//                                addOperationContentStreamThrowsException(
//                                        URL, new Properties()))
//                        .iterator()));
//        
//        verify(mockIndexingService).indexItemAndContent(
//                eq(goldenItem(APPLY_DOMAIN_ACLS, MIME_PDF)),
//                itemContentCaptor.capture(), eq(null), eq(ContentFormat.TEXT),
//                eq(RequestMode.ASYNCHRONOUS));
////        thrown.expect(IOException.class);
//        itemContentCaptor.getValue().getInputStream().read(new byte[1024]);
//    }
//
//    @Test
//    void addFailsWhenContentTypeFieldIsMissing() {
//        Properties metadata = new Properties();
//        metadata.add(GoogleCloudSearchCommitter.FIELD_CONTENT_TYPE, null);
//        
//        setupConfig.initConfig(new java.util.Properties());
//        subject.loadBatchCommitterFromXML(mockConfig);
////        thrown.expect(CommitterException.class);
////        thrown.expectMessage("Content type field ('"
////                + GoogleCloudSearchCommitter.FIELD_CONTENT_TYPE
////                + "') is missing!");
//        
//        assertThatExceptionOfType(CommitterException.class)
//                .isThrownBy(() -> subject.commitBatch(
//                        Arrays.asList(addOperationContentStreamThrowsException(URL, metadata))
//                                .iterator()))
//                .withMessage("Content type field ('"
//                        + GoogleCloudSearchCommitter.FIELD_CONTENT_TYPE
//                        + "') is missing!");
//        //addOperation(URL, null, CONTENT_BASE64, CONTENT_ERROR)
//    }

//    @Test
//    void addShouldFailWhenBinaryContentIsEmpty() throws CommitterException {
//        Properties metadata = new Properties();
//        metadata.add(GoogleCloudSearchCommitter.FIELD_BINARY_CONTENT, null);
//        
//        setupConfig.initConfig(new java.util.Properties());
//        subject.loadBatchCommitterFromXML(mockConfig);
//        subject.commitBatch(
//                Arrays.asList(addOperation(URL, metadata)).iterator());
//        verify(appender, atLeastOnce()).append(loggingCaptor.capture());
//        List<LogEvent> loggingEvents = loggingCaptor.getAllValues();
//        assertEquals("Exception caught while committing: " + URL,
//                loggingEvents.get(4).getMessage());
//        assertEquals(
//                "Binary content field is missing or invalid. Please configure BinaryContentTagger"
//                        + " and make sure the '"
//                        + GoogleCloudSearchCommitter.FIELD_BINARY_CONTENT
//                        + "' field is left untouched (e.g. watch out for KeepOnlyTagger)",
//                loggingEvents.get(5).getMessage());
//    }

    

    private ICommitterRequest addOperation(String url, Properties metadata) {
        return new UpsertRequest(url, metadata,
                new ByteArrayInputStream(CONTENT.getBytes()));
    }

    private ICommitterRequest addOperationContentStreamThrowsException(String url,
            Properties metadata) throws IOException {

        InputStream contentStream = mock(InputStream.class);
        when(contentStream.read(any())).thenThrow(new IOException());

        return new UpsertRequest(url, metadata, contentStream);
    }

    private ICommitterRequest deleteOperation(String url, Properties metadata) {
        return new DeleteRequest(url, metadata);
    }

    private Item goldenItem(boolean applyDomainAcl, String mimeType) {
        Item item = new Item().setName(URL)
                .setItemType(ItemType.CONTENT_ITEM.name())
                .setMetadata(new ItemMetadata().setSourceRepositoryUrl(URL)
                        .setMimeType(mimeType));
        if (applyDomainAcl) {
            item.setAcl(new ItemAcl().setReaders(
                    Collections.singletonList(Acl.getCustomerPrincipal())));
        }
        return item;
    }
}
