/*
 *  Copyright 2026 Collate.
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
import {
  Autocomplete,
  ButtonUtility,
  Typography,
} from '@openmetadata/ui-core-components';
import { Edit01, XClose } from '@openmetadata/ui-core-components/icons';
import { AxiosError } from 'axios';
import { debounce } from 'lodash';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { EntityType } from '../../../enums/entity.enum';
import { SearchIndex } from '../../../enums/search.enum';
import { EntityReference } from '../../../generated/entity/type';
import { searchData } from '../../../rest/miscAPI';
import { getEntityName } from '../../../utils/EntityNameUtils';
import { getEntityReferenceListFromEntities } from '../../../utils/EntityReferenceUtils';
import { getEntityDetailsPath } from '../../../utils/RouterUtils';
import { showErrorToast } from '../../../utils/ToastUtils';

const PAGE_SIZE = 25;
const SEARCH_DEBOUNCE_MS = 300;

export interface ReplacedByProps {
  tableFqn: string;
  replacedBy?: EntityReference;
  canEdit: boolean;
  onUpdate: (replacedBy?: EntityReference) => Promise<void>;
}

export const ReplacedBy = ({
  tableFqn,
  replacedBy,
  canEdit,
  onUpdate,
}: ReplacedByProps) => {
  const { t } = useTranslation();
  const [isEditing, setIsEditing] = useState(false);
  const [options, setOptions] = useState<EntityReference[]>([]);
  const latestRequestId = useRef(0);

  const fetchOptions = useCallback(
    async (searchText: string) => {
      const requestId = ++latestRequestId.current;
      try {
        const response = await searchData(
          searchText,
          1,
          PAGE_SIZE,
          'deleted:false',
          'displayName.keyword',
          'asc',
          SearchIndex.TABLE
        );
        if (requestId !== latestRequestId.current) {
          return;
        }
        setOptions(
          getEntityReferenceListFromEntities(
            response.data.hits.hits.map((hit) => hit._source),
            EntityType.TABLE
          ).filter((option) => option.fullyQualifiedName !== tableFqn)
        );
      } catch (error) {
        if (requestId === latestRequestId.current) {
          setOptions([]);
          showErrorToast(error as AxiosError);
        }
      }
    },
    [tableFqn]
  );

  const debouncedFetch = useMemo(
    () => debounce(fetchOptions, SEARCH_DEBOUNCE_MS),
    [fetchOptions]
  );

  useEffect(() => {
    if (isEditing) {
      void fetchOptions('');
    }

    return () => debouncedFetch.cancel();
  }, [isEditing, fetchOptions, debouncedFetch]);

  const items = useMemo(
    () =>
      options.map((option) => ({
        id: option.id,
        label: option.fullyQualifiedName ?? getEntityName(option),
      })),
    [options]
  );

  const handleSelect = useCallback(
    async (key: string | number) => {
      const chosen = options.find((option) => option.id === String(key));
      if (chosen) {
        await onUpdate(chosen);
      }
      setIsEditing(false);
    },
    [options, onUpdate]
  );

  const handleClear = useCallback(async () => {
    await onUpdate(undefined);
    setIsEditing(false);
  }, [onUpdate]);

  if (isEditing) {
    return (
      <div className="tw:w-72" data-testid="replaced-by-editor">
        <Autocomplete
          items={items}
          multiple={false}
          placeholder={t('label.search-entity', { entity: t('label.table') })}
          selectedItems={[]}
          onItemInserted={handleSelect}
          onSearchChange={debouncedFetch}>
          {(item) => (
            <Autocomplete.Item id={String(item.id)} key={item.id}>
              {item.label}
            </Autocomplete.Item>
          )}
        </Autocomplete>
      </div>
    );
  }

  if (!replacedBy) {
    return canEdit ? (
      <button
        className="tw:text-sm tw:font-medium tw:text-brand-secondary"
        data-testid="replaced-by-add"
        type="button"
        onClick={() => setIsEditing(true)}>
        {t('label.set-entity', { entity: t('label.replaced-by') })}
      </button>
    ) : null;
  }

  return (
    <span
      className="tw:inline-flex tw:items-center tw:gap-1"
      data-testid="replaced-by">
      <Typography as="span" className="tw:text-tertiary" size="text-sm">
        {t('label.replaced-by')}
      </Typography>
      <Link
        className="tw:text-sm tw:font-medium tw:text-brand-secondary"
        data-testid="replaced-by-link"
        to={getEntityDetailsPath(
          EntityType.TABLE,
          replacedBy.fullyQualifiedName ?? ''
        )}>
        {replacedBy.fullyQualifiedName ?? getEntityName(replacedBy)}
      </Link>
      {canEdit && (
        <>
          <ButtonUtility
            data-testid="replaced-by-edit"
            icon={Edit01}
            tooltip={t('label.edit-entity', { entity: t('label.replaced-by') })}
            onClick={() => setIsEditing(true)}
          />
          <ButtonUtility
            data-testid="replaced-by-remove"
            icon={XClose}
            tooltip={t('label.remove-entity', {
              entity: t('label.replaced-by'),
            })}
            onClick={handleClear}
          />
        </>
      )}
    </span>
  );
};
