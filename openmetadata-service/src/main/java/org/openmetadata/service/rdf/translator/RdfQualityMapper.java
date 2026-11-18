package org.openmetadata.service.rdf.translator;

import static org.openmetadata.common.utils.CommonUtil.nullOrEmpty;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;

/**
 * Emits the latest table and column profile as {@code dqv:QualityMeasurement} triples, so SPARQL
 * can answer "tables with fewer than N rows" or "columns whose null ratio exceeds 5%".
 *
 * <p>The graph holds the latest profile only (ADR:2026-11-18-the-rdf-graph-carries-the-latest-quality-evidence).
 * Each measure has one IRI per subject and metric, so a newer profile changes values in place and
 * the entity write that owns the measurement replaces it exactly. The measure set is bounded to
 * what a model uses to write filters and judge data; min and max are emitted only when numeric,
 * because a non-numeric bound is a data value with no single datatype.
 */
public final class RdfQualityMapper {

  private static final String OM_NS = "https://open-metadata.org/ontology/";
  private static final String DQV_NS = "http://www.w3.org/ns/dqv#";
  private static final String PROV_NS = "http://www.w3.org/ns/prov#";

  private static final Map<String, String> TABLE_METRICS =
      Map.of(
          "rowCount", "RowCountMetric",
          "columnCount", "ColumnCountMetric",
          "sizeInByte", "SizeInBytesMetric");

  private static final Map<String, String> COLUMN_METRICS =
      Map.ofEntries(
          Map.entry("valuesCount", "ValuesCountMetric"),
          Map.entry("nullCount", "NullCountMetric"),
          Map.entry("nullProportion", "NullProportionMetric"),
          Map.entry("distinctCount", "DistinctCountMetric"),
          Map.entry("uniqueCount", "UniqueCountMetric"),
          Map.entry("min", "MinMetric"),
          Map.entry("max", "MaxMetric"),
          Map.entry("mean", "MeanMetric"),
          Map.entry("minLength", "MinLengthMetric"),
          Map.entry("maxLength", "MaxLengthMetric"));

  private RdfQualityMapper() {}

  static void emitTableProfile(JsonNode profile, Resource tableResource, Model model) {
    emitProfile(profile, TABLE_METRICS, tableResource, model);
  }

  static void emitColumnProfile(JsonNode profile, Resource columnResource, Model model) {
    emitProfile(profile, COLUMN_METRICS, columnResource, model);
  }

  private static void emitProfile(
      JsonNode profile, Map<String, String> metrics, Resource subjectResource, Model model) {
    RdfJsonNode.object(profile)
        .ifPresent(
            value ->
                emitMeasurements(value, metrics, readTimestamp(value), subjectResource, model));
  }

  private static void emitMeasurements(
      JsonNode profile,
      Map<String, String> metricMap,
      String timestamp,
      Resource subjectResource,
      Model model) {
    MeasurementContext context =
        new MeasurementContext(
            subjectResource, model, MeasurementVocabulary.from(model), timestamp);

    for (Map.Entry<String, String> entry : metricMap.entrySet()) {
      RdfJsonNode.field(profile, entry.getKey())
          .filter(JsonNode::isNumber)
          .ifPresent(value -> emitMeasurement(entry.getValue(), value, context));
    }
  }

  private static void emitMeasurement(
      String metricLocalName, JsonNode value, MeasurementContext context) {
    Model model = context.model();
    MeasurementVocabulary vocabulary = context.vocabulary();
    String measurementUri = measurementUri(context.subject().getURI(), metricLocalName);
    Resource measurement = model.createResource(measurementUri);
    measurement.addProperty(RDF.type, vocabulary.measurementClass());
    measurement.addProperty(
        vocabulary.isMeasurementOf(), model.createResource(OM_NS + metricLocalName));
    measurement.addProperty(vocabulary.computedOn(), context.subject());
    addNumericValue(measurement, vocabulary.value(), value, model);
    if (!nullOrEmpty(context.timestamp())) {
      measurement.addProperty(
          vocabulary.generatedAtTime(),
          model.createTypedLiteral(context.timestamp(), XSDDatatype.XSDdateTime));
    }
    context.subject().addProperty(vocabulary.hasMeasurement(), measurement);
  }

  private static void addNumericValue(
      Resource measurement, Property dqvValue, JsonNode value, Model model) {
    if (value.isInt()) {
      measurement.addProperty(dqvValue, model.createTypedLiteral(value.asInt()));
    } else if (value.isLong()) {
      measurement.addProperty(dqvValue, model.createTypedLiteral(value.asLong()));
    } else {
      measurement.addProperty(dqvValue, model.createTypedLiteral(value.asDouble()));
    }
  }

  /** One measurement IRI per subject and metric: the graph carries the latest profile only. */
  static String measurementUri(String subjectUri, String metricLocalName) {
    return subjectUri + "/measurement/" + metricLocalName;
  }

  private static String readTimestamp(JsonNode profile) {
    return RdfJsonNode.field(profile, "timestamp")
        .flatMap(RdfQualityMapper::timestampValue)
        .orElse(null);
  }

  private static Optional<String> timestampValue(JsonNode timestamp) {
    Optional<String> value = Optional.empty();
    if (timestamp.isTextual()) {
      value = Optional.of(timestamp.asText());
    } else if (timestamp.isNumber()) {
      value = Optional.of(Instant.ofEpochMilli(timestamp.asLong()).toString());
    }
    return value;
  }

  private record MeasurementContext(
      Resource subject, Model model, MeasurementVocabulary vocabulary, String timestamp) {}

  private record MeasurementVocabulary(
      Property hasMeasurement,
      Property isMeasurementOf,
      Property value,
      Property computedOn,
      Property generatedAtTime,
      Resource measurementClass) {

    private static MeasurementVocabulary from(Model model) {
      return new MeasurementVocabulary(
          model.createProperty(DQV_NS, "hasQualityMeasurement"),
          model.createProperty(DQV_NS, "isMeasurementOf"),
          model.createProperty(DQV_NS, "value"),
          model.createProperty(DQV_NS, "computedOn"),
          model.createProperty(PROV_NS, "generatedAtTime"),
          model.createResource(DQV_NS + "QualityMeasurement"));
    }
  }
}
