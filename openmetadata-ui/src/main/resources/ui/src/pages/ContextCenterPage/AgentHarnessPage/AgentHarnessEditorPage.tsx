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
  PageLayout,
  Tabs,
} from '@openmetadata/ui-core-components';
import { Eye, EyeOff } from '@openmetadata/ui-core-components/icons';
import { AxiosError } from 'axios';
import classNames from 'classnames';
import { Key, useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useSearchParams } from 'react-router-dom';
import DocumentTitle from '../../../components/common/DocumentTitle/DocumentTitle';
import NoDataPlaceholder from '../../../components/common/EmptyPlaceholder/NoDataPlaceholder';
import PermissionPlaceholder from '../../../components/common/EmptyPlaceholder/PermissionPlaceholder';
import Loader from '../../../components/common/Loader/Loader';
import { NavigationBlocker } from '../../../components/common/NavigationBlocker/NavigationBlocker';
import CompiledContextRail from '../../../components/ContextCenter/AgentHarness/CompiledContextRail/CompiledContextRail';
import ContextTab from '../../../components/ContextCenter/AgentHarness/ContextTab/ContextTab';
import DeliveryTab from '../../../components/ContextCenter/AgentHarness/DeliveryTab/DeliveryTab';
import GuardrailsTab from '../../../components/ContextCenter/AgentHarness/GuardrailsTab/GuardrailsTab';
import HarnessSettingsBar from '../../../components/ContextCenter/AgentHarness/HarnessSettingsBar/HarnessSettingsBar';
import InheritanceStrip from '../../../components/ContextCenter/AgentHarness/InheritanceStrip/InheritanceStrip';
import InstructionsTab from '../../../components/ContextCenter/AgentHarness/InstructionsTab/InstructionsTab';
import MemoryTab from '../../../components/ContextCenter/AgentHarness/MemoryTab/MemoryTab';
import SurfacesTab from '../../../components/ContextCenter/AgentHarness/SurfacesTab/SurfacesTab';
import ContextCenterHeader from '../../../components/ContextCenter/ContextCenterHeader/ContextCenterHeader.component';
import { useContextCenterPageLayout } from '../../../components/ContextCenter/ContextCenterLayout/useContextCenterPageLayout';
import {
  HARNESS_STATUS_BADGE,
  HARNESS_TABS,
  HARNESS_TAB_LABEL_KEYS,
} from '../../../constants/discovery/agentHarness.constants';
import {
  AgentHarnessState,
  GuardrailLevel,
  HarnessKind,
} from '../../../generated/api/context/agentHarnessState';
import { GuardrailDefinition } from '../../../generated/api/context/guardrailDefinition';
import { SkillDefinition } from '../../../generated/api/context/skillDefinition';
import { useAuth } from '../../../hooks/authHooks';
import {
  AgentHarnessDraft,
  useAgentHarnessDraft,
} from '../../../hooks/discovery/context-center/useAgentHarnessDraft';
import { useIsAiMode } from '../../../hooks/useAppMode';
import { useFqn } from '../../../hooks/useFqn';
import {
  HarnessTab,
  HarnessTarget,
} from '../../../interface/discovery/agentHarness.interface';
import {
  createPersonaAgentHarness,
  getAgentHarnessGuardrails,
  getAgentHarnessSkills,
  getDefaultAgentHarness,
} from '../../../rest/AgentHarnessAPI';
import contextCenterClassBase from '../../../utils/ContextCenterClassBase';
import {
  enabledEventTokens,
  hasBudgetErrors,
  hasToolAccessErrors,
} from '../../../utils/discovery/context-center/agentHarness.utils';
import { getEntityName } from '../../../utils/EntityNameUtils';
import { showErrorToast } from '../../../utils/ToastUtils';

const isHarnessTab = (value: string | null): value is HarnessTab =>
  HARNESS_TABS.includes(value as HarnessTab);

const versionLabel = (state: AgentHarnessState) =>
  state.published.version === undefined
    ? undefined
    : `v${state.published.version.toFixed(1)}`;

const AgentHarnessEditorPage = () => {
  const { t } = useTranslation();
  const isAiMode = useIsAiMode();
  const pageLayoutClassNames = useContextCenterPageLayout();
  const { isAdminUser } = useAuth();
  const { fqn } = useFqn();
  const [searchParams, setSearchParams] = useSearchParams();
  const [showInherited, setShowInherited] = useState(true);
  const [showPreview, setShowPreview] = useState(true);
  const [isCreating, setIsCreating] = useState(false);
  const [defaultState, setDefaultState] = useState<AgentHarnessState>();
  const [skills, setSkills] = useState<SkillDefinition[]>([]);
  const [guardrails, setGuardrails] = useState<GuardrailDefinition[]>([]);
  const canEdit = Boolean(isAdminUser);

  const target = useMemo<HarnessTarget>(
    () => (fqn ? { kind: 'persona', personaFqn: fqn } : { kind: 'default' }),
    [fqn]
  );
  const draft: AgentHarnessDraft = useAgentHarnessDraft(target);
  const { load, saveState, rows, errors, toolErrors } = draft;
  const isInvalid = hasBudgetErrors(errors) || hasToolAccessErrors(toolErrors);

  useEffect(() => {
    if (!fqn || !canEdit) {
      return;
    }
    getDefaultAgentHarness()
      .then(setDefaultState)
      .catch(() => setDefaultState(undefined));
  }, [fqn, canEdit]);

  useEffect(() => {
    if (!canEdit) {
      return;
    }
    getAgentHarnessSkills()
      .then(setSkills)
      .catch((error) => showErrorToast(error as AxiosError));
    getAgentHarnessGuardrails()
      .then(setGuardrails)
      .catch((error) => showErrorToast(error as AxiosError));
  }, [canEdit]);

  const tabParam = searchParams.get('tab');
  const activeTab = isHarnessTab(tabParam) ? tabParam : HarnessTab.Context;

  const persona =
    load.kind === 'ready' ? load.state.published.persona : undefined;
  const name = fqn
    ? (persona && getEntityName(persona)) || fqn
    : t('label.company-default-harness');

  const handleCreate = async () => {
    setIsCreating(true);
    try {
      await createPersonaAgentHarness(fqn);
      await draft.reload();
    } catch (error) {
      showErrorToast(error as AxiosError);
    } finally {
      setIsCreating(false);
    }
  };

  const handleTabChange = (key: Key) => {
    const next = new URLSearchParams(searchParams);
    next.set('tab', String(key));
    setSearchParams(next, { replace: true });
  };

  const renderHeaderActions = () => {
    if (load.kind !== 'ready' || !canEdit) {
      return undefined;
    }
    const { state } = load;
    const badge = HARNESS_STATUS_BADGE[state.status];
    const hasDraft = Boolean(state.draft) || saveState !== 'saved';

    return (
      <Box align="center" gap={3}>
        <Badge
          color={badge.color}
          data-testid="harness-version"
          size="sm"
          type="pill-color">
          {[versionLabel(state), t(badge.labelKey)].filter(Boolean).join(' · ')}
        </Badge>
        <Button
          color="secondary"
          data-testid="toggle-preview"
          iconLeading={showPreview ? EyeOff : Eye}
          size="sm"
          onPress={() => setShowPreview((shown) => !shown)}>
          {t(showPreview ? 'label.hide-preview' : 'label.preview-context')}
        </Button>
        <Button
          color="primary"
          data-testid="publish-harness"
          isDisabled={!hasDraft || isInvalid}
          isLoading={draft.isPublishing}
          size="sm"
          onPress={draft.publish}>
          {t('label.publish')}
        </Button>
      </Box>
    );
  };

  const renderTabPanel = (tab: HarnessTab) => {
    if (load.kind !== 'ready') {
      return null;
    }
    const { spec, state } = load;
    switch (tab) {
      case HarnessTab.Context:
        return (
          <ContextTab
            canEdit={canEdit}
            harnessId={state.published.id}
            resolved={state.resolved}
            showInherited={target.kind === 'default' || showInherited}
            spec={spec}
            onChange={draft.update}
          />
        );
      case HarnessTab.Delivery:
        return (
          <DeliveryTab
            canEdit={canEdit}
            errors={errors}
            kind={state.published.kind}
            perTurnTokenBudget={spec.perTurnTokenBudget}
            rows={rows}
            onChange={draft.update}
          />
        );
      case HarnessTab.Instructions:
        return (
          <InstructionsTab
            canEdit={canEdit}
            kind={state.published.kind}
            resolved={state.resolved}
            showInherited={showInherited}
            skills={skills}
            spec={spec}
            onChange={draft.update}
          />
        );
      case HarnessTab.Guardrails:
        return (
          <GuardrailsTab
            canEdit={canEdit}
            guardrails={guardrails}
            kind={state.published.kind}
            resolved={state.resolved}
            showInherited={showInherited}
            spec={spec}
            toolAccessErrors={toolErrors}
            onChange={draft.update}
          />
        );
      case HarnessTab.Memory:
        return (
          <MemoryTab
            canEdit={canEdit}
            inheritedMemory={
              defaultState?.resolved.memory ?? state.resolved.memory
            }
            kind={state.published.kind}
            spec={spec}
            onChange={draft.update}
          />
        );
      case HarnessTab.Surfaces:
        return <SurfacesTab />;
    }
  };

  const tabBadge = (tab: HarnessTab) => {
    if (load.kind !== 'ready') {
      return undefined;
    }
    switch (tab) {
      case HarnessTab.Delivery:
        return rows.filter(({ enabled }) => enabled).length;
      case HarnessTab.Guardrails:
        return load.state.resolved.guardrails.filter(
          ({ level }) => level !== GuardrailLevel.Off
        ).length;
      default:
        return undefined;
    }
  };

  const renderEditor = () => {
    if (load.kind !== 'ready') {
      return null;
    }
    const { spec, state } = load;
    const kind = state.published.kind;

    return (
      <Box direction="col" gap={4}>
        {kind === HarnessKind.Persona ? (
          <InheritanceStrip
            defaultVersion={defaultState?.published.version}
            kind="persona"
            personaName={name}
            showInherited={showInherited}
            onShowInheritedChange={setShowInherited}
          />
        ) : (
          <InheritanceStrip kind="default" />
        )}
        <HarnessSettingsBar
          canEdit={canEdit}
          enabled={state.published.enabled ?? true}
          errors={errors}
          eventTokens={enabledEventTokens(rows)}
          saveState={saveState}
          spec={spec}
          onChange={draft.update}
          onEnabledChange={draft.setEnabled}
        />
        <Box align="start" gap={6}>
          <div className="tw:min-w-0 tw:flex-1">
            <Tabs selectedKey={activeTab} onSelectionChange={handleTabChange}>
              <Tabs.List
                aria-label={t('label.agent-harness')}
                size="sm"
                type="underline">
                {HARNESS_TABS.map((tab) => (
                  <Tabs.Item
                    badge={tabBadge(tab)}
                    data-testid={`harness-tab-${tab}`}
                    id={tab}
                    key={tab}>
                    {t(HARNESS_TAB_LABEL_KEYS[tab])}
                  </Tabs.Item>
                ))}
              </Tabs.List>
              {HARNESS_TABS.map((tab) => (
                <Tabs.Panel className="tw:pt-5" id={tab} key={tab}>
                  {renderTabPanel(tab)}
                </Tabs.Panel>
              ))}
            </Tabs>
          </div>
          {showPreview && (
            <CompiledContextRail
              harnessId={state.published.id}
              isValid={!isInvalid}
              perTurnTokenBudget={spec.perTurnTokenBudget}
              spec={spec}
            />
          )}
        </Box>
      </Box>
    );
  };

  const renderContent = () => {
    if (!canEdit) {
      return (
        <div className="tw:relative tw:min-h-80">
          <PermissionPlaceholder permissionValue={t('label.agent-harness')} />
        </div>
      );
    }
    switch (load.kind) {
      case 'loading':
        return <Loader />;
      case 'missing':
        return (
          <div className="tw:relative tw:min-h-80">
            <NoDataPlaceholder
              actions={[
                {
                  color: 'primary',
                  isLoading: isCreating,
                  key: 'create',
                  label: t('label.new-persona-harness'),
                  onPress: handleCreate,
                },
              ]}
              description={t('message.agent-harness-persona-uses-default')}
              title={t('label.uses-default')}
            />
          </div>
        );
      case 'error':
        return null;
      default:
        return renderEditor();
    }
  };

  return (
    <NavigationBlocker
      enabled={saveState !== 'saved'}
      onConfirm={async () => {
        if (!(await draft.flush())) {
          throw new Error('The draft was not saved');
        }
      }}>
      <div
        className={`tw:flex tw:flex-col tw:w-full tw:h-full tw:overflow-hidden ${contextCenterClassBase.getPageContainerClassName(
          isAiMode
        )}`}
        data-testid="agent-harness-editor-page">
        <DocumentTitle title={name} />
        <PageLayout
          className={pageLayoutClassNames.root}
          data-testid="context-center-page-layout">
          <PageLayout.Header className={pageLayoutClassNames.header}>
            <ContextCenterHeader
              actionsSlot={renderHeaderActions()}
              breadcrumbs={[
                {
                  label: t('label.agent-harness'),
                  href: contextCenterClassBase.getAgentHarnessListPath(),
                },
                { label: name },
              ]}
              hasPermission={canEdit}
              subtitle={t(
                fqn
                  ? 'message.agent-harness-persona-subtitle'
                  : 'message.agent-harness-default-subtitle'
              )}
              title={name}
            />
          </PageLayout.Header>
          <PageLayout.Content
            className={classNames(
              'tw:flex tw:flex-col tw:min-h-0 tw:overflow-y-auto',
              pageLayoutClassNames.content
            )}>
            {renderContent()}
          </PageLayout.Content>
        </PageLayout>
      </div>
    </NavigationBlocker>
  );
};

export default AgentHarnessEditorPage;
