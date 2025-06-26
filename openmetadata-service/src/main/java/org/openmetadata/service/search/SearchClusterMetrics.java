package org.openmetadata.service.search;

import java.util.Map;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.openmetadata.schema.service.configuration.elasticsearch.ElasticSearchConfiguration;
import org.openmetadata.service.search.elasticsearch.ElasticSearchClient;
import org.openmetadata.service.search.opensearch.OpenSearchClient;

@Slf4j
@Builder
@Getter
public class SearchClusterMetrics {
  private final int availableProcessors;
  private final long heapSizeBytes;
  private final long availableMemoryBytes;
  private final int totalShards;
  private final int totalNodes;
  private final double cpuUsagePercent;
  private final double memoryUsagePercent;
  private final long maxPayloadSizeBytes;
  private final int recommendedConcurrentRequests;
  private final int recommendedBatchSize;
  private final int recommendedProducerThreads;

  public static SearchClusterMetrics fetchClusterMetrics(
      SearchRepository searchRepository, long totalEntities) {
    ElasticSearchConfiguration.SearchType searchType = searchRepository.getSearchType();

    try {
      if (searchType.equals(ElasticSearchConfiguration.SearchType.OPENSEARCH)) {
        return fetchOpenSearchMetrics(
            searchRepository, (OpenSearchClient) searchRepository.getSearchClient(), totalEntities);
      } else {
        return fetchElasticSearchMetrics(
            searchRepository,
            (ElasticSearchClient) searchRepository.getSearchClient(),
            totalEntities);
      }
    } catch (Exception e) {
      LOG.warn("Failed to fetch cluster metrics, using conservative defaults: {}", e.getMessage());
      return getConservativeDefaults(searchRepository, totalEntities);
    }
  }

  @SuppressWarnings("unchecked")
  private static SearchClusterMetrics fetchOpenSearchMetrics(
      SearchRepository searchRepository, OpenSearchClient osClient, long totalEntities) {
    try {
      Map<String, Object> clusterStats = osClient.clusterStats();
      Map<String, Object> nodesStats = osClient.nodesStats();
      Map<String, Object> clusterSettings = osClient.clusterSettings();

      // Debug logging for API responses
      LOG.debug("ClusterStats response: {}", clusterStats);
      LOG.debug("NodesStats response: {}", nodesStats);

      Map<String, Object> nodes = (Map<String, Object>) clusterStats.get("nodes");
      int totalNodes = extractIntValue(nodes, "count", 1);

      Map<String, Object> indices = (Map<String, Object>) clusterStats.get("indices");
      Map<String, Object> shards = (Map<String, Object>) indices.get("shards");
      int totalShards = extractIntValue(shards, "total", 0);

      Map<String, Object> nodesMap = (Map<String, Object>) nodesStats.get("nodes");
      Map<String, Object> firstNode = (Map<String, Object>) nodesMap.values().iterator().next();

      Map<String, Object> os = (Map<String, Object>) firstNode.get("os");
      Map<String, Object> cpu = (Map<String, Object>) os.get("cpu");
      double cpuUsagePercent = extractCpuPercent(cpu);

      Map<String, Object> jvm = (Map<String, Object>) firstNode.get("jvm");
      Map<String, Object> mem = (Map<String, Object>) jvm.get("mem");
      long heapUsedBytes = ((Number) mem.get("heap_used_in_bytes")).longValue();
      long heapMaxBytes = ((Number) mem.get("heap_max_in_bytes")).longValue();
      double memoryUsagePercent = (double) heapUsedBytes / heapMaxBytes * 100;

      long maxContentLength = extractMaxContentLength(clusterSettings);

      return calculateRecommendations(
          totalNodes,
          totalShards,
          cpuUsagePercent,
          memoryUsagePercent,
          heapMaxBytes,
          maxContentLength,
          totalEntities);

    } catch (Exception e) {
      LOG.warn("Failed to fetch OpenSearch cluster metrics: {}", e.getMessage(), e);
      return getConservativeDefaults(searchRepository, totalEntities);
    }
  }

  @SuppressWarnings("unchecked")
  private static SearchClusterMetrics fetchElasticSearchMetrics(
      SearchRepository searchRepository, ElasticSearchClient client, long totalEntities) {
    try {
      Map<String, Object> clusterStats = client.clusterStats();
      Map<String, Object> nodesStats = client.nodesStats();
      Map<String, Object> clusterSettings = client.clusterSettings();

      Map<String, Object> nodes = (Map<String, Object>) clusterStats.get("nodes");
      int totalNodes = extractIntValue(nodes, "count", 1);

      Map<String, Object> indices = (Map<String, Object>) clusterStats.get("indices");
      Map<String, Object> shards = (Map<String, Object>) indices.get("shards");
      int totalShards = extractIntValue(shards, "total", 0);

      Map<String, Object> nodesMap = (Map<String, Object>) nodesStats.get("nodes");
      Map<String, Object> firstNode = (Map<String, Object>) nodesMap.values().iterator().next();

      Map<String, Object> os = (Map<String, Object>) firstNode.get("os");
      Map<String, Object> cpu = (Map<String, Object>) os.get("cpu");
      double cpuUsagePercent = extractCpuPercent(cpu);

      Map<String, Object> jvm = (Map<String, Object>) firstNode.get("jvm");
      Map<String, Object> mem = (Map<String, Object>) jvm.get("mem");
      long heapUsedBytes = ((Number) mem.get("heap_used_in_bytes")).longValue();
      long heapMaxBytes = ((Number) mem.get("heap_max_in_bytes")).longValue();
      double memoryUsagePercent = (double) heapUsedBytes / heapMaxBytes * 100;

      long maxContentLength = extractMaxContentLength(clusterSettings);

      return calculateRecommendations(
          totalNodes,
          totalShards,
          cpuUsagePercent,
          memoryUsagePercent,
          heapMaxBytes,
          maxContentLength,
          totalEntities);

    } catch (Exception e) {
      LOG.warn(
          "Failed to fetch ElasticSearch cluster metrics ({}): {}",
          e.getClass().getSimpleName(),
          e.getMessage(),
          e);
      LOG.info("Using conservative defaults for {} total entities", totalEntities);
      SearchClusterMetrics defaults = getConservativeDefaults(searchRepository, totalEntities);
      LOG.info(
          "Conservative defaults: Batch size={}, Producer threads={}, Concurrent requests={}, Max payload={} MB",
          defaults.getRecommendedBatchSize(),
          defaults.getRecommendedProducerThreads(),
          defaults.getRecommendedConcurrentRequests(),
          defaults.getMaxPayloadSizeBytes() / (1024 * 1024));
      return defaults;
    }
  }

  private static SearchClusterMetrics calculateRecommendations(
      int totalNodes,
      int totalShards,
      double cpuUsagePercent,
      double memoryUsagePercent,
      long heapMaxBytes,
      long maxContentLength,
      long totalEntities) {

    int baseThreadsPerNode = Runtime.getRuntime().availableProcessors() * 4;
    int recommendedProducerThreads = Math.min(100, baseThreadsPerNode * totalNodes);

    if (cpuUsagePercent > 80) {
      recommendedProducerThreads = Math.max(10, recommendedProducerThreads / 2);
    } else if (cpuUsagePercent < 40) {
      recommendedProducerThreads = Math.min(200, recommendedProducerThreads * 3);
    }

    int baseConcurrentRequests = totalNodes * 50;
    if (memoryUsagePercent > 80) {
      baseConcurrentRequests = Math.max(10, baseConcurrentRequests / 2);
    } else if (memoryUsagePercent < 50) {
      baseConcurrentRequests = Math.min(500, baseConcurrentRequests * 2);
    }

    long heapBasedPayloadSize =
        Math.min(500 * 1024 * 1024L, heapMaxBytes / 20); // Max 500MB or 5% of heap

    // Don't assume compression - use actual content length limit
    // Some clusters might have compression disabled
    // Use 90% of limit to leave small buffer for request overhead
    long maxPayloadSize =
        Math.min(heapBasedPayloadSize, maxContentLength * 9 / 10); // Use 90% of limit

    // Be more conservative with batch size to handle large documents (some can be 30KB+)
    int avgEntitySizeKB = 10; // More realistic average including large documents
    int recommendedBatchSize = (int) Math.min(500, maxPayloadSize / (avgEntitySizeKB * 1024L));
    recommendedBatchSize =
        Math.max(50, recommendedBatchSize); // Lower minimum to avoid timeout with large docs

    // Scale batch size based on dataset size but be conservative to avoid timeouts
    if (totalEntities > 1000000) {
      recommendedBatchSize = Math.min(500, recommendedBatchSize); // Cap at 500
      recommendedProducerThreads =
          Math.min(50, recommendedProducerThreads); // Capped by connection pool
    } else if (totalEntities > 500000) {
      recommendedBatchSize = Math.min(400, recommendedBatchSize); // Cap at 400
      recommendedProducerThreads = Math.min(40, recommendedProducerThreads);
    } else if (totalEntities > 100000) {
      recommendedBatchSize = Math.min(300, recommendedBatchSize); // Cap at 300
      recommendedProducerThreads = Math.min(30, recommendedProducerThreads);
    }

    return SearchClusterMetrics.builder()
        .availableProcessors(Runtime.getRuntime().availableProcessors())
        .heapSizeBytes(heapMaxBytes)
        .availableMemoryBytes(heapMaxBytes - (long) (heapMaxBytes * memoryUsagePercent / 100))
        .totalShards(totalShards)
        .totalNodes(totalNodes)
        .cpuUsagePercent(cpuUsagePercent)
        .memoryUsagePercent(memoryUsagePercent)
        .maxPayloadSizeBytes(maxPayloadSize)
        .recommendedConcurrentRequests(baseConcurrentRequests)
        .recommendedBatchSize(recommendedBatchSize)
        .recommendedProducerThreads(recommendedProducerThreads)
        .build();
  }

  /**
   * Check if HTTP compression is enabled in cluster settings
   */
  @SuppressWarnings("unchecked")
  public static boolean isCompressionEnabled(Map<String, Object> clusterSettings) {
    try {
      Map<String, Object> persistentSettings =
          (Map<String, Object>) clusterSettings.get("persistent");
      Map<String, Object> transientSettings =
          (Map<String, Object>) clusterSettings.get("transient");
      Map<String, Object> defaultSettings = (Map<String, Object>) clusterSettings.get("defaults");

      // Check in order: transient -> persistent -> defaults
      Boolean compressionEnabled = null;

      if (transientSettings != null && transientSettings.containsKey("http.compression")) {
        compressionEnabled = (Boolean) transientSettings.get("http.compression");
      }

      if (compressionEnabled == null
          && persistentSettings != null
          && persistentSettings.containsKey("http.compression")) {
        compressionEnabled = (Boolean) persistentSettings.get("http.compression");
      }

      if (compressionEnabled == null
          && defaultSettings != null
          && defaultSettings.containsKey("http.compression")) {
        compressionEnabled = (Boolean) defaultSettings.get("http.compression");
      }

      // Default is false in Elasticsearch/OpenSearch
      return compressionEnabled != null ? compressionEnabled : false;
    } catch (Exception e) {
      LOG.debug("Failed to check compression setting, assuming disabled: {}", e.getMessage());
      return false;
    }
  }

  @SuppressWarnings("unchecked")
  public static long extractMaxContentLength(Map<String, Object> clusterSettings) {
    try {
      long defaultMaxContentLength = 100 * 1024 * 1024L; // 100MB

      Map<String, Object> persistentSettings =
          (Map<String, Object>) clusterSettings.get("persistent");
      Map<String, Object> transientSettings =
          (Map<String, Object>) clusterSettings.get("transient");

      String maxContentLengthStr = null;
      if (persistentSettings != null && persistentSettings.containsKey("http.max_content_length")) {
        maxContentLengthStr = (String) persistentSettings.get("http.max_content_length");
      }

      if (maxContentLengthStr == null
          && transientSettings != null
          && transientSettings.containsKey("http.max_content_length")) {
        maxContentLengthStr = (String) transientSettings.get("http.max_content_length");
      }

      if (maxContentLengthStr != null) {
        return parseByteSize(maxContentLengthStr);
      }

      return defaultMaxContentLength;
    } catch (Exception e) {
      LOG.warn("Failed to extract maxContentLength from cluster settings: {}", e.getMessage());
      return 100 * 1024 * 1024L; // Default 100MB
    }
  }

  private static long parseByteSize(String sizeStr) {
    if (sizeStr == null || sizeStr.trim().isEmpty()) {
      return 100 * 1024 * 1024L; // Default 100MB
    }

    sizeStr = sizeStr.trim().toLowerCase();

    String numStr = sizeStr.replaceAll("[^0-9.]", "");
    String unit = sizeStr.replaceAll("[0-9.]", "");

    try {
      double num = Double.parseDouble(numStr);

      return switch (unit) {
        case "b", "" -> (long) num;
        case "kb" -> (long) (num * 1024);
        case "mb" -> (long) (num * 1024 * 1024);
        case "gb" -> (long) (num * 1024 * 1024 * 1024);
        default -> (long) num; // Default to bytes
      };
    } catch (NumberFormatException e) {
      LOG.warn("Failed to parse byte size: {}", sizeStr);
      return 100 * 1024 * 1024L; // Default 100MB
    }
  }

  @SuppressWarnings("unchecked")
  private static double extractCpuPercent(Map<String, Object> cpu) {
    Object percentValue = cpu.get("percent");

    // Handle different formats of CPU percent from various OpenSearch versions
    if (percentValue instanceof Number) {
      // OpenSearch < 2.19 format: direct numeric value
      return ((Number) percentValue).doubleValue();
    } else if (percentValue instanceof Map) {
      // OpenSearch 2.19+ format: might be a map with detailed CPU info
      Map<String, Object> percentMap = (Map<String, Object>) percentValue;
      // Try to find the actual percent value in the map
      for (String key : new String[] {"value", "percent", "usage", "total"}) {
        if (percentMap.containsKey(key) && percentMap.get(key) instanceof Number) {
          return ((Number) percentMap.get(key)).doubleValue();
        }
      }
    }

    // Fallback: return default 50% if unable to extract
    LOG.warn("Unable to extract CPU percent from response, using default 50%");
    return 50.0;
  }

  private static long extractLongValue(Map<String, Object> map, String key, long defaultValue) {
    Object value = map.get(key);
    if (value instanceof Number) {
      return ((Number) value).longValue();
    }
    LOG.debug("Unable to extract long value for key '{}', using default: {}", key, defaultValue);
    return defaultValue;
  }

  private static int extractIntValue(Map<String, Object> map, String key, int defaultValue) {
    Object value = map.get(key);
    if (value instanceof Number) {
      return ((Number) value).intValue();
    }
    LOG.debug("Unable to extract int value for key '{}', using default: {}", key, defaultValue);
    return defaultValue;
  }

  private static SearchClusterMetrics getConservativeDefaults(
      SearchRepository searchRepository, long totalEntities) {
    int conservativeBatchSize;
    if (totalEntities > 1000000) {
      conservativeBatchSize = 500;
    } else if (totalEntities > 500000) {
      conservativeBatchSize = 400;
    } else if (totalEntities > 250000) {
      conservativeBatchSize = 300;
    } else if (totalEntities > 100000) {
      conservativeBatchSize = 200;
    } else if (totalEntities > 50000) {
      conservativeBatchSize = 150;
    } else {
      conservativeBatchSize = 100;
    }

    // Scale producer threads based on dataset size and available processors
    // Still capped by MAX_CONCURRENT_TASKS (50) in the application
    int availableProcessors = Runtime.getRuntime().availableProcessors();
    int conservativeThreads;
    if (totalEntities > 1000000) {
      conservativeThreads = Math.min(50, availableProcessors * 4);
    } else if (totalEntities > 500000) {
      conservativeThreads = Math.min(40, availableProcessors * 3);
    } else if (totalEntities > 100000) {
      conservativeThreads = Math.min(30, availableProcessors * 2);
    } else {
      conservativeThreads = Math.min(20, Math.max(10, availableProcessors));
    }

    int conservativeConcurrentRequests = totalEntities > 100000 ? 50 : 25;

    long maxHeap = Runtime.getRuntime().maxMemory();
    long totalHeap = Runtime.getRuntime().totalMemory();
    long freeHeap = Runtime.getRuntime().freeMemory();
    long usedHeap = totalHeap - freeHeap;
    double heapUsagePercent = (maxHeap > 0) ? (double) usedHeap / maxHeap * 100 : 50.0;

    // Default to 100MB if we can't fetch from cluster
    long maxPayloadSize = 100 * 1024 * 1024L; // Default 100MB
    try {
      if (searchRepository != null) {
        SearchClient searchClient = searchRepository.getSearchClient();
        Map<String, Object> clusterSettings = null;

        // Get cluster settings based on search client type
        if (searchClient instanceof OpenSearchClient) {
          clusterSettings = ((OpenSearchClient) searchClient).clusterSettings();
        } else if (searchClient instanceof ElasticSearchClient) {
          clusterSettings = ((ElasticSearchClient) searchClient).clusterSettings();
        }

        if (clusterSettings != null) {
          long maxContentLength = extractMaxContentLength(clusterSettings);
          // Use actual max content length from cluster settings
          // Apply 90% to leave small buffer for HTTP headers and request overhead
          maxPayloadSize = maxContentLength * 9 / 10;
          LOG.debug(
              "Detected max content length: {} MB, effective payload size: {} MB",
              maxContentLength / (1024 * 1024),
              maxPayloadSize / (1024 * 1024));
        }
      }
    } catch (Exception e) {
      LOG.debug(
          "Could not fetch max content length from cluster, using default: {}", e.getMessage());
    }

    return SearchClusterMetrics.builder()
        .availableProcessors(Runtime.getRuntime().availableProcessors())
        .heapSizeBytes(maxHeap)
        .availableMemoryBytes(maxHeap - usedHeap)
        .totalShards(0)
        .totalNodes(1)
        .cpuUsagePercent(50.0)
        .memoryUsagePercent(heapUsagePercent)
        .maxPayloadSizeBytes(maxPayloadSize)
        .recommendedConcurrentRequests(conservativeConcurrentRequests)
        .recommendedBatchSize(conservativeBatchSize)
        .recommendedProducerThreads(conservativeThreads)
        .build();
  }

  public void logRecommendations() {
    LOG.info("=== Auto-Tune Cluster Analysis ===");
    LOG.info("Cluster: {} nodes, {} shards", totalNodes, totalShards);
    LOG.info(
        "Resource Usage: CPU {}%, Memory {}%",
        String.format("%.1f", cpuUsagePercent), String.format("%.1f", memoryUsagePercent));
    LOG.info(
        "Heap: {} MB total, {} MB available",
        heapSizeBytes / (1024 * 1024),
        availableMemoryBytes / (1024 * 1024));
    LOG.info("=== Auto-Tune Recommendations (Virtual Threads Optimized) ===");
    LOG.info("Batch Size: {}", recommendedBatchSize);
    LOG.info(
        "Producer Threads: {} (virtual threads - lightweight & scalable)",
        recommendedProducerThreads);
    LOG.info("Concurrent Requests: {}", recommendedConcurrentRequests);
    LOG.info(
        "Max Payload Size: {} MB (with compression optimization)",
        maxPayloadSizeBytes / (1024 * 1024));
    LOG.info("Note: Virtual threads enable high concurrency for I/O-bound operations");
    LOG.info("Note: Request compression is enabled (~75% size reduction for JSON)");
    LOG.info("================================================================");
  }
}
