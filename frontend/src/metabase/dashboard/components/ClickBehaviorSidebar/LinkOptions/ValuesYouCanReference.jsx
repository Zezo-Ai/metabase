import { t } from "ttag";

import { AccordionList } from "metabase/common/components/AccordionList";
import PopoverWithTrigger from "metabase/common/components/PopoverWithTrigger";
import CS from "metabase/css/core/index.css";
import {
  isMappableColumn,
  withUserAttributes,
} from "metabase/dashboard/components/ClickMappings";
import { Flex, Icon } from "metabase/ui";

import S from "./ValuesYouCanReference.module.css";

function prefixIfNeeded(values, prefix, otherLists) {
  const otherValues = otherLists.flat().map((s) => s.toLowerCase());
  return values.map((value) =>
    otherValues.includes(value.toLowerCase()) ? `${prefix}:${value}` : value,
  );
}

export const ValuesYouCanReference = withUserAttributes(
  ({ dashcard, parameters, userAttributes }) => {
    const columnMetadata = dashcard.card.result_metadata || [];
    const columns = columnMetadata?.filter(isMappableColumn).map((c) => c.name);
    const parameterNames = parameters.map((p) => p.name);
    const sections = [
      {
        items: prefixIfNeeded(columns, "column", [
          parameterNames,
          userAttributes,
        ]),
        name: t`Columns`,
      },
      {
        items: prefixIfNeeded(parameterNames, "filter", [
          columns,
          userAttributes,
        ]),
        name: t`Dashboard filters`,
      },
      {
        items: prefixIfNeeded(userAttributes, "user", [
          parameterNames,
          columns,
        ]),
        name: t`User attributes`,
      },
    ].filter((section) => section.items.length > 0);

    if (!sections.length) {
      return null;
    }

    return (
      <PopoverWithTrigger
        triggerElement={
          <Flex align="center" my="md" className={S.PopoverTrigger}>
            <h4>{t`Values you can reference`}</h4>
            <Icon name="chevrondown" className={CS.ml1} size={12} />
          </Flex>
        }
      >
        <AccordionList
          alwaysExpanded
          sections={sections}
          renderItemName={(name) => name}
          itemIsClickable={() => false}
        />
      </PopoverWithTrigger>
    );
  },
);
