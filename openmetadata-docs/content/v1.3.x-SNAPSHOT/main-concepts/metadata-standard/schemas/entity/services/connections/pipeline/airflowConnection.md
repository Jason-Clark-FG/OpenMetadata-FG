---
title: airflowConnection
slug: /main-concepts/metadata-standard/schemas/entity/services/connections/pipeline/airflowconnection
---

# AirflowConnection

*Airflow Metadata Database Connection Config*

## Properties

- **`type`**: Service Type. Refer to *#/definitions/AirflowType*. Default: `Airflow`.
- **`hostPort`** *(string)*: Pipeline Service Management/UI URI.
- **`numberOfStatus`** *(integer)*: Pipeline Service Number Of Status. Default: `10`.
- **`connection`**: Underlying database connection. See https://airflow.apache.org/docs/apache-airflow/stable/howto/set-up-database.html for supported backends.
- **`supportsMetadataExtraction`**: Refer to *../connectionBasicType.json#/definitions/supportsMetadataExtraction*.
## Definitions

- **`AirflowType`** *(string)*: Service type. Must be one of: `['Airflow']`. Default: `Airflow`.


Documentation file automatically generated at 2023-10-27 13:55:46.343512.
