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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.LowLevelHttpRequest;
import com.google.api.client.http.LowLevelHttpResponse;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.testing.http.MockHttpTransport;
import com.google.api.client.testing.http.MockLowLevelHttpRequest;
import com.google.api.client.testing.http.MockLowLevelHttpResponse;
import com.google.api.services.cloudsearch.v1.CloudSearch;
import com.norconex.committer.core3.ICommitterRequest;
import com.norconex.committer.core3.UpsertRequest;
import com.norconex.commons.lang.map.Properties;
import com.norconex.commons.lang.xml.XML;

class GoogleCloudSearchCommitterMetadataCompatibilityTest {

        private static final JsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();
        private static final String REFERENCE = "https://example.com/item";

        @TempDir
        File tempDir;

        @Test
        void sourceRepositoryUrlFallsBackToReferenceWhenMappedFieldMissing()
                        throws Exception {
                RecordingTransport transport = new RecordingTransport();
                transport.enqueue(jsonResponse(successfulBatchResponseHeader(),
                                successfulBatchResponseBody("operations/index-1")));

                try (GoogleCloudSearchCommitter subject = new GoogleCloudSearchCommitter(
                                new TestHelper(transport, 1000L))) {
                        XML xml = minimalXml(
                                        new File(tempDir, "placeholder.json").getAbsolutePath());
                        xml.addElement("uploadFormat", "text");
                        xml.addElement("metadata")
                                        .addElement("mapping")
                                        .setAttribute("fromField", "source_url")
                                        .setAttribute("toField", "sourceRepositoryUrl");
                        subject.loadBatchCommitterFromXML(xml);
                        subject.initBatchCommitter();

                        Properties metadata = new Properties();
                        metadata.set(GoogleCloudSearchCommitter.FIELD_CONTENT_TYPE,
                                        "text/plain");

                        List<ICommitterRequest> requests = new ArrayList<>();
                        requests.add(new UpsertRequest(
                                        REFERENCE,
                                        metadata,
                                        new ByteArrayInputStream("x".getBytes(UTF_8))));

                        subject.commitBatch(requests.iterator());

                        String batchBody = transport.getRequests().get(0).getContentAsString();
                        assertThat(batchBody)
                                        .contains("\"sourceRepositoryUrl\":\"" + REFERENCE + "\"");
                }
        }

        @Test
        void metadataDefaultsAndCreateTimeAreAppliedWhenConfigured()
                        throws Exception {
                RecordingTransport transport = new RecordingTransport();
                transport.enqueue(jsonResponse(successfulBatchResponseHeader(),
                                successfulBatchResponseBody("operations/index-1")));

                try (GoogleCloudSearchCommitter subject = new GoogleCloudSearchCommitter(
                                new TestHelper(transport, 1000L))) {
                        XML xml = minimalXml(
                                        new File(tempDir, "placeholder.json").getAbsolutePath());
                        xml.addElement("uploadFormat", "text");
                        XML metadataXml = xml.addElement("metadata");
                        metadataXml.addElement("mapping")
                                        .setAttribute("toField", "objectType")
                                        .setAttribute("defaultValue", "webpage");
                        metadataXml.addElement("mapping")
                                        .setAttribute("toField", "contentLanguage")
                                        .setAttribute("defaultValue", "en-US");
                        metadataXml.addElement("mapping")
                                        .setAttribute("fromField", "created")
                                        .setAttribute("toField", "createTime");
                        subject.loadBatchCommitterFromXML(xml);
                        subject.initBatchCommitter();

                        Properties metadata = new Properties();
                        metadata.set(GoogleCloudSearchCommitter.FIELD_CONTENT_TYPE,
                                        "text/plain");
                        metadata.set("created", "2026-07-14T12:30:00Z");

                        List<ICommitterRequest> requests = new ArrayList<>();
                        requests.add(new UpsertRequest(
                                        REFERENCE,
                                        metadata,
                                        new ByteArrayInputStream("x".getBytes(UTF_8))));

                        subject.commitBatch(requests.iterator());

                        String batchBody = transport.getRequests().get(0).getContentAsString();
                        assertThat(batchBody).contains("\"objectType\":\"webpage\"");
                        assertThat(batchBody).contains("\"contentLanguage\":\"en-US\"");
                        assertThat(batchBody)
                                        .contains("\"createTime\":\"2026-07-14T12:30:00Z\"");
                }
        }

        @Test
        void hashKeywordsAndSearchQualityMetadataAreApplied() throws Exception {
                RecordingTransport transport = new RecordingTransport();
                transport.enqueue(jsonResponse(successfulBatchResponseHeader(),
                                successfulBatchResponseBody("operations/index-1")));

                try (GoogleCloudSearchCommitter subject = new GoogleCloudSearchCommitter(
                                new TestHelper(transport, 1000L))) {
                        XML xml = minimalXml(
                                        new File(tempDir, "placeholder.json").getAbsolutePath());
                        xml.addElement("uploadFormat", "text");
                        XML metadataXml = xml.addElement("metadata");
                        metadataXml.addElement("mapping")
                                        .setAttribute("fromField", "checksum")
                                        .setAttribute("toField", "hash");
                        metadataXml.addElement("mapping")
                                        .setAttribute("fromField", "tags")
                                        .setAttribute("toField", "keywords");
                        metadataXml.addElement("mapping")
                                        .setAttribute("fromField", "quality")
                                        .setAttribute("toField", "searchQualityMetadata")
                                        .setAttribute("defaultValue", "0.0");
                        subject.loadBatchCommitterFromXML(xml);
                        subject.initBatchCommitter();

                        Properties metadata = new Properties();
                        metadata.set(GoogleCloudSearchCommitter.FIELD_CONTENT_TYPE,
                                        "text/plain");
                        metadata.set("checksum", "abc123");
                        metadata.set("tags", "cloud", "migration", "ai");

                        List<ICommitterRequest> requests = new ArrayList<>();
                        requests.add(new UpsertRequest(
                                        REFERENCE,
                                        metadata,
                                        new ByteArrayInputStream("x".getBytes(UTF_8))));

                        subject.commitBatch(requests.iterator());

                        String batchBody = transport.getRequests().get(0).getContentAsString();
                        assertThat(batchBody).contains("\"hash\":\"abc123\"");
                        assertThat(batchBody).contains(
                                        "\"keywords\":[\"cloud\",\"migration\",\"ai\"]");
                        assertThat(batchBody).contains(
                                        "\"searchQualityMetadata\":{\"quality\":0.0}");
                }
        }

        @Test
        void structuredDataMappingBuildsDeclaredTypedValues()
                        throws Exception {
                RecordingTransport transport = new RecordingTransport();
                transport.enqueue(jsonResponse(successfulBatchResponseHeader(),
                                successfulBatchResponseBody("operations/index-1")));

                try (GoogleCloudSearchCommitter subject = new GoogleCloudSearchCommitter(
                                new TestHelper(transport, 1000L))) {
                        XML xml = minimalXml(
                                        new File(tempDir, "placeholder.json").getAbsolutePath());
                        xml.addElement("uploadFormat", "text");
                        XML structuredDataXml = xml.addElement("structuredData");
                        structuredDataXml.addElement("mapping")
                                        .setAttribute("field", "isPublished")
                                        .setAttribute("type", "boolean");
                        structuredDataXml.addElement("mapping")
                                        .setAttribute("field", "count")
                                        .setAttribute("type", "integer");
                        structuredDataXml.addElement("mapping")
                                        .setAttribute("field", "ratio")
                                        .setAttribute("type", "double");
                        structuredDataXml.addElement("mapping")
                                        .setAttribute("field", "timestamp")
                                        .setAttribute("type", "timestamp");
                        structuredDataXml.addElement("mapping")
                                        .setAttribute("field", "publishDate")
                                        .setAttribute("type", "date");
                        structuredDataXml.addElement("mapping")
                                        .setAttribute("field", "status")
                                        .setAttribute("type", "enum");
                        subject.loadBatchCommitterFromXML(xml);
                        subject.initBatchCommitter();

                        Properties metadata = new Properties();
                        metadata.set(GoogleCloudSearchCommitter.FIELD_CONTENT_TYPE,
                                        "text/plain");
                        metadata.set("isPublished", "true");
                        metadata.set("count", "123");
                        metadata.set("ratio", "1.5");
                        metadata.set("timestamp", "2026-07-14T12:30:00Z");
                        metadata.set("publishDate", "2026-07-14");
                        metadata.set("status", "active");
                        // Not declared in structuredData: must default to text.
                        metadata.set("untyped", "some-bare-word");

                        List<ICommitterRequest> requests = new ArrayList<>();
                        requests.add(new UpsertRequest(
                                        REFERENCE,
                                        metadata,
                                        new ByteArrayInputStream("x".getBytes(UTF_8))));

                        subject.commitBatch(requests.iterator());

                        String batchBody = transport.getRequests().get(0).getContentAsString();
                        assertThat(batchBody).contains("\"booleanValue\":true");
                        assertThat(batchBody)
                                        .contains("\"integerValues\":{\"values\":[\"123\"]}");
                        assertThat(batchBody)
                                        .contains("\"doubleValues\":{\"values\":[1.5]}");
                        assertThat(batchBody).contains(
                                        "\"timestampValues\":{\"values\":[\"2026-07-14T12:30:00Z\"]}");
                        assertThat(batchBody).contains(
                                        "\"dateValues\":{\"values\":[{\"day\":14,\"month\":7,\"year\":2026}]}");
                        assertThat(batchBody)
                                        .contains("\"enumValues\":{\"values\":[\"active\"]}");
                        assertThat(batchBody).contains(
                                        "\"untyped\"");
                        assertThat(batchBody)
                                        .contains("\"textValues\":{\"values\":[\"some-bare-word\"]}");
                }
        }

        @Test
        void undeclaredFieldsDefaultToTextWithoutGuessing() throws Exception {
                RecordingTransport transport = new RecordingTransport();
                transport.enqueue(jsonResponse(successfulBatchResponseHeader(),
                                successfulBatchResponseBody("operations/index-1")));

                try (GoogleCloudSearchCommitter subject = new GoogleCloudSearchCommitter(
                                new TestHelper(transport, 1000L))) {
                        XML xml = minimalXml(
                                        new File(tempDir, "placeholder.json").getAbsolutePath());
                        xml.addElement("uploadFormat", "text");
                        subject.loadBatchCommitterFromXML(xml);
                        subject.initBatchCommitter();

                        Properties metadata = new Properties();
                        metadata.set(GoogleCloudSearchCommitter.FIELD_CONTENT_TYPE,
                                        "text/plain");
                        metadata.set("count", "123");
                        metadata.set("publishDate", "2026-07-14");

                        List<ICommitterRequest> requests = new ArrayList<>();
                        requests.add(new UpsertRequest(
                                        REFERENCE,
                                        metadata,
                                        new ByteArrayInputStream("x".getBytes(UTF_8))));

                        subject.commitBatch(requests.iterator());

                        String batchBody = transport.getRequests().get(0).getContentAsString();
                        assertThat(batchBody)
                                        .contains("\"textValues\":{\"values\":[\"123\"]}");
                        assertThat(batchBody)
                                        .contains("\"textValues\":{\"values\":[\"2026-07-14\"]}");
                        assertThat(batchBody).doesNotContain("integerValues");
                        assertThat(batchBody).doesNotContain("dateValues");
                }
        }

        @Test
        void enumFieldExceedingGoogleCapFallsBackToText() throws Exception {
                RecordingTransport transport = new RecordingTransport();
                transport.enqueue(jsonResponse(successfulBatchResponseHeader(),
                                successfulBatchResponseBody("operations/index-1")));

                try (GoogleCloudSearchCommitter subject = new GoogleCloudSearchCommitter(
                                new TestHelper(transport, 1000L))) {
                        XML xml = minimalXml(
                                        new File(tempDir, "placeholder.json").getAbsolutePath());
                        xml.addElement("uploadFormat", "text");
                        xml.addElement("structuredData")
                                        .addElement("mapping")
                                        .setAttribute("field", "keyword")
                                        .setAttribute("type", "enum");
                        subject.loadBatchCommitterFromXML(xml);
                        subject.initBatchCommitter();

                        Properties metadata = new Properties();
                        metadata.set(GoogleCloudSearchCommitter.FIELD_CONTENT_TYPE,
                                        "text/plain");
                        List<String> manyValues = new ArrayList<>();
                        for (int i = 0; i < 36; i++) {
                                manyValues.add("tag" + i);
                        }
                        metadata.set("keyword", manyValues.toArray(new String[0]));

                        List<ICommitterRequest> requests = new ArrayList<>();
                        requests.add(new UpsertRequest(
                                        REFERENCE,
                                        metadata,
                                        new ByteArrayInputStream("x".getBytes(UTF_8))));

                        subject.commitBatch(requests.iterator());

                        String batchBody = transport.getRequests().get(0).getContentAsString();
                        assertThat(batchBody).doesNotContain("enumValues");
                        assertThat(batchBody).contains("\"textValues\"");
                }
        }

        private XML minimalXml(String secretKeyPath) {
                XML xml = new XML("committer");
                xml.addElement("secretKeyPath", secretKeyPath);
                xml.addElement("dataSourceId", "datasource-id");
                xml.addElement("apiEndpoint", "https://mock.local/");
                return xml;
        }

        private MockLowLevelHttpResponse jsonResponse(String contentType,
                        String body) {
                return new MockLowLevelHttpResponse()
                                .setStatusCode(200)
                                .setContentType(contentType)
                                .setContent(body);
        }

        private String successfulBatchResponseHeader() {
                return "multipart/mixed; boundary=batch_test";
        }

        private String successfulBatchResponseBody(String operationName) {
                return "--batch_test\r\n"
                                + "Content-Type: application/http\r\n"
                                + "Content-ID: response-1\r\n\r\n"
                                + "HTTP/1.1 200 OK\r\n"
                                + "Content-Type: application/json; charset=UTF-8\r\n\r\n"
                                + "{\"name\":\""
                                + operationName
                                + "\",\"done\":true}\r\n"
                                + "--batch_test--\r\n";
        }

        private static HttpRequestInitializer noOpInitializer() {
                return request -> {
                        // no-op
                };
        }

        private static final class TestHelper
                        extends GoogleCloudSearchCommitter.Helper {
                private final HttpTransport transport;
                private final long currentTimeMillis;

                private TestHelper(HttpTransport transport, long currentTimeMillis) {
                        this.transport = transport;
                        this.currentTimeMillis = currentTimeMillis;
                }

                @Override
                CloudSearch createCloudSearch(String applicationName,
                                String secretKeyPath,
                                String apiEndpoint,
                                GoogleCloudSearchCommitter.HttpRequestOptions httpOptions) {
                        return new CloudSearch.Builder(
                                        transport,
                                        JSON_FACTORY,
                                        noOpInitializer())
                                        .setApplicationName(applicationName)
                                        .setRootUrl(apiEndpoint)
                                        .build();
                }

                @Override
                long currentTimeMillis() {
                        return currentTimeMillis;
                }
        }

        private static final class RecordingTransport extends MockHttpTransport {
                private final Deque<MockLowLevelHttpResponse> responses = new java.util.ArrayDeque<>();
                private final List<MockLowLevelHttpRequest> requests = new ArrayList<>();
                private final List<String> urls = new ArrayList<>();

                private void enqueue(MockLowLevelHttpResponse response) {
                        responses.add(response);
                }

                private List<MockLowLevelHttpRequest> getRequests() {
                        return requests;
                }

                @Override
                public LowLevelHttpRequest buildRequest(String method, String url) {
                        MockLowLevelHttpRequest request = new MockLowLevelHttpRequest(url) {
                                @Override
                                public LowLevelHttpResponse execute() {
                                        if (responses.isEmpty()) {
                                                throw new IllegalStateException(
                                                                "No response enqueued for request to "
                                                                                + getUrl());
                                        }
                                        return responses.removeFirst();
                                }
                        };
                        requests.add(request);
                        urls.add(url);
                        return request;
                }
        }
}