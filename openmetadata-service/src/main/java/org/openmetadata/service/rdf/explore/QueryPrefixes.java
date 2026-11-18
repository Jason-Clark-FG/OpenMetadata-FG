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
package org.openmetadata.service.rdf.explore;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.jena.query.QueryException;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.Syntax;
import org.apache.jena.shared.PrefixMapping;

/**
 * Supplies the prefixes cards name terms with (om:, dct:, prov:, ...) to a query that uses them
 * without declaring them. Only a query that does not parse is touched, only with declarations for
 * prefixes it uses and does not declare, and only when the result parses; the query then runs
 * through the same validation as one that declared them itself.
 */
public final class QueryPrefixes {

  private static final Pattern DECLARED =
      Pattern.compile("(?i)\\bPREFIX\\s+([A-Za-z][\\w.-]*)?\\s*:");

  /** The query to run and the prefixes declared for it, in name order. */
  public record Completed(String sparql, List<String> added) {
    public Completed {
      added = List.copyOf(added);
    }
  }

  private QueryPrefixes() {}

  static Completed complete(final String sparql, final PrefixMapping prefixes) {
    final List<String> missing = parses(sparql) ? List.of() : missing(sparql, prefixes);
    final String completed =
        missing.stream()
                .map(name -> "PREFIX %s: <%s>%n".formatted(name, prefixes.getNsPrefixURI(name)))
                .collect(Collectors.joining())
            + sparql;
    return !missing.isEmpty() && parses(completed)
        ? new Completed(completed, missing)
        : new Completed(sparql, List.of());
  }

  private static List<String> missing(final String sparql, final PrefixMapping prefixes) {
    final Set<String> declared = declared(sparql);
    return prefixes.getNsPrefixMap().keySet().stream()
        .filter(name -> !name.isEmpty() && !declared.contains(name.toLowerCase(Locale.ROOT)))
        .filter(name -> uses(sparql, name))
        .sorted()
        .toList();
  }

  private static Set<String> declared(final String sparql) {
    final Matcher matcher = DECLARED.matcher(sparql);
    return matcher
        .results()
        .map(match -> match.group(1) == null ? "" : match.group(1).toLowerCase(Locale.ROOT))
        .collect(Collectors.toSet());
  }

  private static boolean uses(final String sparql, final String prefix) {
    return Pattern.compile("(?<![\\w:/#<.-])" + Pattern.quote(prefix) + ":").matcher(sparql).find();
  }

  private static boolean parses(final String sparql) {
    try {
      QueryFactory.create(sparql, Syntax.syntaxARQ);
      return true;
    } catch (QueryException unparsable) {
      return false;
    }
  }
}
