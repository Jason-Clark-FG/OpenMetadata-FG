/*
 *  Copyright 2022 Collate.
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

import { PlusOutlined } from '@ant-design/icons';
import { Button, Col, Form, Input, Row } from 'antd';
import { AxiosError } from 'axios';
import { t } from 'i18next';
import { isUndefined, map, omit, omitBy, startCase } from 'lodash';
import React, {
  FocusEvent,
  useCallback,
  useEffect,
  useMemo,
  useState,
} from 'react';
import { useHistory, useParams } from 'react-router-dom';
import { ReactComponent as DeleteIcon } from '../../../../assets/svg/ic-delete.svg';
import {
  ENTITY_REFERENCE_OPTIONS,
  ENUM_WITH_DESCRIPTION,
  PROPERTY_TYPES_WITH_ENTITY_REFERENCE,
  PROPERTY_TYPES_WITH_FORMAT,
  SUPPORTED_FORMAT_MAP,
} from '../../../../constants/CustomProperty.constants';
import { GlobalSettingsMenuCategory } from '../../../../constants/GlobalSettings.constants';
import { CUSTOM_PROPERTY_NAME_REGEX } from '../../../../constants/regex.constants';
import {
  CUSTOM_PROPERTY_CATEGORY,
  OPEN_METADATA,
} from '../../../../constants/service-guide.constant';
import { EntityType } from '../../../../enums/entity.enum';
import { ServiceCategory } from '../../../../enums/service.enum';
import { Category, Type } from '../../../../generated/entity/type';
import { EnumWithDescriptionsConfig } from '../../../../generated/type/customProperties/enumWithDescriptionsConfig';
import { CustomProperty } from '../../../../generated/type/customProperty';
import {
  FieldProp,
  FieldTypes,
  FormItemLayout,
} from '../../../../interface/FormUtils.interface';
import {
  addPropertyToEntity,
  getTypeByFQN,
  getTypeListByCategory,
} from '../../../../rest/metadataTypeAPI';
import { generateFormFields } from '../../../../utils/formUtils';
import { getSettingOptionByEntityType } from '../../../../utils/GlobalSettingsUtils';
import { getSettingPath } from '../../../../utils/RouterUtils';
import { showErrorToast } from '../../../../utils/ToastUtils';
import ResizablePanels from '../../../common/ResizablePanels/ResizablePanels';
import RichTextEditor from '../../../common/RichTextEditor/RichTextEditor';
import ServiceDocPanel from '../../../common/ServiceDocPanel/ServiceDocPanel';
import TitleBreadcrumb from '../../../common/TitleBreadcrumb/TitleBreadcrumb.component';

const AddCustomProperty = () => {
  const [form] = Form.useForm();
  const { entityType } = useParams<{ entityType: EntityType }>();
  const history = useHistory();

  const [typeDetail, setTypeDetail] = useState<Type>();

  const [propertyTypes, setPropertyTypes] = useState<Array<Type>>([]);
  const [activeField, setActiveField] = useState<string>('');
  const [isCreating, setIsCreating] = useState<boolean>(false);

  const watchedPropertyType = Form.useWatch('propertyType', form);

  const slashedBreadcrumb = useMemo(
    () => [
      {
        name: t('label.setting-plural'),
        url: getSettingPath(),
      },
      {
        name: t('label.custom-attribute-plural'),
        url: getSettingPath(
          GlobalSettingsMenuCategory.CUSTOM_PROPERTIES,
          getSettingOptionByEntityType(entityType)
        ),
      },
      {
        name: t('label.add-entity', {
          entity: t('label.custom-property'),
        }),
        url: '',
      },
    ],
    [entityType]
  );

  const propertyTypeOptions = useMemo(() => {
    return map(propertyTypes, (type) => ({
      key: type.name,
      // Remove -cp from the name and convert to start case
      label: startCase((type.displayName ?? type.name).replace(/-cp/g, '')),
      value: type.id,
    }));
  }, [propertyTypes]);

  const {
    hasEnumConfig,
    hasFormatConfig,
    hasEntityReferenceConfig,
    watchedOption,
    hasEnumWithDescriptionConfig,
  } = useMemo(() => {
    const watchedOption = propertyTypeOptions.find(
      (option) => option.value === watchedPropertyType
    );
    const watchedOptionKey = watchedOption?.key ?? '';

    const hasEnumConfig = watchedOptionKey === 'enum';

    const hasEnumWithDescriptionConfig =
      watchedOptionKey === ENUM_WITH_DESCRIPTION;

    const hasFormatConfig =
      PROPERTY_TYPES_WITH_FORMAT.includes(watchedOptionKey);

    const hasEntityReferenceConfig =
      PROPERTY_TYPES_WITH_ENTITY_REFERENCE.includes(watchedOptionKey);

    return {
      hasEnumConfig,
      hasFormatConfig,
      hasEntityReferenceConfig,
      watchedOption,
      hasEnumWithDescriptionConfig,
    };
  }, [watchedPropertyType, propertyTypeOptions]);

  const fetchPropertyType = async () => {
    try {
      const response = await getTypeListByCategory(Category.Field);
      setPropertyTypes(response.data);
    } catch (error) {
      showErrorToast(error as AxiosError);
    }
  };

  const fetchTypeDetail = async (typeFQN: string) => {
    try {
      const response = await getTypeByFQN(typeFQN);
      setTypeDetail(response);
    } catch (error) {
      showErrorToast(error as AxiosError);
    }
  };

  const handleCancel = useCallback(() => history.goBack(), [history]);

  const handleFieldFocus = useCallback((event: FocusEvent<HTMLFormElement>) => {
    const isDescription = event.target.classList.contains('ProseMirror');

    setActiveField(isDescription ? 'root/description' : event.target.id);
  }, []);

  const handleSubmit = async (
    /**
     * In CustomProperty the propertyType is type of entity reference, however from the form we
     * get propertyType as string
     */
    data: Exclude<CustomProperty, 'propertyType'> & {
      propertyType: string;
      enumConfig: string[];
      formatConfig: string;
      entityReferenceConfig: string[];
      multiSelect?: boolean;
      enumWithDescriptionsConfig?: EnumWithDescriptionsConfig['values'];
    }
  ) => {
    if (isUndefined(typeDetail)) {
      return;
    }

    try {
      setIsCreating(true);
      let customPropertyConfig;

      if (hasEnumConfig) {
        customPropertyConfig = {
          config: {
            multiSelect: Boolean(data?.multiSelect),
            values: data.enumConfig,
          },
        };
      }

      if (hasFormatConfig) {
        customPropertyConfig = {
          config: data.formatConfig,
        };
      }

      if (hasEntityReferenceConfig) {
        customPropertyConfig = {
          config: data.entityReferenceConfig,
        };
      }

      if (hasEnumWithDescriptionConfig) {
        customPropertyConfig = {
          config: {
            multiSelect: Boolean(data?.multiSelect),
            values: data.enumWithDescriptionsConfig,
          },
        };
      }

      const payload = omitBy(
        {
          ...omit(data, [
            'multiSelect',
            'formatConfig',
            'entityReferenceConfig',
            'enumConfig',
            'enumWithDescriptionsConfig',
          ]),
          propertyType: {
            id: data.propertyType,
            type: 'type',
          },
          customPropertyConfig,
        },
        isUndefined
      ) as unknown as CustomProperty;

      await addPropertyToEntity(typeDetail?.id ?? '', payload);
      history.goBack();
    } catch (error) {
      showErrorToast(error as AxiosError);
    } finally {
      setIsCreating(false);
    }
  };

  useEffect(() => {
    fetchTypeDetail(entityType);
  }, [entityType]);

  useEffect(() => {
    fetchPropertyType();
  }, []);

  const formFields: FieldProp[] = [
    {
      name: 'name',
      required: true,
      label: t('label.name'),
      id: 'root/name',
      type: FieldTypes.TEXT,
      props: {
        'data-testid': 'name',
        autoComplete: 'off',
      },
      placeholder: t('label.name'),
      rules: [
        {
          pattern: CUSTOM_PROPERTY_NAME_REGEX,
          message: t('message.custom-property-name-validation'),
        },
      ],
    },
    {
      name: 'propertyType',
      required: true,
      label: t('label.type'),
      id: 'root/propertyType',
      type: FieldTypes.SELECT,
      props: {
        'data-testid': 'propertyType',
        options: propertyTypeOptions,
        placeholder: `${t('label.select-field', {
          field: t('label.type'),
        })}`,
        showSearch: true,
        filterOption: (input: string, option: { label: string }) => {
          return (option?.label ?? '')
            .toLowerCase()
            .includes(input.toLowerCase());
        },
      },
    },
  ];

  const descriptionField: FieldProp = {
    name: 'description',
    required: true,
    label: t('label.description'),
    id: 'root/description',
    type: FieldTypes.DESCRIPTION,
    props: {
      'data-testid': 'description',
      initialValue: '',
    },
  };

  const enumConfigField: FieldProp = {
    name: 'enumConfig',
    required: false,
    label: t('label.enum-value-plural'),
    id: 'root/enumConfig',
    type: FieldTypes.SELECT,
    props: {
      'data-testid': 'enumConfig',
      mode: 'tags',
      placeholder: t('label.enum-value-plural'),
    },
    rules: [
      {
        required: true,
        message: t('label.field-required', {
          field: t('label.enum-value-plural'),
        }),
      },
    ],
  };

  const multiSelectField: FieldProp = {
    name: 'multiSelect',
    label: t('label.multi-select'),
    type: FieldTypes.SWITCH,
    required: false,
    props: {
      'data-testid': 'multiSelect',
    },
    id: 'root/multiSelect',
    formItemLayout: FormItemLayout.HORIZONTAL,
  };

  const formatConfigField: FieldProp = {
    name: 'formatConfig',
    required: false,
    label: t('label.format'),
    id: 'root/formatConfig',
    type: FieldTypes.TEXT,
    props: {
      'data-testid': 'formatConfig',
      autoComplete: 'off',
    },
    placeholder: t('label.format'),
    rules: [
      {
        validator: (_, value) => {
          const propertyName = watchedOption?.key ?? '';
          const supportedFormats =
            SUPPORTED_FORMAT_MAP[
              propertyName as keyof typeof SUPPORTED_FORMAT_MAP
            ];

          if (!supportedFormats.includes(value)) {
            return Promise.reject(
              t('label.field-invalid', {
                field: t('label.format'),
              })
            );
          }

          return Promise.resolve();
        },
      },
    ],
  };

  const entityReferenceConfigField: FieldProp = {
    name: 'entityReferenceConfig',
    required: true,
    label: t('label.entity-reference-types'),
    id: 'root/entityReferenceConfig',
    type: FieldTypes.SELECT,
    props: {
      mode: 'multiple',
      options: ENTITY_REFERENCE_OPTIONS,
      'data-testid': 'entityReferenceConfig',
      placeholder: `${t('label.select-field', {
        field: t('label.type'),
      })}`,
    },
  };

  const firstPanelChildren = (
    <div className="max-width-md w-9/10 service-form-container">
      <TitleBreadcrumb titleLinks={slashedBreadcrumb} />
      <Form
        className="m-t-md"
        data-testid="custom-property-form"
        form={form}
        layout="vertical"
        onFinish={handleSubmit}
        onFocus={handleFieldFocus}>
        {generateFormFields(formFields)}
        {
          // Only show enum value field if the property type has enum config
          hasEnumConfig &&
            generateFormFields([enumConfigField, multiSelectField])
        }
        {
          // Only show format field if the property type has format config
          hasFormatConfig && generateFormFields([formatConfigField])
        }

        {
          // Only show entity reference field if the property type has entity reference config
          hasEntityReferenceConfig &&
            generateFormFields([entityReferenceConfigField])
        }

        {hasEnumWithDescriptionConfig && (
          <>
            <Form.List name="enumWithDescriptionsConfig">
              {(fields, { add, remove }) => (
                <>
                  <Form.Item
                    className="form-item-horizontal"
                    colon={false}
                    label={t('label.property')}>
                    <Button
                      data-testid="add-enum-description-config"
                      icon={
                        <PlusOutlined
                          style={{ color: 'white', fontSize: '12px' }}
                        />
                      }
                      size="small"
                      type="primary"
                      onClick={() => {
                        add();
                      }}
                    />
                  </Form.Item>

                  {fields.map((field, index) => (
                    <Row gutter={[8, 0]} key={field.key}>
                      <Col span={23}>
                        <Row gutter={[8, 0]}>
                          <Col span={24}>
                            <Form.Item
                              name={[field.name, 'key']}
                              rules={[
                                {
                                  required: true,
                                  message: `${t(
                                    'message.field-text-is-required',
                                    {
                                      fieldText: t('label.key'),
                                    }
                                  )}`,
                                },
                              ]}>
                              <Input
                                id={`key-${index}`}
                                placeholder={t('label.key')}
                              />
                            </Form.Item>
                          </Col>
                          <Col span={24}>
                            <Form.Item
                              name={[field.name, 'description']}
                              rules={[
                                {
                                  required: true,
                                  message: `${t(
                                    'message.field-text-is-required',
                                    {
                                      fieldText: t('label.description'),
                                    }
                                  )}`,
                                },
                              ]}
                              trigger="onTextChange"
                              valuePropName="initialValue">
                              <RichTextEditor height="200px" />
                            </Form.Item>
                          </Col>
                        </Row>
                      </Col>
                      <Col span={1}>
                        <Button
                          data-testid={`remove-enum-description-config-${index}`}
                          icon={<DeleteIcon width={16} />}
                          size="small"
                          type="text"
                          onClick={() => {
                            remove(field.name);
                          }}
                        />
                      </Col>
                    </Row>
                  ))}
                </>
              )}
            </Form.List>
            {generateFormFields([multiSelectField])}
          </>
        )}
        {generateFormFields([descriptionField])}
        <Row justify="end">
          <Col>
            <Button
              data-testid="back-button"
              type="link"
              onClick={handleCancel}>
              {t('label.back')}
            </Button>
          </Col>
          <Col>
            <Button
              data-testid="create-button"
              htmlType="submit"
              loading={isCreating}
              type="primary">
              {t('label.create')}
            </Button>
          </Col>
        </Row>
      </Form>
    </div>
  );

  const secondPanelChildren = (
    <ServiceDocPanel
      activeField={activeField}
      serviceName={CUSTOM_PROPERTY_CATEGORY}
      serviceType={OPEN_METADATA as ServiceCategory}
    />
  );

  return (
    <ResizablePanels
      className="content-height-with-resizable-panel"
      firstPanel={{
        className: 'content-resizable-panel-container',
        children: firstPanelChildren,
        minWidth: 700,
        flex: 0.7,
      }}
      pageTitle={t('label.add-entity', {
        entity: t('label.custom-property'),
      })}
      secondPanel={{
        children: secondPanelChildren,
        className: 'service-doc-panel content-resizable-panel-container',
        minWidth: 400,
        flex: 0.3,
      }}
    />
  );
};

export default AddCustomProperty;
