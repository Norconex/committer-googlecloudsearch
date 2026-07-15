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

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.norconex.commons.lang.xml.XML;

class GoogleCloudSearchCommitterConfigTest {

        @Test
        void allPropertiesRoundtripThroughXml() throws Exception {
                GoogleCloudSearchCommitter committer = new GoogleCloudSearchCommitter();

                // Set all simple config properties
                committer.setSecretKeyPath("/path/to/service-account.json");
                committer.setDataSourceId("test-datasource-123");
                committer.setApiEndpoint("https://custom-api.example.com/");
                committer.setApplicationName("My Custom Application");
                committer.setConnectorName("my-connector");
                committer.setSourceIdField("unique_id");
                committer.setKeepSourceIdField(true);
                committer.setTitleField("document_title");
                committer.setObjectTypeField("doc_type");
                committer.setObjectTypeDefaultValue("webpage");
                committer.setUpdateTimeField("last_updated");
                committer.setCreateTimeField("first_seen");
                committer.setContainerNameField("collection_name");
                committer.setContentLanguageField("language");
                committer.setContentLanguageDefaultValue("en-US");
                committer.setSourceRepositoryUrlField("source_url");
                committer.setTypedStructuredData(true);
                committer.setUploadFormat(GoogleCloudSearchCommitter.UploadFormat.TEXT);
                committer.setRequestMode(GoogleCloudSearchCommitter.RequestMode.SYNCHRONOUS);

                // Set ACL mappings
                List<GoogleCloudSearchCommitter.AclMapping> mappings = new ArrayList<>();

                GoogleCloudSearchCommitter.AclMapping mapping1 = new GoogleCloudSearchCommitter.AclMapping();
                mapping1.setFromField("acl.readers");
                mapping1.setTarget(GoogleCloudSearchCommitter.AclTarget.READERS);
                mapping1.setPrincipalType(GoogleCloudSearchCommitter.PrincipalType.GROUP);
                mappings.add(mapping1);

                GoogleCloudSearchCommitter.AclMapping mapping2 = new GoogleCloudSearchCommitter.AclMapping();
                mapping2.setFromField("acl.denied");
                mapping2.setTarget(GoogleCloudSearchCommitter.AclTarget.DENIED_READERS);
                mapping2.setPrincipalType(GoogleCloudSearchCommitter.PrincipalType.USER);
                mappings.add(mapping2);

                GoogleCloudSearchCommitter.AclMapping mapping3 = new GoogleCloudSearchCommitter.AclMapping();
                mapping3.setFromField("acl.owners");
                mapping3.setTarget(GoogleCloudSearchCommitter.AclTarget.OWNERS);
                mapping3.setPrincipalType(GoogleCloudSearchCommitter.PrincipalType.USER);
                mappings.add(mapping3);

                committer.setAclMappings(mappings);

                // Set ACL inheritance
                GoogleCloudSearchCommitter.AclInheritanceMapping inheritance = new GoogleCloudSearchCommitter.AclInheritanceMapping();
                inheritance.setFromField("parent_reference");
                inheritance.setType(GoogleCloudSearchCommitter.AclInheritanceType.CHILD_OVERRIDE);
                committer.setAclInheritance(inheritance);

                // Save to XML
                XML savedXml = new XML("committer");
                committer.saveBatchCommitterToXML(savedXml);

                // Load from XML into a new committer
                GoogleCloudSearchCommitter loaded = new GoogleCloudSearchCommitter();
                loaded.loadBatchCommitterFromXML(savedXml);

                // Verify all simple properties
                assertThat(loaded.getSecretKeyPath()).isEqualTo("/path/to/service-account.json");
                assertThat(loaded.getDataSourceId()).isEqualTo("test-datasource-123");
                assertThat(loaded.getApiEndpoint()).isEqualTo("https://custom-api.example.com/");
                assertThat(loaded.getApplicationName()).isEqualTo("My Custom Application");
                assertThat(loaded.getConnectorName()).isEqualTo("my-connector");
                assertThat(loaded.getSourceIdField()).isEqualTo("unique_id");
                assertThat(loaded.isKeepSourceIdField()).isTrue();
                assertThat(loaded.getTitleField()).isEqualTo("document_title");
                assertThat(loaded.getObjectTypeField()).isEqualTo("doc_type");
                assertThat(loaded.getObjectTypeDefaultValue()).isEqualTo("webpage");
                assertThat(loaded.getUpdateTimeField()).isEqualTo("last_updated");
                assertThat(loaded.getCreateTimeField()).isEqualTo("first_seen");
                assertThat(loaded.getContainerNameField()).isEqualTo("collection_name");
                assertThat(loaded.getContentLanguageField()).isEqualTo("language");
                assertThat(loaded.getContentLanguageDefaultValue()).isEqualTo("en-US");
                assertThat(loaded.getSourceRepositoryUrlField()).isEqualTo("source_url");
                assertThat(loaded.isTypedStructuredData()).isTrue();
                assertThat(loaded.getUploadFormat())
                                .isEqualTo(GoogleCloudSearchCommitter.UploadFormat.TEXT);
                assertThat(loaded.getRequestMode())
                                .isEqualTo(GoogleCloudSearchCommitter.RequestMode.SYNCHRONOUS);

                // Verify ACL mappings
                assertThat(loaded.getAclMappings()).hasSize(3);
                assertThat(loaded.getAclMappings().get(0).getFromField()).isEqualTo("acl.readers");
                assertThat(loaded.getAclMappings().get(0).getTarget())
                                .isEqualTo(GoogleCloudSearchCommitter.AclTarget.READERS);
                assertThat(loaded.getAclMappings().get(0).getPrincipalType())
                                .isEqualTo(GoogleCloudSearchCommitter.PrincipalType.GROUP);

                assertThat(loaded.getAclMappings().get(1).getFromField()).isEqualTo("acl.denied");
                assertThat(loaded.getAclMappings().get(1).getTarget())
                                .isEqualTo(GoogleCloudSearchCommitter.AclTarget.DENIED_READERS);
                assertThat(loaded.getAclMappings().get(1).getPrincipalType())
                                .isEqualTo(GoogleCloudSearchCommitter.PrincipalType.USER);

                assertThat(loaded.getAclMappings().get(2).getFromField()).isEqualTo("acl.owners");
                assertThat(loaded.getAclMappings().get(2).getTarget())
                                .isEqualTo(GoogleCloudSearchCommitter.AclTarget.OWNERS);
                assertThat(loaded.getAclMappings().get(2).getPrincipalType())
                                .isEqualTo(GoogleCloudSearchCommitter.PrincipalType.USER);

                // Verify ACL inheritance
                assertThat(loaded.getAclInheritance().getFromField()).isEqualTo("parent_reference");
                assertThat(loaded.getAclInheritance().getType())
                                .isEqualTo(GoogleCloudSearchCommitter.AclInheritanceType.CHILD_OVERRIDE);
        }
}
