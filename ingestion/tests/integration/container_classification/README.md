# Container Auto-Classification Integration Tests

Integration tests for the container auto-classification feature using MinIO as a replacement for AWS S3.

## Overview

These tests validate the end-to-end functionality of auto-classification for Container entities in OpenMetadata:

1. **Container Sample Data Ingestion**: Tests that sample data from S3/MinIO containers is correctly ingested
2. **PII Detection**: Tests that PII recognizers correctly identify sensitive data in container columns
3. **Tag Application**: Tests that detected PII is tagged appropriately with confidence scores
4. **Storage Service Workflows**: Tests metadata ingestion and auto-classification pipeline execution

## Architecture

### Components Tested

- **Backend**: `ContainerRepository.addSampleData()`, `ContainerResource` REST endpoints
- **Python Ingestion**:
  - `container_mixin.py`: Container sample data ingestion
  - `processor.py`: `_run_for_container()` method
  - `base_processor.py`: PII detection for containers
- **MinIO**: Test container providing S3-compatible storage

### Test Structure

```
container_classification/
├── __init__.py
├── conftest.py              # Fixtures for MinIO, test data, and PII tags
├── test_container_classification.py  # Auto-classification tests
├── test_container_sample_data.py     # Sample data API tests
├── resources/
│   └── README.md            # Test data documentation
└── README.md                # This file
```

## Prerequisites

### Python Dependencies

Required packages (should already be in your environment):

```bash
pip install pytest
pip install testcontainers[minio]
pip install pandas
pip install pyarrow
pip install minio
```

These are included in the ingestion development environment.

### OpenMetadata Server

Tests require a running OpenMetadata server:

```bash
# Start OpenMetadata locally
./docker/run_local_docker.sh -m no-ui -d mysql
```

Or use an existing OpenMetadata instance at `http://localhost:8585/api`.

## Running the Tests

### Run All Container Classification Tests

```bash
cd ingestion
pytest tests/integration/container_classification/ -v
```

### Run Specific Test File

```bash
pytest tests/integration/container_classification/test_container_classification.py -v
```

### Run Specific Test

```bash
pytest tests/integration/container_classification/test_container_classification.py::test_container_pii_classification_csv -v
```

### Run with Debug Logging

```bash
pytest tests/integration/container_classification/ -v -s --log-cli-level=DEBUG
```

### Run in Parallel (if using pytest-xdist)

```bash
pytest tests/integration/container_classification/ -v -n auto
```

Note: These tests are grouped to run in the same worker to share the module-scoped MinIO container.

## Test Coverage

### 1. Container Classification Tests (`test_container_classification.py`)

- ✅ **Storage service ingestion**: Verifies S3 storage service is created
- ✅ **Container ingestion**: Verifies containers with data models are ingested
- ✅ **CSV PII classification**: Tests email, SSN, credit card, name detection
- ✅ **Parquet PII classification**: Tests PII detection in binary file formats
- ✅ **Non-sensitive PII**: Tests phone and date classification
- ✅ **No false positives**: Verifies non-PII columns are not tagged
- ✅ **Classification reasons**: Validates explanation/confidence metadata
- ✅ **Sample data storage**: Verifies sample data is stored when configured
- ✅ **Workflow status**: Checks auto-classification completes successfully
- ✅ **Filter patterns**: Tests `containerFilterPattern` configuration
- ✅ **Parametrized tests**: Multiple column/tag combinations

### 2. Sample Data API Tests (`test_container_sample_data.py`)

- ✅ **API ingestion**: Tests `ingest_container_sample_data()` mixin method
- ✅ **Data retrieval**: Tests sample data can be fetched after classification
- ✅ **Sample limits**: Verifies `sampleDataCount` is respected
- ✅ **Empty data handling**: Tests edge case with no rows
- ✅ **Column matching**: Verifies sample columns match data model
- ✅ **Parquet support**: Tests binary format sample data
- ✅ **Special characters**: Tests encoding and Unicode handling
- ✅ **Multiple containers**: Verifies isolation of sample data

## Test Data

Test data is generated programmatically using fixtures in `conftest.py`:

### CSV Files

1. **customers.csv** (PII data):
   - 5 rows with customer information
   - Columns: customer_id, name, email, phone, ssn, credit_card, address, created_date
   - Tests: Email, SSN, credit card, name recognition

2. **orders.csv** (non-PII data):
   - 5 rows with order information
   - Columns: order_id, product_id, quantity, price, order_date
   - Tests: No false positive PII detection

### Parquet Files

1. **employees.parquet** (PII data):
   - 5 rows with employee information
   - Columns: employee_id, full_name, email, ssn, phone, hire_date
   - Tests: PII detection in binary formats

## Configuration

### MinIO Setup

MinIO is automatically started via `testcontainers` with:
- Access Key: `minio`
- Secret Key: `password`
- Exposed Port: Random (managed by testcontainers)
- Bucket: `test-pii-bucket`

### Auto-Classification Config

```python
{
    "type": "AutoClassification",
    "containerFilterPattern": {"includes": ["^test-pii-bucket.*"]},
    "storeSampleData": True,
    "sampleDataCount": 50,
    "enableAutoClassification": True,
    "confidence": 80,
}
```

### PII Tags

Two PII tags are created:

1. **PII.Sensitive** (Priority: 100)
   - EmailRecognizer
   - CreditCardRecognizer
   - UsSsnRecognizer
   - SpacyRecognizer (for person names)

2. **PII.NonSensitive** (Priority: 80)
   - PhoneRecognizer
   - DateRecognizer

## Fixtures

### Module-Scoped Fixtures

- `minio`: MinIO container instance
- `metadata`: OpenMetadata client
- `service_name`: Unique S3 service name
- `bucket_name`: Test bucket name
- `ingest_storage_metadata`: Metadata ingestion workflow result
- `run_autoclassification`: Auto-classification workflow result
- `pii_classification`: PII classification entity
- `sensitive_pii_tag`: Sensitive PII tag entity
- `non_sensitive_pii_tag`: Non-sensitive PII tag entity

### Session-Scoped Fixtures

- Recognizers: email, credit card, SSN, phone, date, spacy

## Debugging

### Check MinIO Container

```python
# In your test, add:
minio_container, minio_client = minio
print(f"MinIO endpoint: http://localhost:{minio_container.get_exposed_port(9000)}")

# List bucket contents
for obj in minio_client.list_objects(bucket_name):
    print(obj.object_name)
```

### Check Container Ingestion

```python
container = metadata.get_by_name(
    entity=Container,
    fqn=f"{service_name}.{bucket_name}.customers",
    fields=["*"]
)
print(f"Container: {container}")
print(f"Columns: {container.dataModel.columns if container.dataModel else 'None'}")
```

### Check Classification Results

```python
for col in container.dataModel.columns:
    print(f"{col.name}: {col.tags}")
```

## Common Issues

### 1. MinIO Container Fails to Start

**Symptom**: `RuntimeError: Could not start container`

**Solution**:
- Ensure Docker is running
- Check Docker has sufficient resources
- Try: `docker pull minio/minio:latest`

### 2. OpenMetadata Server Not Available

**Symptom**: `Connection refused to localhost:8585`

**Solution**:
- Start OpenMetadata: `./docker/run_local_docker.sh -m no-ui -d mysql`
- Verify: `curl http://localhost:8585/api/v1/system/version`

### 3. Tests Fail with "Container not found"

**Symptom**: Containers are not ingested

**Solution**:
- Check `ingest_storage_metadata` fixture ran successfully
- Verify MinIO is accessible
- Check workflow logs for errors

### 4. PII Not Detected

**Symptom**: Columns have no tags after classification

**Solution**:
- Verify PII tags were created (`pii_classification`, `sensitive_pii_tag` fixtures)
- Check recognizers are configured
- Verify sample data contains expected patterns
- Check `enableAutoClassification: True` in config

### 5. Sample Data Not Stored

**Symptom**: `container.sampleData` is None

**Solution**:
- Verify `storeSampleData: True` in config
- Check workflow completed successfully
- Verify container has `dataModel` with columns

## Integration with CI/CD

These tests are designed to run in CI/CD pipelines:

```yaml
# Example GitHub Actions
- name: Run Container Classification Tests
  run: |
    cd ingestion
    pytest tests/integration/container_classification/ -v --tb=short
```

## Performance

- **Execution Time**: ~2-3 minutes (includes MinIO startup, data upload, workflows)
- **Resource Usage**: Minimal (1 MinIO container, small test files)
- **Parallelization**: Tests are grouped to share MinIO container (xdist_group)

## Future Enhancements

Potential improvements:

1. Add tests for additional file formats (JSON, Avro, ORC)
2. Test nested/partitioned container structures
3. Add performance benchmarks for large files
4. Test custom PII recognizers
5. Test conflict resolution strategies
6. Add negative test cases (malformed files, permission errors)
7. Test incremental classification updates

## Related Files

- `/Users/eugenio/repos/work/OpenMetadata/ingestion/src/metadata/ingestion/ometa/mixins/container_mixin.py`
- `/Users/eugenio/repos/work/OpenMetadata/ingestion/src/metadata/sampler/processor.py`
- `/Users/eugenio/repos/work/OpenMetadata/ingestion/src/metadata/pii/base_processor.py`
- `/Users/eugenio/repos/work/OpenMetadata/openmetadata-service/src/main/java/org/openmetadata/service/jdbi3/ContainerRepository.java`
- `/Users/eugenio/repos/work/OpenMetadata/openmetadata-service/src/main/resources/json/schema/metadataIngestion/storageServiceAutoClassificationPipeline.json`

## Contributing

When adding new tests:

1. Follow pytest best practices (no unittest, use plain assert)
2. Use fixtures for setup/teardown
3. Add docstrings explaining what is tested
4. Update this README with new test coverage
5. Ensure tests are isolated and can run in any order
