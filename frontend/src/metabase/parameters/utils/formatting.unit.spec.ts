import { createMockMetadata } from "__support__/metadata";
import { checkNotNull } from "metabase/lib/types";
import { createMockUiParameter } from "metabase-lib/v1/parameters/mock";
import {
  createMockField,
  createMockFieldDimension,
} from "metabase-types/api/mocks";
import {
  ORDERS,
  PRODUCTS,
  createSampleDatabase,
} from "metabase-types/api/mocks/presets";

import { formatParameterValue } from "./formatting";

const REMAPPED_FIELD_ID = 100;

const metadata = createMockMetadata({
  databases: [createSampleDatabase()],
  fields: [
    createMockField({
      id: REMAPPED_FIELD_ID,
      base_type: "type/Integer",
      remappings: [[123456789, "A"]],
      dimensions: [
        createMockFieldDimension({
          type: "internal",
        }),
      ],
    }),
  ],
});

const numberField = checkNotNull(metadata.field(ORDERS.TOTAL));
const textField = checkNotNull(metadata.field(PRODUCTS.TITLE));
const categoryField = checkNotNull(metadata.field(PRODUCTS.CATEGORY));
const remappedField = checkNotNull(metadata.field(REMAPPED_FIELD_ID));

describe("metabase/parameters/utils/formatting", () => {
  describe("formatParameterValue", () => {
    const cases = [
      {
        type: "date/single",
        value: "2018-01-01",
        expected: "January 1, 2018",
      },
      {
        type: "date/single",
        value: "2018-01-01T12:30:00",
        expected: "January 1, 2018 12:30 PM",
      },
      {
        type: "date/range",
        value: "1995-01-01~1995-01-10",
        expected: "January 1, 1995 - January 10, 1995",
      },
      {
        type: "date/range",
        value: "2018-01-01T12:30:00~2018-01-10",
        expected: "January 1, 2018 12:30 PM - January 10, 2018 12:00 AM",
      },
      {
        type: "date/range",
        value: "2018-01-01~2018-01-10T08:15:00",
        expected: "January 1, 2018 12:00 AM - January 10, 2018 08:15 AM",
      },
      {
        type: "date/range",
        value: "2018-01-01T12:30:00~2018-01-10T08:15:00",
        expected: "January 1, 2018 12:30 PM - January 10, 2018 08:15 AM",
      },
      {
        type: "date/all-options",
        value: "2018-01-01",
        expected: "On January 1, 2018",
      },
      {
        type: "date/month-year",
        value: "2018-01",
        expected: "January 2018",
      },
      {
        type: "date/quarter-year",
        value: "Q1-2018",
        expected: "Q1 2018",
      },
      {
        type: "date/relative",
        value: "past30days",
        expected: "Previous 30 days",
      },
      {
        type: "date/month-year",
        value: "thisday",
        expected: "Today",
      },
      {
        type: "date/month-year",
        value: "thisweek",
        expected: "This week",
      },
      {
        type: "date/month-year",
        value: "past1days",
        expected: "Yesterday",
      },
      {
        type: "date/month-year",
        value: "past1weeks",
        expected: "Previous week",
      },
      {
        type: "date/month-year",
        value: "2023-10-02~2023-10-24",
        expected: "October 2, 2023 - October 24, 2023",
      },
      {
        type: "number/=",
        value: 123456789,
        expected: "123,456,789",
        fields: [numberField],
      },
      {
        type: "number/>=",
        value: 1.111111111111,
        expected: "1.111111111111",
        fields: [],
      },
      {
        type: "number/>=",
        value: 1.111111111111,
        expected: "1.111111111111",
        fields: [],
        hasVariableTemplateTagTarget: true,
      },
      {
        type: "string/=",
        value: "abc",
        expected: "abc",
        fields: [textField],
      },
      {
        type: "category",
        value: "foo",
        expected: "foo",
        fields: [categoryField],
      },
      {
        type: "location/city",
        value: "foo",
        expected: "foo",
        fields: [],
      },
      {
        type: "number/=",
        value: [1, 2, 3, 4, 5],
        expected: "5 selections",
        fields: [numberField],
      },
      {
        type: "number/=",
        value: [1],
        expected: "1",
        fields: [numberField],
      },
    ];

    test.each(cases)(
      "should format $type parameter",
      ({ value, expected, ...parameterProps }) => {
        const parameter = createMockUiParameter(parameterProps);
        expect(formatParameterValue(value, parameter)).toEqual(expected);
      },
    );

    it("should not remap a field filter parameter connected to more than one field", () => {
      const parameter = createMockUiParameter({
        type: "number/=",
        fields: [remappedField, numberField],
      });
      expect(formatParameterValue(123456789, parameter)).toEqual("123,456,789");
    });

    it("should remap a field filter parameter value with a target field that is remapped", () => {
      const parameter = createMockUiParameter({
        type: "number/=",
        fields: [remappedField],
      });
      expect(formatParameterValue(123456789, parameter)).toEqual("A");
    });
  });
});
