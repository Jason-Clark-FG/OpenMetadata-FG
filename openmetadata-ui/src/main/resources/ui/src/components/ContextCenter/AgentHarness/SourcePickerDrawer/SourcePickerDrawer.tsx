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
  Box,
  Button,
  Checkbox,
  Input,
  SlideoutMenu,
  Typography,
} from '@openmetadata/ui-core-components';
import { Search } from '@openmetadata/ui-core-components/icons';
import { AxiosError } from 'axios';
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  MAX_PICKED_ITEMS,
  SOURCE_LABEL_KEYS,
} from '../../../../constants/discovery/agentHarness.constants';
import { PickableSource } from '../../../../interface/discovery/agentHarness.interface';
import {
  HarnessSourceItem,
  searchHarnessSource,
} from '../../../../rest/AgentHarnessAPI';
import { getEntityName } from '../../../../utils/EntityNameUtils';
import { showErrorToast } from '../../../../utils/ToastUtils';
import Loader from '../../../common/Loader/Loader';

const SEARCH_DELAY_MS = 300;

export interface SourcePickerDrawerProps {
  source?: PickableSource;
  pickedIds: string[];
  onClose: () => void;
  onSave: (source: PickableSource, ids: string[]) => void;
}

const SourcePickerDrawer = ({
  source,
  pickedIds,
  onClose,
  onSave,
}: SourcePickerDrawerProps) => {
  const { t } = useTranslation();
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<HarnessSourceItem[]>([]);
  const [selected, setSelected] = useState<HarnessSourceItem[]>([]);
  const [isSearching, setIsSearching] = useState(false);

  useEffect(() => {
    if (!source) {
      return;
    }
    setQuery('');
    setSelected([]);
    if (pickedIds.length) {
      searchHarnessSource(source, { ids: pickedIds })
        .then(setSelected)
        .catch((error) => showErrorToast(error as AxiosError));
    }
  }, [source]);

  useEffect(() => {
    if (!source) {
      return;
    }
    let current = true;
    setIsSearching(true);
    const timeout = window.setTimeout(() => {
      searchHarnessSource(source, { query })
        .then((items) => current && setResults(items))
        .catch((error) => current && showErrorToast(error as AxiosError))
        .finally(() => current && setIsSearching(false));
    }, SEARCH_DELAY_MS);

    return () => {
      current = false;
      window.clearTimeout(timeout);
    };
  }, [source, query]);

  const selectedIds = new Set(selected.map(({ id }) => id));
  const isFull = selected.length >= MAX_PICKED_ITEMS;

  const toggle = (item: HarnessSourceItem, isSelected: boolean) =>
    setSelected((current) =>
      isSelected
        ? [...current, item]
        : current.filter(({ id }) => id !== item.id)
    );

  const sourceLabel = source ? t(SOURCE_LABEL_KEYS[source]) : '';

  const renderItem = (item: HarnessSourceItem) => (
    <Checkbox
      data-testid={`source-picker-item-${item.name}`}
      hint={item.fullyQualifiedName}
      isDisabled={isFull && !selectedIds.has(item.id)}
      isSelected={selectedIds.has(item.id)}
      key={item.id}
      label={getEntityName(item)}
      onChange={(isSelected) => toggle(item, isSelected)}
    />
  );

  return (
    <SlideoutMenu
      aria-label={t('label.manage-entity', { entity: sourceLabel })}
      isOpen={Boolean(source)}
      width={480}
      onOpenChange={(open) => !open && onClose()}>
      <SlideoutMenu.Header onClose={onClose}>
        <Typography as="h3" size="text-lg" weight="semibold">
          {t('label.manage-entity', { entity: sourceLabel })}
        </Typography>
        <Typography className="tw:text-tertiary" size="text-sm">
          {t('message.agent-harness-picker-hint')}
        </Typography>
      </SlideoutMenu.Header>
      <SlideoutMenu.Content data-testid="source-picker">
        <Input
          aria-label={t('label.search')}
          icon={Search}
          inputDataTestId="source-picker-search"
          placeholder={t('label.search-entity', { entity: sourceLabel })}
          value={query}
          onChange={setQuery}
        />
        {selected.length > 0 && (
          <Box direction="col" gap={2}>
            <Typography
              className="tw:text-tertiary"
              size="text-xs"
              weight="semibold">
              {t('label.selected-count', { count: selected.length })}
            </Typography>
            {selected.map(renderItem)}
          </Box>
        )}
        <Box direction="col" gap={2}>
          {isSearching && results.length === 0 ? (
            <Loader size="small" />
          ) : (
            results.filter(({ id }) => !selectedIds.has(id)).map(renderItem)
          )}
          {!isSearching && results.length === 0 && (
            <Typography className="tw:text-tertiary" size="text-sm">
              {t('message.no-data-available')}
            </Typography>
          )}
        </Box>
      </SlideoutMenu.Content>
      <SlideoutMenu.Footer>
        <Box gap={3} justify="end">
          <Button color="secondary" size="sm" onPress={onClose}>
            {t('label.cancel')}
          </Button>
          <Button
            color="primary"
            data-testid="source-picker-save"
            size="sm"
            onPress={() => {
              if (source) {
                onSave(
                  source,
                  selected.map(({ id }) => id)
                );
              }
            }}>
            {t('label.save')}
          </Button>
        </Box>
      </SlideoutMenu.Footer>
    </SlideoutMenu>
  );
};

export default SourcePickerDrawer;
