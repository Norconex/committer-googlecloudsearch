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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.norconex.commons.lang.io.CachedStreamFactory;
import com.norconex.commons.lang.map.Properties;
import com.norconex.importer.doc.Doc;
import com.norconex.importer.handler.HandlerDoc;
import com.norconex.importer.handler.ImporterHandlerException;
import com.norconex.importer.parser.ParseState;

class BinaryContentTaggerTest {

        private static final String URL = "http://x.yz/abc";
        private static final String CONTENT = "Test1234567890";
        private static final String CONTENT_BASE64 = "VGVzdDEyMzQ1Njc4OTA=";
        private static final CachedStreamFactory csf = new CachedStreamFactory();
        private static Properties metadata;
        private HandlerDoc doc;
        private BinaryContentTagger subject;

        @BeforeEach
        public void setUp() {
                subject = new BinaryContentTagger();
                metadata = new Properties();
                doc = new HandlerDoc(new Doc(URL, csf.newInputStream(), metadata));
        }

        @Test
        void tagDocumentShouldFailIfDocumentIsAlreadyParsed() {
                ParseState parseState = ParseState.POST;

                String expectedErrMsg = "Document is already parsed. "
                                + "Please make sure the <tagger ... /> entry is inside the"
                                + " <preParseHandlers> list!";

                assertThatExceptionOfType(ImporterHandlerException.class)
                                .isThrownBy(() -> subject.tagDocument(
                                                doc,
                                                new ByteArrayInputStream(
                                                                CONTENT.getBytes(StandardCharsets.UTF_8)),
                                                parseState))
                                .withMessage(expectedErrMsg);
        }

        @Test
        void tagDocumentShouldFailOnContentReadException() throws Exception {
                ParseState parseState = ParseState.PRE;

                InputStream contentStream = new InputStream() {
                        @Override
                        public int read() throws IOException {
                                throw new IOException("Error when reading content stream!");
                        }

                        @Override
                        public int read(byte[] b, int off, int len) throws IOException {
                                throw new IOException("Error when reading content stream!");
                        }
                };

                assertThatExceptionOfType(ImporterHandlerException.class)
                                .isThrownBy(() -> subject.tagDocument(doc, contentStream, parseState))
                                .withMessage("java.io.IOException: Error when reading content stream!");
        }

        @Test
        void tagDocumentSuccessful() throws Exception {
                ParseState parseState = ParseState.PRE;

                subject.tagDocument(
                                doc,
                                new ByteArrayInputStream(
                                                CONTENT.getBytes(StandardCharsets.UTF_8)),
                                parseState);

                assertThat(doc.getMetadata().getString(
                                GoogleCloudSearchCommitter.FIELD_BINARY_CONTENT))
                                .isEqualTo(CONTENT_BASE64);
        }

        @Test
        void tagDocument_empty_successful() throws Exception {
                ParseState parseState = ParseState.PRE;

                subject.tagDocument(
                                doc,
                                new ByteArrayInputStream(new byte[0]),
                                parseState);

                assertThat(doc.getMetadata().getString(
                                GoogleCloudSearchCommitter.FIELD_BINARY_CONTENT))
                                .isEmpty();
        }
}
