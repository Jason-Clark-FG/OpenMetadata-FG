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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the one durable fact a user's prompt states outright, so a hook can save it without the
 * model's help. Deterministic and conservative: only an explicit cue at the start of a sentence
 * (or after "please") counts. A question, a fenced code block, a long prompt and anything that
 * looks like a credential or personal data never does, and the prompt's language is not guessed.
 * A prompt that states two facts yields the first
 * (ADR:2026-11-18-agent-captured-memories-merge-only-restatements).
 */
public final class PromptMemoryCues {
  public static final int MAX_PROMPT_CHARS = 4000;
  public static final int MIN_FACT_CHARS = 12;
  public static final int MAX_FACT_CHARS = 2000;
  static final int MAX_ASSET_NAMES = 10;

  private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
  private static final String LEAD = "^(?:(?:ok|okay|also|and|so|btw)[,\\s]+)?(?:please\\s+)?";

  /** Cues whose own words are not part of the fact. */
  private static final List<Pattern> LEADING_CUES =
      List.of(
          Pattern.compile(LEAD + "(?:remember|note)\\s+that\\s+(.+)$", FLAGS),
          Pattern.compile(LEAD + "(?:remember|note)\\s*[:\\u2013-]\\s*(.+)$", FLAGS),
          Pattern.compile(
              LEAD + "(?:from now on|going forward|for future reference)\\s*[,:]?\\s+(.+)$",
              FLAGS));

  /** Cues that are the fact itself: the whole sentence is kept. */
  private static final List<Pattern> RULE_SHAPES =
      List.of(
          Pattern.compile(LEAD + "(?:always|never) use .+ for .+$", FLAGS),
          Pattern.compile(LEAD + "use .+ instead of .+$", FLAGS),
          Pattern.compile("^.+ (?:is|are) deprecated[,;:]?\\s*(?:so\\s+)?use .+$", FLAGS));

  private static final Pattern FENCED_CODE = Pattern.compile("(?s)(```|~~~).*?(\\1|$)");
  private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+|\\R+");
  private static final Pattern PLEASE = Pattern.compile("^please\\s+", FLAGS);

  private static final List<Pattern> SENSITIVE =
      List.of(
          Pattern.compile(
              "\\b(?:pass(?:word|wd)?|pwd|secret|api[_ -]?key|access[_ -]?key|private[_ -]?key"
                  + "|auth[_ -]?token|bearer|credential|token)s?\\b[^.\\n]{0,40}?(?:\\bis\\b|\\bare\\b|[:=])\\s*\\S",
              FLAGS),
          Pattern.compile("(?:^|[^\\w])(?:sk|pk|rk)[-_][A-Za-z0-9_-]{16,}"),
          Pattern.compile("\\b(?:ghp|gho|ghs|github_pat|glpat|xox[abprs])[-_][A-Za-z0-9_-]{10,}"),
          Pattern.compile("\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b"),
          Pattern.compile("\\beyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}"),
          Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----"),
          Pattern.compile("://[^\\s/:@]+:[^\\s/@]+@"),
          Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+\\.[A-Za-z0-9.-]+"),
          Pattern.compile("\\b(?:\\d[ -]?){13,19}\\b"),
          Pattern.compile("(?<![A-Za-z0-9+/_-])(?=\\S*\\d)(?=\\S*[A-Za-z])[A-Za-z0-9+/_-]{32,}"));

  // An identifier such as arr_daily keeps its case: the name is what a reader searches for.
  private static final Pattern FIRST_WORD_LOWERCASE = Pattern.compile("^\\p{Ll}+(?:\\s|$)");
  private static final Pattern IDENTIFIER =
      Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)*");
  private static final Pattern BACKTICKED = Pattern.compile("`([^`\\s]{3,200})`");

  private PromptMemoryCues() {}

  /** The fact the prompt states under a cue, or empty when it states none. */
  public static Optional<String> factIn(final String prompt) {
    Optional<String> fact = Optional.empty();
    if (prompt != null && !prompt.isBlank() && prompt.length() <= MAX_PROMPT_CHARS) {
      final String prose = FENCED_CODE.matcher(prompt).replaceAll("\n");
      for (final String sentence : SENTENCE_END.split(prose)) {
        if (fact.isEmpty()) {
          fact = factOf(sentence.strip());
        }
      }
    }
    return fact;
  }

  private static Optional<String> factOf(final String sentence) {
    Optional<String> fact = Optional.empty();
    if (!sentence.isEmpty() && !sentence.contains("?")) {
      fact = leadingCue(sentence).or(() -> ruleShape(sentence));
    }
    return fact.map(PromptMemoryCues::tidy).filter(PromptMemoryCues::acceptable);
  }

  private static Optional<String> leadingCue(final String sentence) {
    Optional<String> fact = Optional.empty();
    for (final Pattern cue : LEADING_CUES) {
      final Matcher match = cue.matcher(sentence);
      if (fact.isEmpty() && match.matches()) {
        fact = Optional.of(match.group(1));
      }
    }
    return fact;
  }

  private static Optional<String> ruleShape(final String sentence) {
    final boolean matches =
        RULE_SHAPES.stream().anyMatch(shape -> shape.matcher(sentence).matches());
    return matches ? Optional.of(PLEASE.matcher(sentence).replaceFirst("")) : Optional.empty();
  }

  private static String tidy(final String fact) {
    final String stripped = fact.strip().replaceAll("\\s+", " ");
    return FIRST_WORD_LOWERCASE.matcher(stripped).find()
        ? Character.toUpperCase(stripped.charAt(0)) + stripped.substring(1)
        : stripped;
  }

  private static boolean acceptable(final String fact) {
    return fact.length() >= MIN_FACT_CHARS
        && fact.length() <= MAX_FACT_CHARS
        && !looksSensitive(fact);
  }

  /** True when the text appears to hold a credential, key, address or card number. */
  public static boolean looksSensitive(final String text) {
    return SENSITIVE.stream().anyMatch(pattern -> pattern.matcher(text).find());
  }

  /**
   * Names in the fact that may be a table or metric: backticked words, and words with an underscore
   * or a dot. A plain word is never a candidate, so "orders" in a sentence is not read as a table.
   */
  public static List<String> assetNames(final String fact) {
    final Set<String> names = new LinkedHashSet<>();
    final Matcher backticked = BACKTICKED.matcher(fact);
    while (backticked.find()) {
      names.add(backticked.group(1));
    }
    final Matcher identifiers = IDENTIFIER.matcher(fact);
    while (identifiers.find()) {
      final String word = identifiers.group();
      if (word.length() >= 3 && (word.contains("_") || word.contains("."))) {
        names.add(word);
      }
    }
    return names.stream().limit(MAX_ASSET_NAMES).toList();
  }
}
