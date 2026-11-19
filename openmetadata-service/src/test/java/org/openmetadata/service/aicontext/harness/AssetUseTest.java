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
package org.openmetadata.service.aicontext.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class AssetUseTest {
  @Test
  void certifiedAssetsComeFirstAndShowTheirCertification() {
    final List<AssetUse> chosen =
        AssetUse.choose(
            List.of(
                new AssetUse("svc.db.s.plain", "table", null, false),
                new AssetUse("svc.db.s.arr_daily", "table", "Certification.Gold", false)));

    assertEquals(
        "Use: `svc.db.s.arr_daily` (table, Gold), `svc.db.s.plain` (table)",
        AssetUse.render(chosen));
  }

  @Test
  void deprecatedAssetsAreNeverChosen() {
    final List<AssetUse> chosen =
        AssetUse.choose(
            List.of(
                new AssetUse("svc.db.s.old", "table", "Certification.Gold", true),
                new AssetUse("svc.db.s.new", "dashboard", null, false)));

    assertEquals("Use: `svc.db.s.new` (dashboard)", AssetUse.render(chosen));
  }

  @Test
  void atMostThreeAssetsAreChosenAndCertifiedOnesSurviveTheCap() {
    final List<AssetUse> chosen =
        AssetUse.choose(
            List.of(
                new AssetUse("a", "table", null, false),
                new AssetUse("b", "table", null, false),
                new AssetUse("c", "table", null, false),
                new AssetUse("d", "table", "Certification.Silver", false)));

    assertEquals("Use: `d` (table, Silver), `a` (table), `b` (table)", AssetUse.render(chosen));
  }

  @Test
  void noAssetsRenderNoLine() {
    assertEquals("", AssetUse.render(AssetUse.choose(List.of())));
    assertEquals(
        "", AssetUse.render(AssetUse.choose(List.of(new AssetUse("x", "table", null, true)))));
  }
}
