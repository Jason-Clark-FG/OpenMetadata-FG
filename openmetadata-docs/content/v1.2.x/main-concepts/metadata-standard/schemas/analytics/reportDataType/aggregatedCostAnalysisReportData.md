---
title: aggregatedCostAnalysisReportData
slug: /main-concepts/metadata-standard/schemas/analytics/reportdatatype/aggregatedcostanalysisreportdata
---

# aggregatedCostAnalysisReportData

*Aggregated data for Cost Analysis Report.*

## Properties

- **`unusedDataAssets`**: Count and Size of the unused Data Assets over a period of time. Refer to *#/definitions/dataAssetMetrics*.
- **`frequentlyUsedDataAssets`**: Count and Size of the frequently used Data Assets over a period of time. Refer to *#/definitions/dataAssetMetrics*.
- **`totalSize`** *(number)*: Total Size based in Bytes.
- **`totalCount`** *(number)*: Total Count.
- **`serviceName`** *(string)*: Name of the service.
- **`serviceType`** *(string)*: Type of the service.
- **`entityType`** *(string)*: Type of the entity.
- **`serviceOwner`** *(string)*: Name of the service owner.
## Definitions

- **`dataAssetValues`** *(object)*: Count or Size in bytes of Data Assets over a time period. Cannot contain additional properties.
  - **`threeDays`** *(number)*: Data Asset Count or Size for 3 days.
  - **`sevenDays`** *(number)*: Data Asset Count or Size for 7 days.
  - **`fourteenDays`** *(number)*: Data Asset Count or Size for 14 days.
  - **`thirtyDays`** *(number)*: Data Asset Count or Size for 30 days.
  - **`sixtyDays`** *(number)*: Data Asset Count or Size for 60 days.
- **`dataAssetMetrics`** *(object)*: Store the Count and Size in bytes of the Data Assets over a time period.
  - **`size`**: Size of the Data Assets over a period of time. Refer to *#/definitions/dataAssetValues*.
  - **`count`**: Count of the Data Assets over a period of time. Refer to *#/definitions/dataAssetValues*.
  - **`totalSize`** *(number)*: Total Size based in Bytes.
  - **`totalCount`** *(number)*: Total Count.


Documentation file automatically generated at 2023-10-27 13:55:46.343512.
