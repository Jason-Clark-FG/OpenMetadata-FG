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

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import org.apache.jena.datatypes.xsd.XSDDatatype;

/** The kinds of literal a card names, so a number, a boolean or a time is read as one. */
enum ValueType {
  INTEGER(
      "integer",
      Set.of(
          XSDDatatype.XSDinteger,
          XSDDatatype.XSDint,
          XSDDatatype.XSDlong,
          XSDDatatype.XSDshort,
          XSDDatatype.XSDbyte,
          XSDDatatype.XSDnonNegativeInteger,
          XSDDatatype.XSDpositiveInteger,
          XSDDatatype.XSDnonPositiveInteger,
          XSDDatatype.XSDnegativeInteger,
          XSDDatatype.XSDunsignedLong,
          XSDDatatype.XSDunsignedInt,
          XSDDatatype.XSDunsignedShort,
          XSDDatatype.XSDunsignedByte)),
  DECIMAL("decimal", Set.of(XSDDatatype.XSDdecimal, XSDDatatype.XSDdouble, XSDDatatype.XSDfloat)),
  BOOLEAN("boolean", Set.of(XSDDatatype.XSDboolean)),
  DATE_TIME("dateTime", Set.of(XSDDatatype.XSDdateTime, XSDDatatype.XSDdateTimeStamp)),
  DATE("date", Set.of(XSDDatatype.XSDdate));

  private final String name;
  private final Set<XSDDatatype> datatypes;

  ValueType(final String name, final Set<XSDDatatype> datatypes) {
    this.name = name;
    this.datatypes = datatypes;
  }

  @JsonValue
  String value() {
    return name;
  }

  /** The type a datatype IRI is read as; none for text and for datatypes the card leaves as text. */
  static Optional<ValueType> of(final String datatypeIri) {
    return Arrays.stream(values())
        .filter(type -> type.datatypes.stream().anyMatch(xsd -> xsd.getURI().equals(datatypeIri)))
        .findFirst();
  }
}
