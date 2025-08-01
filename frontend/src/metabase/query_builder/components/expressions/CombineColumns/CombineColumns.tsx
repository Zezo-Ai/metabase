import type { FormEventHandler } from "react";
import { useState } from "react";
import { jt, t } from "ttag";

import { isNotNull } from "metabase/lib/types";
import { Box, Button, Flex, Icon, Stack } from "metabase/ui";
import * as Lib from "metabase-lib";

import { ExpressionWidgetHeader } from "../ExpressionWidget/ExpressionWidgetHeader";

import { ColumnAndSeparatorRow } from "./ColumnAndSeparatorRow";
import { Example } from "./Example";
import type { ColumnAndSeparator } from "./util";
import {
  flatten,
  formatSeparator,
  getDefaultSeparator,
  getExpressionName,
  getNextColumnAndSeparator,
} from "./util";

interface Props {
  query: Lib.Query;
  stageIndex: number;
  availableColumns: Lib.ColumnMetadata[];
  onCancel?: () => void;
  onSubmit: (name: string, clause: Lib.ExpressionClause) => void;
  withTitle?: boolean;
  width?: number;

  /**
   * If set, use this as the first column to combine.
   */
  column?: Lib.ColumnMetadata;
}

type State = {
  columnsAndSeparators: ColumnAndSeparator[];
  isUsingDefaultSeparator: boolean;
  defaultSeparator: string;
};

export function CombineColumns({
  query,
  stageIndex,
  availableColumns,
  onCancel,
  onSubmit,
  width,
  column,
  withTitle,
}: Props) {
  const [state, setState] = useState<State>(() => {
    const defaultSeparator = getDefaultSeparator(column);

    const firstColumnAndSeparator = {
      column: column ?? availableColumns[0] ?? null,
      separator: null,
    };

    const secondColumnAndSeparator = getNextColumnAndSeparator(
      availableColumns,
      defaultSeparator,
      [firstColumnAndSeparator],
    );

    return {
      columnsAndSeparators: [firstColumnAndSeparator, secondColumnAndSeparator],
      isUsingDefaultSeparator: true,
      defaultSeparator,
    };
  });

  const { columnsAndSeparators, isUsingDefaultSeparator } = state;

  const handleRowChange = (
    index: number,
    column: Lib.ColumnMetadata | null,
    separator: string,
  ) => {
    setState((state) => {
      const updated = {
        ...state,
        columnsAndSeparators: [
          ...state.columnsAndSeparators.slice(0, index),
          { column, separator },
          ...state.columnsAndSeparators.slice(index + 1),
        ],
      };

      if (index === 0 && state.isUsingDefaultSeparator && column) {
        // rewrite the default separator when the first column is selected
        const defaultSeparator = getDefaultSeparator(column);
        updated.columnsAndSeparators = updated.columnsAndSeparators.map(
          (columnAndSeparator) => ({
            ...columnAndSeparator,
            separator: defaultSeparator,
          }),
        );
        updated.defaultSeparator = defaultSeparator;
      }

      return updated;
    });
  };

  const handleRowRemove = (index: number) => {
    setState((state) => ({
      ...state,
      columnsAndSeparators: [
        ...state.columnsAndSeparators.slice(0, index),
        ...state.columnsAndSeparators.slice(index + 1),
      ],
    }));
  };

  const handleRowAdd = () => {
    setState((state) => {
      return {
        ...state,
        columnsAndSeparators: [
          ...state.columnsAndSeparators,
          getNextColumnAndSeparator(
            availableColumns,
            state.defaultSeparator,
            state.columnsAndSeparators,
          ),
        ],
      };
    });
  };

  const handleEditSeparators = () => {
    setState((state) => ({
      ...state,
      isUsingDefaultSeparator: false,
    }));
  };

  const handleSubmit: FormEventHandler = (event) => {
    event.preventDefault();

    const name = getExpressionName(query, stageIndex, columnsAndSeparators);

    const expression = Lib.expressionClause(
      "concat",
      flatten(columnsAndSeparators),
    );

    onSubmit(name, expression);
  };

  const isValid = state.columnsAndSeparators.every(({ column }) =>
    isNotNull(column),
  );

  return (
    <>
      {onCancel && withTitle && (
        <ExpressionWidgetHeader
          title={t`Select columns to combine`}
          onBack={onCancel}
        />
      )}
      <form onSubmit={handleSubmit}>
        <Box maw="100vw" w={width} p="lg" pt={0}>
          <Stack gap="lg" mt="lg">
            <Stack gap="md">
              <Box>
                <Stack gap="md">
                  {columnsAndSeparators.map(
                    (item, index) =>
                      // Do not allow editing the first column when it is passed from
                      // the props.
                      (!column || index > 0) && (
                        <ColumnAndSeparatorRow
                          key={index}
                          query={query}
                          stageIndex={stageIndex}
                          index={index}
                          columns={availableColumns}
                          column={item.column}
                          separator={item.separator ?? ""}
                          showSeparator={
                            !isUsingDefaultSeparator && index !== 0
                          }
                          showRemove={columnsAndSeparators.length >= 3}
                          onChange={handleRowChange}
                          onRemove={handleRowRemove}
                        />
                      ),
                  )}
                </Stack>
              </Box>
              <Flex
                align="center"
                gap="md"
                justify={isUsingDefaultSeparator ? "space-between" : "end"}
              >
                {isUsingDefaultSeparator && (
                  <Box>
                    <Button
                      p={0}
                      variant="subtle"
                      onClick={handleEditSeparators}
                    >
                      {jt`Separated by ${formatSeparator(
                        state.defaultSeparator,
                      )}`}
                    </Button>
                  </Box>
                )}

                <Button
                  leftSection={<Icon name="add" />}
                  p={0}
                  variant="subtle"
                  onClick={handleRowAdd}
                >
                  {t`Add column`}
                </Button>
              </Flex>
            </Stack>

            <Example columnsAndSeparators={columnsAndSeparators} />

            <Flex align="center" gap="md" justify="end">
              <Button type="submit" variant="filled" disabled={!isValid}>
                {t`Done`}
              </Button>
            </Flex>
          </Stack>
        </Box>
      </form>
    </>
  );
}
