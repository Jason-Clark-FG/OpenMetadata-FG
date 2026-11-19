/*
 *  Copyright 2026 Collate
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.openmetadata.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.openmetadata.schema.type.aicontext.KnowledgeItem;

class FindContextToolTest {
  private static KnowledgeItem item(KnowledgeItem.Type type, String name, String displayName) {
    return new KnowledgeItem()
        .withType(type)
        .withName(name)
        .withDisplayName(displayName)
        .withFullyQualifiedName("G." + name);
  }

  @Test
  void aLoneConceptAmongArticlesIsTheClearHit() {
    List<KnowledgeItem> items =
        List.of(
            item(KnowledgeItem.Type.PAGE, "Guide", "Revenue guide"),
            item(KnowledgeItem.Type.METRIC, "arr", "ARR"));

    Optional<KnowledgeItem> clear = FindContextTool.clearConcept(items, "what is ARR");

    assertThat(clear).map(KnowledgeItem::getName).contains("arr");
  }

  @Test
  void severalConceptsAreNotClearUnlessTheTopOneIsNamedByTheQuestion() {
    List<KnowledgeItem> items =
        List.of(
            item(KnowledgeItem.Type.GLOSSARY_TERM, "Customer", "Customer"),
            item(KnowledgeItem.Type.GLOSSARY_TERM, "ActiveCustomer", "Active customer"));

    assertThat(FindContextTool.clearConcept(items, "customers who churned")).isEmpty();
    assertThat(FindContextTool.clearConcept(items, " customer "))
        .map(KnowledgeItem::getName)
        .contains("Customer");
    assertThat(FindContextTool.clearConcept(items, "Active customer")).isEmpty();
  }

  @Test
  void noConceptMeansNoCard() {
    assertThat(
            FindContextTool.clearConcept(
                List.of(item(KnowledgeItem.Type.PAGE, "Guide", "Guide")), "guide"))
        .isEmpty();
    assertThat(FindContextTool.clearConcept(List.of(), "anything")).isEmpty();
  }
}
