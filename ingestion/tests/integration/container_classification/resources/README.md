# Container Classification Test Resources

This directory contains test data resources for container auto-classification integration tests.

## Overview

The test data is generated programmatically in `conftest.py` fixtures and uploaded to MinIO during test execution. This ensures:

1. **Reproducibility**: Tests generate consistent data on every run
2. **No External Dependencies**: No need to commit large binary files
3. **Flexibility**: Easy to modify test data patterns

## Test Data Files

### CSV Files

#### customers.csv
Contains PII data for testing sensitive data classification:
- `customer_id`: Integer identifier (non-PII)
- `name`: Person names (PII - Sensitive via SpacyRecognizer)
- `email`: Email addresses (PII - Sensitive via EmailRecognizer)
- `phone`: Phone numbers (PII - NonSensitive via PhoneRecognizer)
- `ssn`: Social Security Numbers (PII - Sensitive via UsSsnRecognizer)
- `credit_card`: Credit card numbers (PII - Sensitive via CreditCardRecognizer)
- `address`: Street addresses (non-PII or location)
- `created_date`: Dates (PII - NonSensitive via DateRecognizer)

#### orders.csv
Contains non-PII data for testing that classification doesn't over-tag:
- `order_id`: Order identifier
- `product_id`: Product identifier
- `quantity`: Numeric quantity
- `price`: Decimal price
- `order_date`: Order date

### Parquet Files

#### employees.parquet
Contains PII data in Parquet format to test binary file handling:
- `employee_id`: Integer identifier
- `full_name`: Person names (PII - Sensitive)
- `email`: Email addresses (PII - Sensitive)
- `ssn`: Social Security Numbers (PII - Sensitive)
- `phone`: Phone numbers (PII - NonSensitive)
- `hire_date`: Date values (PII - NonSensitive)

## Data Generation

Test data is generated in `conftest.py` using these fixtures:

- `pii_customers_csv()`: Generates CSV with PII fields
- `non_pii_orders_csv()`: Generates CSV without PII
- `pii_employees_parquet()`: Generates Parquet with PII using pandas

## Adding New Test Data

To add new test data files:

1. Create a new fixture in `conftest.py`:
   ```python
   @pytest.fixture(scope="module")
   def my_new_test_data():
       # Generate data
       return data_bytes
   ```

2. Update `upload_test_data` fixture to upload your file:
   ```python
   minio_client.put_object(
       bucket_name,
       "my_file.csv",
       io.BytesIO(my_new_test_data),
       len(my_new_test_data),
   )
   ```

3. Add corresponding test cases in test files

## PII Detection Patterns

The tests validate these PII recognizers:

### Sensitive PII
- **EmailRecognizer**: Detects email addresses
- **CreditCardRecognizer**: Detects credit card numbers
- **UsSsnRecognizer**: Detects US SSN format (XXX-XX-XXXX)
- **SpacyRecognizer**: Detects person names using NLP

### Non-Sensitive PII
- **PhoneRecognizer**: Detects phone numbers
- **DateRecognizer**: Detects date patterns

## Sample Data in Tests

Sample data is used for:
1. **Classification**: PII recognizers analyze sample data
2. **Verification**: Tests validate correct classification
3. **API Testing**: Tests verify sample data storage/retrieval

Sample data count is controlled by `sampleDataCount` config parameter (default: 50 rows).
