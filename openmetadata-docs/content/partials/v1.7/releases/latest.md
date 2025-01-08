# 1.6.1 Release 🎉

{% note noteType="Tip" %}
**December 10th, 2024**
{% /note %}

{% inlineCalloutContainer %}
{% inlineCallout
color="violet-70"
icon="celebration"
bold="Upgrade OpenMetadata"
href="/deployment/upgrade" %}
Learn how to upgrade your OpenMetadata instance to 1.6.1!
{% /inlineCallout %}
{% /inlineCalloutContainer %}

You can find the GitHub release [here](https://github.com/open-metadata/OpenMetadata/releases/tag/1.6.1-release).

# Backward Incompatible Changes

### Ingestion Workflow Status

We are updating how we compute the success percentage. Previously, we took into account for partial success the results
of the Source (e.g., the tables we were able to properly retrieve from Snowflake, Redshift, etc.). This means that we had
an error threshold in there were if up to 90% of the tables were successfully ingested, we would still consider the
workflow as successful. However, any errors when sending the information to OpenMetadata would be considered as a failure.

Now, we're changing this behavior to consider the success rate of all the steps involved in the workflow. The UI will
then show more `Partial Success` statuses rather than `Failed`, properly reflecting the real state of the workflow.

### Database Metadata & Lineage Workflow

With 1.6 Release we are moving the `View Lineage` & `Stored Procedure Lineage` computation from metadata workflow to lineage workflow.

This means that we are removing the `overrideViewLineage` property from the `DatabaseServiceMetadataPipeline` schema which will be moved to the `DatabaseServiceQueryLineagePipeline` schema.

### Profiler & Auto Classification Workflow

We are creating a new `Auto Classification` workflow that will take care of managing the sample data and PII classification,
which was previously done by the Profiler workflow. This change will allow us to have a more modular and scalable system.

The Profiler workflow will now only focus on the profiling part of the data, while the Auto Classification will take care
of the rest.

This means that we are removing these properties from the `DatabaseServiceProfilerPipeline` schema:
- `generateSampleData`
- `processPiiSensitive`
- `confidence`
  which will be moved to the new `DatabaseServiceAutoClassificationPipeline` schema.

What you will need to do:
- If you are using the **EXTERNAL** ingestion for the profiler (YAML configuration), you will need to update your configuration,
  removing these properties as well.
- If you still want to use the Auto PII Classification and sampling features, you can create the new workflow
  from the UI.

### RBAC Policy Updates for `EditTags`

We have given more granularity to the `EditTags` policy. Previously, it was a single policy that allowed the user to manage
any kind of tagging to the assets, including adding tags, glossary terms, and Tiers.

Now, we have split this policy to give further control on which kind of tagging the user can manage. The `EditTags` policy has been
split into:

- `EditTags`: to add tags.
- `EditGlossaryTerms`: to add Glossary Terms.
- `EditTier`: to add Tier tags.

### Collate - Metadata Actions for ML Tagging - Deprecation Notice

Since we are introducing the `Auto Classification` workflow, **we are going to remove in 1.7 the `ML Tagging` action**
from the Metadata Actions. That feature will be covered already by the `Auto Classification` workflow, which even brings
more flexibility allow the on-the-fly usage of the sample data for classification purposes without having to store
it in the database.

### Service Spec for the Ingestion Framework

This impacts users who maintain their own connectors for the ingestion framework that are **NOT** part of the
[OpenMetadata python library (openmetadata-ingestion)](https://github.com/open-metadata/OpenMetadata/tree/ff261fb3738f3a56af1c31f7151af9eca7a602d5/ingestion/src/metadata/ingestion/source).
Introducing the ["connector specifcication class (`ServiceSpec`)"](https://github.com/open-metadata/OpenMetadata/blob/main/ingestion/src/metadata/utils/service_spec/service_spec.py).
The `ServiceSpec` class serves as the entrypoint for the connector and holds the references for the classes that will be used
to ingest and process the metadata from the source.
You can see [postgres](https://github.com/open-metadata/OpenMetadata/blob/main/ingestion/src/metadata/ingestion/source/database/postgres/service_spec.py) for an
implementation example.


### Fivetran

The filtering of Fivetran pipelines now supports using their names instead of IDs. This change may affect existing configurations that rely on pipeline IDs for filtering.

### DBT Cloud Pipeline Service

We are removing the field `jobId` which we required to ingest dbt metadata from a specific job, instead of this we added a new field called `jobIds` which will accept multiple job ids to ingest metadata from multiple jobs.

### MicroStrategy

The `serviceType` for MicroStrategy connector is renamed from `Mstr` to `MicroStrategy`.

# What's New

## Visualizing Your Data Landscape with Entity Relationship (ER) Diagrams! (Collate)

Understanding complex database schemas can be challenging without clear visualization. While OpenMetadata's best-in-class Lineage UI helps track data flow, there are better options for viewing structural relationships between tables. Collate 1.6 introduces ER diagrams as a new feature to let you:

- Visualize table connections through primary and foreign key constraints
- Navigate between data assets to discover relationships
- Modify connections using the built-in UI editor

ER diagrams help you better understand and manage your data architecture by showing how your database tables relate to each other.

## Establishing Smooth Data Governance with Automated Glossary Approval Workflows! (Collate)

Organizations often struggle with data governance due to rigid, pre-defined manual workflows. OpenMetadata 1.6 introduces a new, automated data governance framework designed to be customized to each organization's needs.

In Collate 1.6, the Glossary Approval Workflow has been migrated to this new framework. Now, you can create custom approval processes with specific conditions and rules and easily visualize them through intuitive workflow diagrams. You can also create smart approval processes for glossary terms with real-time state changes and task creation to save time and streamline work. 

## Data Certification Workflows for Automated Bronze, Silver, & Gold Data Standardization! (Collate)

Collate 1.6 also leverages the new data governance framework for a new Data Certification Workflow, allowing you to define your organization's rules to certify your data as Bronze, Silver, or Gold. Certified assets are a great way to help users discover the right data and inform them which data has been properly curated.

Our vision is to expand our governance framework to allow our users to create their own Custom Governance workflows. We want to enable data teams to implement and automate data governance processes that perfectly fit your organization, promoting data quality and compliance.

## Maintaining a Healthy Data Platform with Observability Dashboards! (Collate)

Monitoring data quality and incident management across platforms can be challenging. OpenMetadata has been a pillar for data quality implementations, with its ability to create tests from the UI, native observability alerts, and Incident Manager. It offers data quality insights on a per-table level.

In Collate 1.6, we're introducing platform-wide observability dashboards that allow you to track overall data quality coverage trends and analyze incident response performance across your entire data estate. Quickly identify root causes through enhanced asset and lineage views and enable proactive data quality management across your entire data ecosystem.

## Elevating Metric Management with Dedicated Metric Entities

Metrics are essential for data-driven organizations, but OpenMetadata previously lacked dedicated metric management, forcing users to use glossary terms as a workaround.

The new "Metric" entity in OpenMetadata 1.6 provides a purpose-built solution to:

- Document detailed metric calculations and descriptions
- Record calculation formulas and implementation code (Python, Java, SQL, LaTeX)
- Visualize metric lineage from source data to insights

This new addition helps teams better manage, understand, and calculate their business KPIs, for improved data literacy and consistency across data teams.

## Reinforcing Data Security with Search RBAC

OpenMetadata's Roles and Policies enable granular permission control, ensuring appropriate access to metadata across different domains and teams. Some data teams may wish to enable data discovery to search for other tables while still enforcing controls with access requests. Other data teams in more restrictive environments may also wish to control the search experience.

OpenMetadata 1.6 extends Role-Based Access Control (RBAC) to search functionality, allowing administrators to tailor user search experience. This provides personalized search results, with users only seeing assets they have permission to access, as well as stronger data governance by ensuring users only interact with data within their defined roles and responsibilities.

## Expanded Connector Ecosystem and Diversity

OpenMetadata's ingestion framework contains 80+ native connectors. These connectors are the foundation of the platform and bring in all the metadata your team needs: technical metadata, lineage, usage, profiling, etc.

We bring new connectors in each release, continuously expanding our coverage. This time, release 1.6 comes with seven new connectors:

1. **OpenAPI**: Extract rich metadata from OpenAPI specifications, including endpoints and schemas.

2. **Sigma**: Bringing in your BI dashboard information.

3. **Exasol**: Gain insights into your Exasol database, now supported thanks to Nicola Coretti's OSS contribution!

And in Collate, we are bringing four ETL, dashboarding and ML tools: Matillion, Azure Data Factory, Stitch, PowerBI Server and Vertex AI!

## Streamlining Data Management with Additional Enhancements

Release 1.6 comes with several other notable improvements:

- Asynchronous Export APIs: Enjoy increased efficiency when exporting and importing large datasets with new asynchronous APIs.
- Faster Search Re-indexing: Experience significantly improved performance in search re-indexing, making data discovery even smoother.
- Improved Data Insights Custom Dashboards UI (Collate): To make it even easier to write your own insights dashboards in Collate.
- Slack Integration  (Collate): Collate is releasing a new Application that lets your users find and share assets directly within your Slack workspace!
- Alert Debuggability: Allowing users to test the destinations and see whenever the alert was triggered.
- And even more fixes and improvements!

**Full Changelog**: https://github.com/open-metadata/OpenMetadata/compare/1.5.13-release...1.6.1-release