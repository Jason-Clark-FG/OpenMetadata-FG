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

import java.util.List;

/**
 * One piece of context the compiler can deliver: company instructions, the persona prompt, a skill,
 * a glossary term, metric, article or asset card, or a guardrail or incident note.
 *
 * @param origin whether the item comes from the default harness or the persona's own settings;
 *     persona items are cut first when the budget runs out
 * @param kind the segment the item is counted under
 * @param key identity within a session, so an item is not sent twice
 * @param names names a prompt or tool call can mention the item by
 * @param asset the catalog asset the item describes, when it describes one
 */
public record ContextItem(
    Origin origin,
    String kind,
    String key,
    String text,
    List<String> names,
    boolean alwaysInContext,
    AssetRef asset) {

  public enum Origin {
    DEFAULT,
    PERSONA
  }

  /** A catalog asset by entity type and fully qualified name. */
  public record AssetRef(String entityType, String fullyQualifiedName) {}

  public static final String INSTRUCTIONS = "Instructions";
  public static final String PROMPT = "Prompt";
  public static final String SKILLS = "Skills";
  public static final String GLOSSARY = "Glossary";
  public static final String METRICS = "Metrics";
  public static final String ARTICLES = "Articles";
  public static final String ASSETS = "Assets";
  public static final String GUARDRAILS = "Guardrails";
  public static final String INCIDENTS = "Incidents";

  public ContextItem {
    names = List.copyOf(names);
  }

  /** An item that no prompt or tool call selects by name. */
  public static ContextItem fixed(
      final Origin origin, final String kind, final String key, final String text) {
    return new ContextItem(origin, kind, key, text, List.of(), false, null);
  }
}
