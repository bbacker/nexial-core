/*
 * Copyright 2012-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.nexial.core.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import javax.validation.constraints.NotNull;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.nexial.commons.utils.RegexUtils;
import org.nexial.commons.utils.TextUtils;
import org.nexial.core.utils.CheckUtils;
import org.nexial.core.utils.ConsoleUtils;
import org.nexial.core.variable.CsvTransformer;

import static org.nexial.core.NexialConst.Data.NULL;
import static org.nexial.core.NexialConst.FlowControls.*;
import static org.nexial.core.model.NexialFilterComparator.Any;

/**
 * specification of what a filter looks like and does within the context of automation.  Filter is currently
 * used in {@link FlowControl} for conditional flow control, and in {@link CsvTransformer} as a way to filter out
 * portions of CSV content.
 */
public class NexialFilter implements Serializable {
    static final String ITEM_SEP = "|";

    private String subject;
    private NexialFilterComparator comparator;
    private String controls;
    private List<String> controlList;

    public NexialFilter(String subject, NexialFilterComparator comparator, String controls) {
        this.subject = subject;
        this.comparator = comparator;
        this.controls = controls;
    }

    public NexialFilter(String subject, NexialFilterComparator operator, Pair<String, List<String>> controlValues) {
        this.subject = subject;
        this.comparator = operator;
        this.controls = controlValues.getKey();
        this.controlList = controlValues.getValue();
    }

    public static NexialFilter newInstance(String filterText) throws IllegalArgumentException {
        String errPrefix = "invalid filter '" + filterText + "': ";

        if (StringUtils.isBlank(filterText)) { throw new IllegalArgumentException(errPrefix + "null"); }

        String regexFilter = NexialFilterComparator.getRegexFilter();

        // general pattern: [subject] [comparator] [control]
        if (!RegexUtils.isExact(filterText, regexFilter, true)) {
            throw new IllegalArgumentException(errPrefix + "does not match required format");
        }

        List<String> parts = RegexUtils.collectGroups(filterText, regexFilter, false, true);
        if (CollectionUtils.size(parts) != 3) {
            throw new IllegalArgumentException(errPrefix + "does not contain the required 3 parts of a valid filter");
        }

        String subject = StringUtils.trim(parts.get(0));
        if (StringUtils.isBlank(subject)) { throw new IllegalArgumentException(errPrefix + "empty/blank subject"); }

        NexialFilterComparator operator = NexialFilterComparator.findComparator(parts.get(1));
        Pair<String, List<String>> controlValues = operator.formatControlValues(StringUtils.trim(parts.get(2)));
        return new NexialFilter(subject, operator, controlValues);
    }

    public String getSubject() { return subject; }

    public boolean isAnySubject() { return StringUtils.equals(subject, ANY_FIELD); }

    public NexialFilterComparator getComparator() { return comparator; }

    public String getControls() { return controls; }

    public List<String> getControlList() { return controlList; }

    public boolean isMatch(ExecutionContext context, String msgPrefix) {

        String actual = context.replaceTokens(subject);

        // for Is operator, we will defer the double-quote-wrap until isAtLeastOneMatched()
        String expected = context.replaceTokens(controls);
        ConsoleUtils.log(msgPrefix + "(" + actual + comparator.getSymbol() + expected + ")");

        switch (comparator) {
            // always good
            case Any:
                return true;

            case Equal:
            case Equal_2:
                return isEqualsTextOrNumeric(actual, expected);

            case NotEqual:
            case NotEqual_2:
                return !isEqualsTextOrNumeric(actual, expected);

            case Greater:
            case Greater_2:
            case GreaterOrEqual:
            case GreaterOrEqual_2:
            case Lesser:
            case Lesser_2:
            case LesserOrEqual:
            case LesserOrEqual_2:
                return isNumericMatch(actual, context, msgPrefix);

            case In:
            case Is:
                return isAtLeastOneMatched(actual, context);

            case IsNot:
            case NotIn:
                return isNoneMatched(actual, context);

            case Between:
                return isInRange(actual, context, msgPrefix);

            case StartsWith:
            case EndsWith:
            case Contain:
                return isMatchingStringCompare(actual, context);

            case Match:
                return isMatchByRegex(actual, context);

            case HasLengthOf:
                return isNumericMatch(StringUtils.length(actual) + "", context, msgPrefix);

            case IsEmpty:
                return StringUtils.isEmpty(actual);
            case IsNotEmpty:
                return StringUtils.isNotEmpty(actual);

            case IsDefined:
                return context.hasData(subject);
            case IsUndefined:
                return !context.hasData(subject);

            default: {
                ConsoleUtils.error("Unsupported comparison: " + comparator.getSymbol());
                return false;
            }
        }
    }

    /**
     * used for `CSV Expression` fetch, filter and removeRows operations
     */
    public boolean isMatch(String data) {
        if (comparator == Any) { return true; }

        data = data == null ? NULL : normalizeCondition(data);

        switch (comparator) {
            case Equal:
            case Equal_2:
                return isEqualsTextOrNumeric(data, normalizeCondition(controls));
            case NotEqual:
            case NotEqual_2:
                return !isEqualsTextOrNumeric(data, normalizeCondition(controls));

            case Greater:
            case Greater_2:
                return toDouble(data) > toDouble(controls);
            case GreaterOrEqual:
            case GreaterOrEqual_2:
                return toDouble(data) >= toDouble(controls);
            case Lesser:
            case Lesser_2:
                return toDouble(data) < toDouble(controls);
            case LesserOrEqual:
            case LesserOrEqual_2:
                return toDouble(data) <= toDouble(controls);

            case Between: {
                Pair<Double, Double> range = toNumericRange(controls);
                double value = toDouble(data);
                return value >= range.getLeft() && value <= range.getRight();
            }

            case StartsWith:
                return TextUtils.isBetween(controls, IS_OPEN_TAG, IS_CLOSE_TAG) ?
                       toControlStream(controls).anyMatch(data::startsWith) :
                       StringUtils.startsWith(data, normalizeCondition(controls));
            case EndsWith:
                return TextUtils.isBetween(controls, IS_OPEN_TAG, IS_CLOSE_TAG) ?
                       toControlStream(controls).anyMatch(data::endsWith) :
                       StringUtils.endsWith(data, normalizeCondition(controls));
            case Contain:
                return TextUtils.isBetween(controls, IS_OPEN_TAG, IS_CLOSE_TAG) ?
                       toControlStream(controls).anyMatch(data::contains) :
                       StringUtils.contains(data, normalizeCondition(controls));

            case Match:
                return RegexUtils.isExact(data, controls);

            case Is:
            case In:
                return StringUtils.isEmpty(data) || toControlStream(controls).anyMatch(data::equals);

            case IsNot:
            case NotIn:
                return (StringUtils.equals(StringUtils.deleteWhitespace(controls), IS_OPEN_TAG + IS_CLOSE_TAG) &&
                        StringUtils.isEmpty(data)) ||
                       toControlStream(controls).noneMatch(data::equals);

            // not applicable in this case
            case IsDefined:
            case IsUndefined:
                throw new IllegalArgumentException("Not application filter: " + comparator);

            case IsEmpty:
                return StringUtils.isEmpty(data);

            case IsNotEmpty:
                return StringUtils.isNotEmpty(data);

            case HasLengthOf:
                return StringUtils.length(data) == toDouble(controls);

            default:
                throw new IllegalArgumentException("Invalid/unknown comparator: " + comparator.getSymbol());
        }
    }

    public static boolean isEqualsTextOrNumeric(String expected, String actual) {
        if (StringUtils.equals(expected, actual)) { return true; }

        return NumberUtils.isCreatable(numericReady(expected)) &&
               NumberUtils.isCreatable(numericReady(actual)) &&
               toDouble(expected) == toDouble(actual);
    }

    @Override
    public String toString() {
        return subject + (comparator == Any ? " MATCH ANY " : comparator) + StringUtils.defaultString(controls);
    }

    /**
     * not all conditions are specified within double quotes, but we should provide warning when there are
     * unbalanced quotes in condition.
     */
    protected static String normalizeCondition(String value) {
        value = StringUtils.trim(value);
        if ((StringUtils.startsWith(value, "\"") && !StringUtils.endsWith(value, "\"")) ||
            (!StringUtils.startsWith(value, "\"") && StringUtils.endsWith(value, "\""))) {
            ConsoleUtils.error("Found unbalance double-quote -- possible data issue: " + value);
        }

        if (StringUtils.startsWith(value, "\"") && StringUtils.endsWith(value, "\"")) {
            value = StringUtils.unwrap(value, "\"");
        } else {
            // could be literal number, boolean, string.  But we need to trim because it's likely to have
            // space(s) between conditions.
            value = StringUtils.trim(value);
        }

        return value;
    }

    protected static Pair<Double, Double> toNumericRange(String controls) {
        controls = StringUtils.trim(StringUtils.substringBetween(controls, IS_OPEN_TAG, IS_CLOSE_TAG));
        String[] array = StringUtils.split(controls, ITEM_SEP);
        if (ArrayUtils.getLength(array) != 2) {
            throw new IllegalArgumentException("range controls must be exactly 2 value (low, high): " + controls);
        }

        double num1 = toDouble(array[0]);
        double num2 = toDouble(array[1]);
        return num1 > num2 ? new ImmutablePair<>(num2, num1) : new ImmutablePair<>(num1, num2);
    }

    protected static Stream<String> toControlStream(String controls) {
        controls = StringUtils.trim(StringUtils.substringBetween(controls, IS_OPEN_TAG, IS_CLOSE_TAG));
        return Arrays.stream(StringUtils.split(controls, ITEM_SEP))
                     .map(StringUtils::trim)
                     .map(NexialFilter::normalizeCondition);
    }

    protected static boolean canBeNumber(String controls) { return NumberUtils.isCreatable(numericReady(controls)); }

    protected static double toDouble(String controls) { return NumberUtils.toDouble(numericReady(controls)); }

    protected static String numericReady(String controls) {
        controls = StringUtils.unwrap(controls, "\"");
        controls = StringUtils.unwrap(controls, "'");
        controls = StringUtils.trim(controls);
        controls = StringUtils.removeStart(controls, "$");
        return StringUtils.deleteWhitespace(controls);
    }

    protected boolean isMatchByRegex(String actual, ExecutionContext context) {
        return RegexUtils.isExact(actual, context.replaceTokens(controls));
    }

    protected boolean isMatchingStringCompare(String actual, ExecutionContext context) {
        String expected = context.replaceTokens(controls);
        switch (comparator) {
            case StartsWith:
                return StringUtils.startsWith(actual, expected);
            case EndsWith:
                return StringUtils.endsWith(actual, expected);
            case Contain: {
                boolean containsAll = true;
                for (String contain : controlList) {
                    if (!StringUtils.contains(actual, context.replaceTokens(contain))) {
                        containsAll = false;
                        break;
                    }
                }

                return containsAll;
            }
            default: {
                ConsoleUtils.error("UNSUPPORTED Operator for text-compare: " + comparator.getSymbol());
                return false;
            }
        }
    }

    protected boolean isInRange(String actual, ExecutionContext context, String msgPrefix) {
        msgPrefix = msgPrefix + " [" + comparator.getSymbol() + "] - ";

        if (!NumberUtils.isCreatable(actual)) {
            ConsoleUtils.error(msgPrefix + "NOT A NUMBER: " + actual);
            return false;
        }

        if (CollectionUtils.size(controlList) != 2) {
            ConsoleUtils.error(msgPrefix + "EXPECTS [min|max]: " + controls);
            return false;
        }

        String minVal = context.replaceTokens(StringUtils.trim(controlList.get(0)));
        if (StringUtils.startsWith(minVal, "\"") && StringUtils.endsWith(minVal, "\"")) {
            minVal = StringUtils.trim(StringUtils.substringBetween(minVal, "\"", "\""));
        }
        if (!NumberUtils.isCreatable(minVal)) {
            ConsoleUtils.error(msgPrefix + "NOT A NUMBER: " + minVal);
            return false;
        }

        String maxVal = context.replaceTokens(StringUtils.trim(controlList.get(1)));
        if (StringUtils.startsWith(maxVal, "\"") && StringUtils.endsWith(maxVal, "\"")) {
            maxVal = StringUtils.trim(StringUtils.substringBetween(maxVal, "\"", "\""));
        }
        if (!NumberUtils.isCreatable(maxVal)) {
            ConsoleUtils.error(msgPrefix + "NOT A NUMBER: " + maxVal);
            return false;
        }

        return CheckUtils.isInRange(NumberUtils.toDouble(actual),
                                    NumberUtils.toDouble(minVal),
                                    NumberUtils.toDouble(maxVal));
    }

    protected boolean isAtLeastOneMatched(String actual, ExecutionContext context) {
        return toSubstitutedControlList(context).contains(actual);
    }

    protected boolean isNoneMatched(String actual, ExecutionContext context) {
        return !toSubstitutedControlList(context).contains(actual);
    }

    protected boolean isNumericMatch(String actual, ExecutionContext context, String msgPrefix) {
        msgPrefix = msgPrefix + " [" + comparator.getSymbol() + "] - ";

        if (!NumberUtils.isCreatable(actual)) {
            ConsoleUtils.error(msgPrefix + "NOT A NUMBER: " + actual);
            return false;
        }

        String expected = context.replaceTokens(controls);
        if (!NumberUtils.isCreatable(expected)) {
            ConsoleUtils.error(msgPrefix + "NOT A NUMBER: " + expected);
            return false;
        }

        double actualNum = NumberUtils.toDouble(actual);
        double expectedNum = NumberUtils.toDouble(expected);

        switch (comparator) {
            case Greater:
            case Greater_2:
                return actualNum > expectedNum;
            case GreaterOrEqual:
            case GreaterOrEqual_2:
                return actualNum >= expectedNum;
            case Lesser:
            case Lesser_2:
                return actualNum < expectedNum;
            case LesserOrEqual:
            case LesserOrEqual_2:
                return actualNum <= expectedNum;
            case HasLengthOf:
                return actualNum == expectedNum;
            default: {
                ConsoleUtils.error(msgPrefix + "UNKNOWN COMPARISON");
                return false;
            }
        }
    }

    @NotNull
    private List<String> toSubstitutedControlList(ExecutionContext context) {
        List<String> matchList = new ArrayList<>();
        controlList.forEach(control -> matchList.add(context.replaceTokens(control)));
        return matchList;
    }
}
