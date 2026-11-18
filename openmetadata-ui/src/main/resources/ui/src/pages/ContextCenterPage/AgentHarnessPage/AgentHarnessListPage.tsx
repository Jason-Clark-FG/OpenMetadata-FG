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
  Badge,
  Box,
  Button,
  Card,
  PageLayout,
  PaginationCardWithControls,
  Skeleton,
  Typography,
} from '@openmetadata/ui-core-components';
import {
  Persona as PersonaIcon,
  Plus,
} from '@openmetadata/ui-core-components/icons';
import { AxiosError } from 'axios';
import classNames from 'classnames';
import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';
import DocumentTitle from '../../../components/common/DocumentTitle/DocumentTitle';
import NoDataPlaceholder from '../../../components/common/EmptyPlaceholder/NoDataPlaceholder';
import PermissionPlaceholder from '../../../components/common/EmptyPlaceholder/PermissionPlaceholder';
import DefaultHarnessCard from '../../../components/ContextCenter/AgentHarness/DefaultHarnessCard/DefaultHarnessCard';
import NewPersonaHarnessModal from '../../../components/ContextCenter/AgentHarness/NewPersonaHarnessModal/NewPersonaHarnessModal';
import PersonaHarnessTable from '../../../components/ContextCenter/AgentHarness/PersonaHarnessTable/PersonaHarnessTable';
import ContextCenterHeader from '../../../components/ContextCenter/ContextCenterHeader/ContextCenterHeader.component';
import { useContextCenterPageLayout } from '../../../components/ContextCenter/ContextCenterLayout/useContextCenterPageLayout';
import { LIST_PAGE_SIZE_OPTIONS } from '../../../constants/constants';
import { MEMORY_STATUSES_AWAITING_DECISION } from '../../../constants/ContextCenter.constants';
import { CursorType } from '../../../enums/pagination.enum';
import { AgentHarnessPersonaSummary } from '../../../generated/api/context/agentHarnessPersonaSummary';
import { AgentHarnessState } from '../../../generated/api/context/agentHarnessState';
import { Surface } from '../../../generated/api/context/agentHarnessSurfaces';
import { useAuth } from '../../../hooks/authHooks';
import { usePaging } from '../../../hooks/paging/usePaging';
import { useIsAiMode } from '../../../hooks/useAppMode';
import {
  HarnessAdditions,
  HarnessTab,
} from '../../../interface/discovery/agentHarness.interface';
import {
  getAgentHarnessSurfaces,
  getDefaultAgentHarness,
  getPersonaAgentHarness,
  listAgentHarnessPersonas,
  setAgentHarnessEnabled,
} from '../../../rest/AgentHarnessAPI';
import { getListContextMemories } from '../../../rest/contextMemoryAPI';
import { getUsers } from '../../../rest/userAPI';
import contextCenterClassBase from '../../../utils/ContextCenterClassBase';
import {
  additionsSummary,
  editableSpec,
} from '../../../utils/discovery/context-center/agentHarness.utils';
import { getEntityName } from '../../../utils/EntityNameUtils';
import { showErrorToast } from '../../../utils/ToastUtils';

const SKELETON_ROWS = 7;

const personaFqn = ({ persona }: AgentHarnessPersonaSummary) =>
  persona.fullyQualifiedName ?? persona.name ?? persona.id;

const AgentHarnessListPage = () => {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const isAiMode = useIsAiMode();
  const pageLayoutClassNames = useContextCenterPageLayout();
  const { isAdminUser } = useAuth();
  const [defaultState, setDefaultState] = useState<AgentHarnessState>();
  const [rows, setRows] = useState<AgentHarnessPersonaSummary[]>([]);
  const [additions, setAdditions] = useState<Map<string, HarnessAdditions>>(
    new Map()
  );
  const [userCount, setUserCount] = useState<number>();
  const [pendingMemories, setPendingMemories] = useState<number>();
  const [surfaces, setSurfaces] = useState<Surface[]>();
  const [isLoading, setIsLoading] = useState(true);
  const [isNewOpen, setIsNewOpen] = useState(false);
  const {
    currentPage,
    handlePageChange,
    handlePageSizeChange,
    handlePagingChange,
    pageSize,
    paging,
    pagingCursor: { cursorType: urlCursorType, cursorValue: urlCursorValue },
    showPagination,
  } = usePaging();

  const loadAdditions = useCallback(
    async (summaries: AgentHarnessPersonaSummary[]) => {
      const withHarness = summaries.filter(({ harnessId }) => harnessId);
      const states = await Promise.allSettled(
        withHarness.map((row) => getPersonaAgentHarness(personaFqn(row)))
      );
      const next = new Map<string, HarnessAdditions>();
      states.forEach((result) => {
        if (result.status === 'fulfilled') {
          next.set(
            result.value.published.id,
            additionsSummary(editableSpec(result.value))
          );
        }
      });
      setAdditions(next);
    },
    []
  );

  const fetchPersonas = useCallback(
    async (cursor?: Partial<Record<CursorType, string>>) => {
      try {
        const page = await listAgentHarnessPersonas({
          ...cursor,
          limit: pageSize,
        });
        const sorted = [...page.data].sort((a, b) =>
          getEntityName(a.persona).localeCompare(getEntityName(b.persona))
        );
        setRows(sorted);
        handlePagingChange(page.paging);
        void loadAdditions(sorted);
      } catch (error) {
        setRows([]);
        showErrorToast(error as AxiosError);
      }
    },
    [handlePagingChange, loadAdditions, pageSize]
  );

  useEffect(() => {
    if (!isAdminUser) {
      setIsLoading(false);

      return;
    }
    setIsLoading(true);
    const cursor =
      urlCursorType && urlCursorValue
        ? { [urlCursorType]: urlCursorValue }
        : undefined;
    void Promise.all([
      getDefaultAgentHarness()
        .then(setDefaultState)
        .catch((error) => showErrorToast(error as AxiosError)),
      fetchPersonas(cursor),
    ]).finally(() => setIsLoading(false));
  }, [fetchPersonas, isAdminUser, urlCursorType, urlCursorValue]);

  useEffect(() => {
    if (!isAdminUser) {
      return;
    }
    getUsers({ isBot: false, limit: 1 })
      .then(({ paging: users }) => setUserCount(users.total))
      .catch(() => setUserCount(undefined));
    getListContextMemories({
      limit: 0,
      statuses: MEMORY_STATUSES_AWAITING_DECISION.join(','),
    })
      .then(({ paging: memories }) => setPendingMemories(memories.total))
      .catch(() => setPendingMemories(undefined));
    getAgentHarnessSurfaces()
      .then(({ surfaces: list }) => setSurfaces(list))
      .catch(() => setSurfaces(undefined));
  }, [isAdminUser]);

  const handlePageNavigation = (newPage: number) => {
    // Cursor paging moves one page at a time.
    const cursorType =
      newPage > currentPage ? CursorType.AFTER : CursorType.BEFORE;
    const cursor = paging[cursorType];
    if (Math.abs(newPage - currentPage) !== 1 || !cursor) {
      return;
    }
    handlePageChange(newPage, { cursorType, cursorValue: cursor });
  };

  const setRowEnabled = (personaId: string, enabled: boolean) =>
    setRows((current) =>
      current.map((row) =>
        row.persona.id === personaId ? { ...row, enabled } : row
      )
    );

  const handleEnabledChange = async (
    row: AgentHarnessPersonaSummary,
    enabled: boolean
  ) => {
    if (!row.harnessId) {
      return;
    }
    setRowEnabled(row.persona.id, enabled);
    try {
      await setAgentHarnessEnabled(row.harnessId, enabled);
    } catch (error) {
      setRowEnabled(row.persona.id, !enabled);
      showErrorToast(error as AxiosError);
    }
  };

  const openPersona = (fqn: string) =>
    navigate(contextCenterClassBase.getAgentHarnessPersonaPath(fqn));

  const renderPersonas = () => {
    if (isLoading) {
      return (
        <Box data-testid="persona-harness-skeleton" direction="col" gap={2}>
          {Array.from({ length: SKELETON_ROWS }, (_, index) => (
            <Skeleton className="tw:h-14 tw:w-full" key={index} />
          ))}
        </Box>
      );
    }
    if (rows.length === 0) {
      return (
        <div className="tw:relative tw:min-h-60">
          <NoDataPlaceholder
            description={t('message.agent-harness-no-persona-description')}
            icon={PersonaIcon}
            title={t('message.no-persona-available')}
          />
        </div>
      );
    }

    return (
      <Card>
        <PersonaHarnessTable
          additions={additions}
          canEdit={Boolean(isAdminUser)}
          rows={rows}
          onEnabledChange={handleEnabledChange}
          onOpen={(row) => openPersona(personaFqn(row))}
        />
        {showPagination && (
          <PaginationCardWithControls
            minimal
            page={currentPage}
            pageSize={pageSize}
            pageSizeOptions={LIST_PAGE_SIZE_OPTIONS}
            total={Math.max(1, Math.ceil((paging.total ?? 0) / pageSize))}
            onPageChange={handlePageNavigation}
            onPageSizeChange={handlePageSizeChange}
          />
        )}
      </Card>
    );
  };

  const renderContent = () => {
    if (!isAdminUser) {
      return (
        <div className="tw:relative tw:min-h-80">
          <PermissionPlaceholder permissionValue={t('label.agent-harness')} />
        </div>
      );
    }

    return (
      <Box direction="col" gap={6}>
        {defaultState ? (
          <DefaultHarnessCard
            pendingMemories={pendingMemories}
            state={defaultState}
            surfaces={surfaces}
            userCount={userCount}
            onEdit={() =>
              navigate(contextCenterClassBase.getAgentHarnessDefaultPath())
            }
          />
        ) : (
          isLoading && <Skeleton className="tw:h-44 tw:w-full" />
        )}
        <Box direction="col" gap={3}>
          <Box align="center" gap={2} wrap="wrap">
            <h2 className="tw:m-0 tw:text-md tw:font-semibold tw:text-primary">
              {t('label.persona-harness-plural')}
            </h2>
            <Badge color="gray" data-testid="persona-harness-count" size="sm">
              {paging.total ?? rows.length}
            </Badge>
            <Typography className="tw:ml-auto tw:text-tertiary" size="text-sm">
              {t('message.agent-harness-persona-list-hint')}
            </Typography>
          </Box>
          {renderPersonas()}
        </Box>
      </Box>
    );
  };

  return (
    <div
      className={`tw:flex tw:flex-col tw:w-full tw:h-full tw:overflow-hidden ${contextCenterClassBase.getPageContainerClassName(
        isAiMode
      )}`}
      data-testid="agent-harness-list-page">
      <DocumentTitle title={t('label.agent-harness')} />
      <PageLayout
        className={pageLayoutClassNames.root}
        data-testid="context-center-page-layout">
        <PageLayout.Header className={pageLayoutClassNames.header}>
          <ContextCenterHeader
            actionsSlot={
              isAdminUser ? (
                <Box gap={3}>
                  <Button
                    color="secondary"
                    data-testid="distribute-to-surfaces"
                    size="sm"
                    onPress={() =>
                      navigate(
                        contextCenterClassBase.getAgentHarnessDefaultPath(
                          HarnessTab.Surfaces
                        )
                      )
                    }>
                    {t('label.distribute-to-surfaces')}
                  </Button>
                  <Button
                    color="primary"
                    data-testid="new-persona-harness"
                    iconLeading={Plus}
                    size="sm"
                    onPress={() => setIsNewOpen(true)}>
                    {t('label.new-persona-harness')}
                  </Button>
                </Box>
              ) : undefined
            }
            breadcrumbs={[{ label: t('label.agent-harness') }]}
            hasPermission={isAdminUser}
            subtitle={t('message.agent-harness-description')}
            title={t('label.agent-harness')}
          />
        </PageLayout.Header>
        <PageLayout.Content
          className={classNames(
            'tw:min-h-0 tw:overflow-y-auto tw:relative',
            pageLayoutClassNames.content
          )}>
          {renderContent()}
        </PageLayout.Content>
      </PageLayout>
      <NewPersonaHarnessModal
        isOpen={isNewOpen}
        onClose={() => setIsNewOpen(false)}
        onCreated={(fqn) => {
          setIsNewOpen(false);
          openPersona(fqn);
        }}
      />
    </div>
  );
};

export default AgentHarnessListPage;
