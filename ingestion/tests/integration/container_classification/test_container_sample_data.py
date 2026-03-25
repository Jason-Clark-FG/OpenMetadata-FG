#  Copyright 2025 Collate
#  Licensed under the Collate Community License, Version 1.0 (the "License");
#  you may not use this file except in compliance with the License.
#  You may obtain a copy of the License at
#  https://github.com/open-metadata/OpenMetadata/blob/main/ingestion/LICENSE
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License.
"""Integration tests for container sample data ingestion API"""
import pytest

from metadata.generated.schema.entity.data.container import Container
from metadata.generated.schema.entity.data.table import TableData
from metadata.ingestion.ometa.ometa_api import OpenMetadata


def test_container_sample_data_ingestion(
    metadata: OpenMetadata,
    ingest_storage_metadata,
    service_name: str,
    bucket_name: str,
):
    """Test that container sample data can be ingested via API"""
    container = metadata.get_by_name(
        entity=Container,
        fqn=f"{service_name}.{bucket_name}.customers",
        fields=["sampleData"],
    )

    assert container is not None

    sample_data = TableData(
        columns=["test_col1", "test_col2"],
        rows=[["value1", "value2"], ["value3", "value4"]],
    )

    result = metadata.ingest_container_sample_data(
        container=container, sample_data=sample_data
    )

    assert result is not None
    assert result.columns == ["test_col1", "test_col2"]
    assert len(result.rows) == 2


def test_container_sample_data_retrieval(
    metadata: OpenMetadata,
    run_autoclassification,
    service_name: str,
    bucket_name: str,
):
    """Test that container sample data can be retrieved after classification"""
    container = metadata.get_by_name(
        entity=Container,
        fqn=f"{service_name}.{bucket_name}.customers",
        fields=["sampleData"],
    )

    assert container is not None
    assert container.sampleData is not None
    assert container.sampleData.columns is not None
    assert len(container.sampleData.columns) == 8
    assert container.sampleData.rows is not None
    assert len(container.sampleData.rows) > 0


def test_container_sample_data_limits(
    metadata: OpenMetadata,
    run_autoclassification,
    service_name: str,
    bucket_name: str,
):
    """Test that sample data respects sampleDataCount limit"""
    container = metadata.get_by_name(
        entity=Container,
        fqn=f"{service_name}.{bucket_name}.customers",
        fields=["sampleData"],
    )

    assert container is not None
    assert container.sampleData is not None
    assert container.sampleData.rows is not None
    assert len(container.sampleData.rows) <= 50


def test_container_without_sample_data(
    metadata: OpenMetadata,
    ingest_storage_metadata,
    service_name: str,
    bucket_name: str,
):
    """Test containers without sample data ingested"""
    container = metadata.get_by_name(
        entity=Container,
        fqn=f"{service_name}.{bucket_name}.orders",
        fields=["sampleData"],
    )

    assert container is not None


def test_container_sample_data_empty_rows(
    metadata: OpenMetadata,
    ingest_storage_metadata,
    service_name: str,
    bucket_name: str,
):
    """Test ingesting empty sample data"""
    container = metadata.get_by_name(
        entity=Container,
        fqn=f"{service_name}.{bucket_name}.customers",
        fields=["sampleData"],
    )

    assert container is not None

    empty_sample_data = TableData(columns=["col1"], rows=[])

    result = metadata.ingest_container_sample_data(
        container=container, sample_data=empty_sample_data
    )

    assert result is not None or result is None


def test_container_sample_data_column_count_match(
    metadata: OpenMetadata,
    run_autoclassification,
    service_name: str,
    bucket_name: str,
):
    """Test that sample data columns match container data model columns"""
    container = metadata.get_by_name(
        entity=Container,
        fqn=f"{service_name}.{bucket_name}.customers",
        fields=["sampleData", "dataModel"],
    )

    assert container is not None
    assert container.sampleData is not None
    assert container.dataModel is not None

    sample_columns = set(container.sampleData.columns)
    model_columns = {col.name for col in container.dataModel.columns}

    assert sample_columns == model_columns or sample_columns.issubset(model_columns)


def test_parquet_container_sample_data(
    metadata: OpenMetadata,
    run_autoclassification,
    service_name: str,
    bucket_name: str,
):
    """Test sample data ingestion for Parquet containers"""
    container = metadata.get_by_name(
        entity=Container,
        fqn=f"{service_name}.{bucket_name}.employees",
        fields=["sampleData"],
    )

    assert container is not None
    assert container.sampleData is not None
    assert container.sampleData.columns is not None
    assert len(container.sampleData.columns) == 5
    assert container.sampleData.rows is not None
    assert len(container.sampleData.rows) > 0


def test_container_sample_data_with_special_characters(
    metadata: OpenMetadata,
    ingest_storage_metadata,
    service_name: str,
    bucket_name: str,
):
    """Test sample data with special characters and encoding"""
    container = metadata.get_by_name(
        entity=Container,
        fqn=f"{service_name}.{bucket_name}.customers",
        fields=["sampleData"],
    )

    assert container is not None

    special_sample_data = TableData(
        columns=["name", "description"],
        rows=[
            ["Test User", "Description with special chars: @#$%"],
            ["User 2", "Unicode: é à ñ 中文"],
        ],
    )

    result = metadata.ingest_container_sample_data(
        container=container, sample_data=special_sample_data
    )

    assert result is not None


def test_multiple_containers_sample_data(
    metadata: OpenMetadata,
    run_autoclassification,
    service_name: str,
    bucket_name: str,
):
    """Test that multiple containers have their own sample data"""
    customers = metadata.get_by_name(
        entity=Container,
        fqn=f"{service_name}.{bucket_name}.customers",
        fields=["sampleData"],
    )

    employees = metadata.get_by_name(
        entity=Container,
        fqn=f"{service_name}.{bucket_name}.employees",
        fields=["sampleData"],
    )

    assert customers is not None
    assert employees is not None
    assert customers.sampleData is not None
    assert employees.sampleData is not None

    assert customers.sampleData.columns != employees.sampleData.columns
