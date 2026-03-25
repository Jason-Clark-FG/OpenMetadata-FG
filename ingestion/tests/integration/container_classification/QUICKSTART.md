# Container Auto-Classification Tests - Quick Start

## TL;DR

```bash
# 1. Start OpenMetadata server
./docker/run_local_docker.sh -m no-ui -d mysql

# 2. Run tests
cd ingestion
pytest tests/integration/container_classification/ -v

# 3. Run specific test
pytest tests/integration/container_classification/test_container_classification.py::test_container_pii_classification_csv -v
```

## What These Tests Do

1. **Start MinIO** (S3-compatible storage) using testcontainers
2. **Generate test data** with PII (emails, SSN, credit cards) and non-PII data
3. **Upload files** to MinIO bucket (CSV and Parquet formats)
4. **Ingest metadata** from S3/MinIO into OpenMetadata
5. **Run auto-classification** to detect PII in container columns
6. **Verify tags** are applied correctly with proper confidence scores

## Test Files Overview

### conftest.py (479 lines)
- MinIO container setup
- Test data generation (CSV with PII, Parquet with PII, non-PII CSV)
- S3 storage service configuration
- PII classification and tag fixtures
- Metadata and auto-classification workflow runners

### test_container_classification.py (323 lines)
**15 tests covering:**
- Storage service ingestion
- Container metadata ingestion
- PII detection in CSV files
- PII detection in Parquet files
- Non-sensitive PII (phone, dates)
- No false positives on non-PII data
- Classification reasons/explanations
- Sample data storage
- Workflow completion
- Container filter patterns
- Parametrized column-specific tests

### test_container_sample_data.py (226 lines)
**10 tests covering:**
- Sample data API ingestion
- Sample data retrieval
- Sample data limits (sampleDataCount)
- Empty data handling
- Column matching with data model
- Parquet sample data
- Special character encoding
- Multiple container isolation

## Key Features Tested

### ✅ PII Detection
- Email addresses → `PII.Sensitive`
- Social Security Numbers → `PII.Sensitive`
- Credit card numbers → `PII.Sensitive`
- Person names → `PII.Sensitive`
- Phone numbers → `PII.NonSensitive`
- Dates → `PII.NonSensitive`

### ✅ File Format Support
- CSV files
- Parquet files
- Both structured containers with data models

### ✅ API Integration
- Container sample data ingestion
- Sample data retrieval
- Tag application via auto-classification

### ✅ Configuration
- `containerFilterPattern` filtering
- `sampleDataCount` limits
- `storeSampleData` flag
- `confidence` thresholds

## Expected Output

```
tests/integration/container_classification/test_container_classification.py
✓ test_storage_service_ingested
✓ test_containers_ingested
✓ test_container_pii_classification_csv
✓ test_container_pii_classification_parquet
✓ test_container_non_sensitive_pii
✓ test_container_no_pii_classification
✓ test_container_classification_reasons
✓ test_container_sample_data_stored
✓ test_autoclassification_workflow_status
✓ test_container_filter_pattern
✓ test_specific_column_classification[customers-email-PII.Sensitive]
✓ test_specific_column_classification[customers-ssn-PII.Sensitive]
✓ test_specific_column_classification[customers-credit_card-PII.Sensitive]
✓ test_specific_column_classification[employees-email-PII.Sensitive]
✓ test_specific_column_classification[employees-ssn-PII.Sensitive]

tests/integration/container_classification/test_container_sample_data.py
✓ test_container_sample_data_ingestion
✓ test_container_sample_data_retrieval
✓ test_container_sample_data_limits
✓ test_container_without_sample_data
✓ test_container_sample_data_empty_rows
✓ test_container_sample_data_column_count_match
✓ test_parquet_container_sample_data
✓ test_container_sample_data_with_special_characters
✓ test_multiple_containers_sample_data

25 passed in ~2-3 minutes
```

## Troubleshooting

### MinIO Won't Start
```bash
# Check Docker
docker ps

# Pull MinIO image manually
docker pull minio/minio:latest

# Check Docker resources (needs ~512MB RAM)
```

### OpenMetadata Not Running
```bash
# Start OpenMetadata
cd /Users/eugenio/repos/work/OpenMetadata
./docker/run_local_docker.sh -m no-ui -d mysql

# Verify it's running
curl http://localhost:8585/api/v1/system/version
```

### Tests Fail
```bash
# Run with verbose output
pytest tests/integration/container_classification/ -v -s

# Run with debug logging
pytest tests/integration/container_classification/ -v --log-cli-level=DEBUG

# Run single test to isolate issue
pytest tests/integration/container_classification/test_container_classification.py::test_storage_service_ingested -v -s
```

## What's Being Tested?

### Backend (Java)
- `ContainerRepository.addSampleData()`
- `ContainerRepository.getSampleData()`
- `ContainerResource` PUT/GET/DELETE endpoints

### Python Ingestion
- `container_mixin.py`: `ingest_container_sample_data()`
- `processor.py`: `_run_for_container()`
- `base_processor.py`: `_get_entity_columns()` for containers
- PII recognizers on container columns

### Integration
- S3/MinIO metadata ingestion
- Auto-classification workflow
- Container filter patterns
- Sample data storage

## Next Steps

After running tests successfully:

1. **Review test output** to understand classification behavior
2. **Modify test data** in `conftest.py` to test custom scenarios
3. **Add new recognizers** to test additional PII types
4. **Extend to new file formats** (JSON, Avro, ORC)
5. **Performance test** with larger files

## Documentation

- `README.md` - Comprehensive documentation
- `resources/README.md` - Test data documentation
- This file - Quick start guide
