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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.tomakehurst.wiremock.WireMockServer;
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
import com.norconex.committer.core3.DeleteRequest;
import com.norconex.committer.core3.ICommitterRequest;
import com.norconex.committer.core3.UpsertRequest;
import com.norconex.commons.lang.map.Properties;
import com.norconex.commons.lang.xml.XML;

class GoogleCloudSearchCommitterTest {

        private static final JsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();
    private static final String CONTENT = "test body";
    private static final String CONTENT_BASE64 = Base64.getEncoder().encodeToString(CONTENT.getBytes(UTF_8));
    private static final String REFERENCE = "https://example.com/path?q=1";

    @TempDir
    File tempDir;

    @Test
    void loadFromXmlAndSaveToXmlSupportAclMappings() throws Exception {
        try (GoogleCloudSearchCommitter subject = new GoogleCloudSearchCommitter()) {
            XML xml = new XML("committer");
            xml.addElement("secretKeyPath", "/tmp/service-account.json");
            xml.addElement("dataSourceId", "datasource-id");
            XML aclXml = xml.addElement("acl");
            aclXml.addElement("mapping")
                    .setAttribute("fromField", "acl.reader")
                    .setAttribute("target", "readers")
                    .setAttribute("principalType", "user");
            aclXml.addElement("inherit")
                    .setAttribute("fromField", "parentReference")
                    .setAttribute("aclInheritanceType", "BOTH_PERMIT");

            subject.loadBatchCommitterFromXML(xml);

            XML saved = new XML("committer");
            subject.saveBatchCommitterToXML(saved);

            assertThat(saved.getString("secretKeyPath", null))
                    .isEqualTo("/tmp/service-account.json");
            assertThat(saved.getString("dataSourceId", null))
                    .isEqualTo("datasource-id");
            assertThat(saved.getXMLList("acl/mapping")).hasSize(1);
            assertThat(saved.getXML("acl/mapping")
                    .getString("@principalType", null))
                    .isEqualTo("user");
            assertThat(saved.getXML("acl/inherit")
                    .getString("@aclInheritanceType", null))
                    .isEqualTo("BOTH_PERMIT");
        }
    }

    @Test
    void commitBatchUsesGoogleBatchRequestWithMockHttpTransport()
            throws Exception {
        RecordingTransport transport = new RecordingTransport();
        transport.enqueue(jsonResponse(successfulBatchResponseHeader(),
                successfulBatchResponseBody("operations/index-1")));

        try (GoogleCloudSearchCommitter subject = new GoogleCloudSearchCommitter(
                new TestHelper(transport, 1000L))) {
            XML xml = minimalXml(
                    new File(tempDir, "placeholder.json").getAbsolutePath(),
                    "https://mock.local/");
            xml.addElement("uploadFormat", "text");
            XML aclXml = xml.addElement("acl");
            aclXml.addElement("mapping")
                    .setAttribute("fromField", "acl.reader")
                    .setAttribute("target", "readers")
                    .setAttribute("principalType", "user");
            subject.loadBatchCommitterFromXML(xml);
            subject.initBatchCommitter();

            Properties metadata = new Properties();
            metadata.set("title", "Example title");
            metadata.set("objectType", "webpage");
            metadata.set("acl.reader", "reader@example.com");
            metadata.set(GoogleCloudSearchCommitter.FIELD_CONTENT_TYPE, "text/plain");

            List<ICommitterRequest> requests = new ArrayList<>();
            requests.add(new UpsertRequest(
                    REFERENCE,
                    metadata,
                    new ByteArrayInputStream(CONTENT.getBytes(UTF_8))));
            requests.add(new DeleteRequest(REFERENCE + "/delete", new Properties()));

            subject.commitBatch(requests.iterator());

            assertThat(transport.getUrls()).contains("https://mock.local/batch");
            String batchBody = transport.getRequests().get(0).getContentAsString();
            assertThat(batchBody).contains(":index");
            assertThat(batchBody).contains("DELETE");
            assertThat(batchBody).contains("Example title");
            assertThat(batchBody).contains("reader@example.com");
        }
    }

    @Test
    void rawUploadCanRunAgainstWireMockEndpoint() throws Exception {
        WireMockServer server = new WireMockServer();
        try {
            server.start();
            server.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(
                    com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo(
                            "/oauth2/v4/token"))
                    .willReturn(com.github.tomakehurst.wiremock.client.WireMock.aResponse()
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"access_token\":\"token\","
                                    + "\"token_type\":\"Bearer\","
                                    + "\"expires_in\":3600}")));
            server.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(
                    com.github.tomakehurst.wiremock.client.WireMock.urlMatching(
                            "/v1/indexing/datasources/.*/items/.*:upload"))
                    .willReturn(com.github.tomakehurst.wiremock.client.WireMock.aResponse()
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"name\":\"uploadItems/test-upload\"}")));
            server.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(
                    com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching(
                            "/upload/v1/media/.*"))
                    .willReturn(com.github.tomakehurst.wiremock.client.WireMock.aResponse()
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"resourceName\":\"uploadItems/test-upload\"}")));
            server.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(
                    com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo("/batch"))
                    .willReturn(com.github.tomakehurst.wiremock.client.WireMock.aResponse()
                            .withHeader(
                                    "Content-Type", successfulBatchResponseHeader())
                            .withBody(successfulBatchResponseBody(
                                    "operations/index-raw"))));

            File secretFile = createServiceAccountJson(server.baseUrl());

            try (GoogleCloudSearchCommitter subject = new GoogleCloudSearchCommitter()) {
                XML xml = minimalXml(secretFile.getAbsolutePath(), server.baseUrl() + "/");
                subject.loadBatchCommitterFromXML(xml);
                subject.initBatchCommitter();

                Properties metadata = new Properties();
                metadata.set(GoogleCloudSearchCommitter.FIELD_BINARY_CONTENT,
                        CONTENT_BASE64);
                metadata.set(GoogleCloudSearchCommitter.FIELD_CONTENT_TYPE,
                        "text/plain");
                metadata.set("title", "Raw title");

                List<ICommitterRequest> requests = new ArrayList<>();
                requests.add(new UpsertRequest(
                        REFERENCE,
                        metadata,
                        new ByteArrayInputStream("ignored".getBytes(UTF_8))));

                subject.commitBatch(requests.iterator());
            }

            server.verify(1, com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(
                    com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo(
                            "/oauth2/v4/token")));
            server.verify(1, com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(
                    com.github.tomakehurst.wiremock.client.WireMock.urlMatching(
                            "/v1/indexing/datasources/.*/items/.*:upload")));
            server.verify(1, com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(
                    com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching(
                            "/upload/v1/media/.*")));
            server.verify(1, com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(
                    com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo("/batch")));
        } finally {
            server.stop();
        }
    }

    private XML minimalXml(String secretKeyPath, String apiEndpoint) {
        XML xml = new XML("committer");
        xml.addElement("secretKeyPath", secretKeyPath);
        xml.addElement("dataSourceId", "datasource-id");
        xml.addElement("apiEndpoint", apiEndpoint);
        return xml;
    }

    private File createServiceAccountJson(String baseUrl)
            throws IOException, GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();

        String privateKey = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64,
                        "\n".getBytes(StandardCharsets.US_ASCII))
                        .encodeToString(keyPair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";

        String json = "{"
                + "\"type\":\"service_account\","
                + "\"project_id\":\"test-project\","
                + "\"private_key_id\":\"test-key\","
                + "\"private_key\":\""
                + privateKey.replace("\n", "\\n")
                + "\","
                + "\"client_email\":"
                + "\"test@test-project.iam.gserviceaccount.com\","
                + "\"client_id\":\"1234567890\","
                + "\"token_uri\":\""
                + baseUrl
                + "/oauth2/v4/token\""
                + "}";

        File secretFile = new File(tempDir, "service-account.json");
        Files.write(secretFile.toPath(), json.getBytes(UTF_8));
        return secretFile;
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

    private static final class TestHelper extends GoogleCloudSearchCommitter.Helper {
        private final HttpTransport transport;
        private final long currentTimeMillis;

        private TestHelper(HttpTransport transport, long currentTimeMillis) {
            this.transport = transport;
            this.currentTimeMillis = currentTimeMillis;
        }

        @Override
        CloudSearch createCloudSearch(String applicationName, String secretKeyPath,
                String apiEndpoint) {
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

        private HttpRequestInitializer noOpInitializer() {
            return request -> {
                // NOOP
            };
        }
    }

    private static final class RecordingTransport extends MockHttpTransport {
        private final List<String> urls = new ArrayList<>();
        private final List<MockLowLevelHttpRequest> requests = new ArrayList<>();
        private final Deque<MockLowLevelHttpResponse> responses = new ArrayDeque<>();

        void enqueue(MockLowLevelHttpResponse response) {
            responses.add(response);
        }

        List<String> getUrls() {
            return urls;
        }

        List<MockLowLevelHttpRequest> getRequests() {
            return requests;
        }

        @Override
        public LowLevelHttpRequest buildRequest(String method, String url)
                throws IOException {
            urls.add(url);
            MockLowLevelHttpRequest request = new MockLowLevelHttpRequest(url) {
                @Override
                public LowLevelHttpResponse execute() throws IOException {
                    return responses.removeFirst();
                }
            };
            requests.add(request);
            return request;
        }
    }
}