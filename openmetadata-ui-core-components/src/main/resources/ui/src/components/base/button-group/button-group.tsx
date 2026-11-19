import { cx, sortCx } from '@/utils/cx';
import { isReactComponent } from '@/utils/is-react-component';
import { borderAfter } from '@/utils/tailwindClasses';
import {
  type FC,
  type PropsWithChildren,
  type ReactNode,
  type RefAttributes,
  createContext,
  isValidElement,
  useContext,
  useMemo,
} from 'react';
import {
  ToggleButton as AriaToggleButton,
  ToggleButtonGroup as AriaToggleButtonGroup,
  type ToggleButtonGroupProps,
  type ToggleButtonProps,
} from 'react-aria-components';

export const styles = sortCx({
  common: {
    root: [
      // `tw:relative` anchors the ::after border overlay (the element's own outline is
      // reserved for the focus ring). No absolutely-positioned children, so it is inert.
      'tw:group/button-group tw:relative tw:inline-flex tw:h-max tw:cursor-pointer tw:items-center tw:bg-primary tw:font-semibold tw:whitespace-nowrap tw:text-secondary tw:shadow-skeuomorphic tw:outline-brand tw:transition tw:duration-100 tw:ease-linear',
      `${borderAfter} tw:after:outline-primary`,
      // Hover and focus styles
      'tw:hover:bg-primary_hover tw:hover:text-secondary_hover tw:focus-visible:z-10 tw:focus-visible:outline-2 tw:focus-visible:outline-offset-2',
      // Disabled styles
      'tw:disabled:cursor-not-allowed tw:disabled:bg-primary tw:disabled:text-disabled',
      // Selected styles
      'tw:selected:bg-active tw:selected:text-secondary_hover tw:selected:disabled:bg-disabled_subtle',
    ].join(' '),
    icon: 'tw:pointer-events-none tw:text-utility-gray-500 tw:transition-[inherit] tw:group-hover/button-group:text-utility-gray-600 tw:group-selected/button-group:text-current tw:group-disabled/button-group:text-fg-disabled_subtle',
  },

  sizes: {
    sm: {
      root: 'tw:gap-1.5 tw:px-3.5 tw:py-2 tw:text-sm tw:not-last:pr-[calc(calc(theme(--spacing)*3.5)+1px)] tw:first:rounded-l-lg tw:last:rounded-r-lg tw:data-icon-leading:pl-3 tw:data-icon-only:p-2',
      icon: 'tw:size-4',
    },
    md: {
      root: 'tw:gap-1.5 tw:px-4 tw:py-2.5 tw:text-sm tw:not-last:pr-[calc(calc(theme(--spacing)*4)+1px)] tw:first:rounded-l-lg tw:last:rounded-r-lg tw:data-icon-leading:pl-3.5 tw:data-icon-only:px-3',
      icon: 'tw:size-5',
    },
    lg: {
      root: 'tw:gap-2 tw:px-4.5 tw:py-2.5 tw:text-md tw:not-last:pr-[calc(calc(theme(--spacing)*4.5)+1px)] tw:first:rounded-l-lg tw:last:rounded-r-lg tw:data-icon-leading:pl-4 tw:data-icon-only:p-3',
      icon: 'tw:size-5',
    },
  },
});

// `segmented`: the items sit on a grey track and the selected one is a raised white pill.
// `pill`: separate rounded chips; the selected one takes the brand tint.
const variantStyles = sortCx({
  segmented: {
    group:
      'tw:inline-flex tw:w-max tw:gap-0.5 tw:rounded-lg tw:bg-secondary_alt tw:p-0.5 tw:outline-1 tw:-outline-offset-1 tw:outline-secondary',
    root: [
      'tw:group/button-group tw:relative tw:inline-flex tw:h-max tw:cursor-pointer tw:items-center tw:rounded-md tw:font-semibold tw:whitespace-nowrap tw:text-tertiary tw:outline-brand tw:transition tw:duration-100 tw:ease-linear',
      'tw:hover:text-secondary tw:focus-visible:z-10 tw:focus-visible:outline-2 tw:focus-visible:outline-offset-2',
      'tw:selected:bg-primary_alt tw:selected:text-primary tw:selected:shadow-xs',
      'tw:disabled:cursor-not-allowed tw:disabled:opacity-40',
    ].join(' '),
    sizes: {
      sm: 'tw:gap-1.5 tw:px-3 tw:py-1 tw:text-sm',
      md: 'tw:gap-1.5 tw:px-3.5 tw:py-1.5 tw:text-sm',
      lg: 'tw:gap-2 tw:px-4 tw:py-2 tw:text-md',
    },
  },
  pill: {
    group: 'tw:inline-flex tw:flex-wrap tw:gap-1',
    root: [
      'tw:group/button-group tw:relative tw:inline-flex tw:h-max tw:cursor-pointer tw:items-center tw:rounded-full tw:bg-primary tw:font-semibold tw:whitespace-nowrap tw:text-tertiary tw:outline-brand tw:transition tw:duration-100 tw:ease-linear',
      `${borderAfter} tw:after:outline-secondary`,
      'tw:hover:text-secondary tw:focus-visible:outline-2 tw:focus-visible:outline-offset-2',
      'tw:selected:bg-brand-primary tw:selected:text-brand-secondary tw:selected:after:outline-utility-brand-200',
      'tw:disabled:cursor-not-allowed tw:disabled:opacity-40',
    ].join(' '),
    sizes: {
      sm: 'tw:gap-1 tw:px-2.5 tw:py-1 tw:text-xs',
      md: 'tw:gap-1.5 tw:px-3 tw:py-1 tw:text-sm',
      lg: 'tw:gap-1.5 tw:px-3.5 tw:py-1.5 tw:text-sm',
    },
  },
});

type ButtonSize = keyof typeof styles.sizes;

export type ButtonGroupVariant = 'default' | keyof typeof variantStyles;

const ButtonGroupContext = createContext<{
  size: ButtonSize;
  variant: ButtonGroupVariant;
}>({ size: 'md', variant: 'default' });

interface ButtonGroupItemProps
  extends ToggleButtonProps,
    RefAttributes<HTMLButtonElement> {
  iconLeading?: FC<{ className?: string }> | ReactNode;
  iconTrailing?: FC<{ className?: string }> | ReactNode;
  onClick?: () => void;
  className?: string;
}

export const ButtonGroupItem = ({
  iconLeading: IconLeading,
  iconTrailing: IconTrailing,
  children,
  className,
  ...otherProps
}: PropsWithChildren<ButtonGroupItemProps>) => {
  const context = useContext(ButtonGroupContext);

  if (!context) {
    throw new Error(
      'ButtonGroupItem must be used within a ButtonGroup component'
    );
  }

  const { size, variant } = context;

  const isIcon = (IconLeading || IconTrailing) && !children;
  const rootClassName =
    variant === 'default'
      ? cx(styles.common.root, styles.sizes[size].root)
      : cx(variantStyles[variant].root, variantStyles[variant].sizes[size]);

  return (
    <AriaToggleButton
      {...otherProps}
      className={cx(rootClassName, className)}
      data-icon-leading={IconLeading ? true : undefined}
      data-icon-only={isIcon ? true : undefined}>
      {isReactComponent(IconLeading) && (
        <IconLeading
          className={cx(styles.common.icon, styles.sizes[size].icon)}
        />
      )}
      {isValidElement(IconLeading) && IconLeading}

      {children}

      {isReactComponent(IconTrailing) && (
        <IconTrailing
          className={cx(styles.common.icon, styles.sizes[size].icon)}
        />
      )}
      {isValidElement(IconTrailing) && IconTrailing}
    </AriaToggleButton>
  );
};

interface ButtonGroupProps
  extends Omit<ToggleButtonGroupProps, 'orientation'>,
    RefAttributes<HTMLDivElement> {
  size?: ButtonSize;
  variant?: ButtonGroupVariant;
  className?: string;
}

export const ButtonGroup = ({
  children,
  size = 'md',
  variant = 'default',
  className,
  ...otherProps
}: ButtonGroupProps) => {
  const contextValue = useMemo(() => ({ size, variant }), [size, variant]);

  return (
    <ButtonGroupContext.Provider value={contextValue}>
      <AriaToggleButtonGroup
        className={cx(
          variant === 'default'
            ? 'tw:relative tw:z-0 tw:inline-flex tw:w-max tw:-space-x-px tw:rounded-lg tw:shadow-xs'
            : variantStyles[variant].group,
          className
        )}
        selectionMode="single"
        {...otherProps}>
        {children}
      </AriaToggleButtonGroup>
    </ButtonGroupContext.Provider>
  );
};
