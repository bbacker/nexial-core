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
 */

package org.nexial.core.variable;

import org.junit.Test;
import org.nexial.core.ExcelBasedTests;
import org.nexial.core.model.ExecutionSummary;

public class HeadlessExpressionTests extends ExcelBasedTests {
    // static {
    //     System.setProperty(OUTPUT_TO_CLOUD, "false");
    //     System.setProperty(OPT_RUN_ID_PREFIX, "unitTest_expression");
    //     System.setProperty(OPT_OPEN_RESULT, "off");
    // }

    @Test
    public void number() throws Exception {
        ExecutionSummary executionSummary = testViaExcel("unitTest_expressions.xlsx", "NUMBER");
        assertPassFail(executionSummary, "NUMBER", TestOutcomeStats.allPassed());
    }

    @Test
    public void excel() throws Exception {
        System.setProperty("nexial.openResult", "true");
        System.setProperty("nexial.inspectOnPause", "true");
        ExecutionSummary executionSummary = testViaExcel("unitTest_expressions.xlsx", "EXCEL");
        assertPassFail(executionSummary, "EXCEL", TestOutcomeStats.allPassed());
    }
}
