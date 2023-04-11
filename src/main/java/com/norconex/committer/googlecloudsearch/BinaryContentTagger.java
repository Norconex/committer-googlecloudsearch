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

import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;

import com.google.common.io.ByteStreams;
import com.norconex.commons.lang.map.Properties;
import com.norconex.importer.handler.HandlerDoc;
import com.norconex.importer.handler.ImporterHandlerException;
import com.norconex.importer.handler.tagger.IDocumentTagger;
import com.norconex.importer.parser.ParseState;

/**
 * Provides access to the unparsed document content for the
 * {@code GoogleCloudSearchCommitter} when using the {@code raw} upload format.
 *
 * <h3>XML configuration usage</h3>
 *
 * <pre>
 *  &lt;tagger class="com.norconex.committer.googlecloudsearch.BinaryContentTagger"/&gt;
 * </pre>
 */
public class BinaryContentTagger implements IDocumentTagger {
    
    @Override
    public void tagDocument(HandlerDoc doc, InputStream input,
            ParseState parseState) throws ImporterHandlerException {

        if (parseState.isPost()) {
            String errMsg = "Document is already parsed. "
                    + "Please make sure the <tagger ... /> entry is inside the"
                    + " <preParseHandlers> list!";
            throw new ImporterHandlerException(errMsg);
        }

        addContentInBase64(input, doc.getMetadata());
    }

    private void addContentInBase64(InputStream document,
            Properties metadata) throws ImporterHandlerException {
        try {
            byte[] content = ByteStreams.toByteArray(document);
            metadata.add(GoogleCloudSearchCommitter.FIELD_BINARY_CONTENT,
                    Base64.getEncoder().encodeToString(content));
        } catch (IOException e) {
            throw new ImporterHandlerException(e);
        }
    }
}
