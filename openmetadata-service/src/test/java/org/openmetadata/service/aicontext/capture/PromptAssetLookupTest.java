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
package org.openmetadata.service.aicontext.capture;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.openmetadata.service.aicontext.capture.PromptAssetLookup.Found;

class PromptAssetLookupTest {
  private static String hits(final String... typeAndFqn) {
    final StringBuilder json = new StringBuilder("{\"hits\":{\"hits\":[");
    for (int index = 0; index < typeAndFqn.length; index += 2) {
      json.append(index == 0 ? "" : ",")
          .append("{\"_source\":{\"entityType\":\"")
          .append(typeAndFqn[index])
          .append("\",\"fullyQualifiedName\":\"")
          .append(typeAndFqn[index + 1])
          .append("\"}}");
    }
    return json.append("]}}").toString();
  }

  @Test
  void aNameThatMatchesOneAssetIsLinked() {
    assertThat(
            PromptAssetLookup.choose(
                List.of("arr_daily"), hits("table", "wh.sales.public.arr_daily")))
        .containsExactly(new Found("table", "wh.sales.public.arr_daily"));
  }

  @Test
  void aNameTwoAssetsShareLinksNeither() {
    assertThat(
            PromptAssetLookup.choose(
                List.of("orders"),
                hits("table", "wh.a.public.orders", "table", "wh.b.public.orders")))
        .isEmpty();
  }

  @Test
  void aFullyQualifiedNameLinksTheOneTableEvenWhenItsNameIsShared() {
    assertThat(
            PromptAssetLookup.choose(
                List.of("wh.b.public.orders"),
                hits("table", "wh.a.public.orders", "table", "wh.b.public.orders")))
        .containsExactly(new Found("table", "wh.b.public.orders"));
  }

  @Test
  void aNameWithNoHitAndAnEmptyResponseLinkNothing() {
    assertThat(PromptAssetLookup.choose(List.of("ghost_table"), hits())).isEmpty();
    assertThat(PromptAssetLookup.choose(List.of("ghost_table"), "{}")).isEmpty();
  }
}
