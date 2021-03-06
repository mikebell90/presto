/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.sql.planner.iterative.rule;

import com.facebook.presto.metadata.FunctionRegistry;
import com.facebook.presto.metadata.MetadataManager;
import com.facebook.presto.sql.planner.Symbol;
import com.facebook.presto.sql.planner.iterative.rule.test.BaseRuleTest;
import com.facebook.presto.sql.planner.iterative.rule.test.PlanBuilder;
import com.facebook.presto.sql.planner.plan.AggregationNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Optional;

import static com.facebook.presto.spi.block.SortOrder.ASC_NULLS_FIRST;
import static com.facebook.presto.spi.type.BigintType.BIGINT;
import static com.facebook.presto.sql.planner.assertions.PlanMatchPattern.aggregationWithOrderBy;
import static com.facebook.presto.sql.planner.assertions.PlanMatchPattern.functionCall;
import static com.facebook.presto.sql.planner.assertions.PlanMatchPattern.values;
import static com.facebook.presto.sql.planner.plan.AggregationNode.Step.SINGLE;

public class TestPruneOrderByInAggregation
        extends BaseRuleTest
{
    private static final FunctionRegistry functionRegistry = MetadataManager.createTestMetadataManager().getFunctionRegistry();

    @Test
    public void testBasics()
    {
        tester().assertThat(new PruneOrderByInAggregation(functionRegistry))
                .on(this::buildAggregation)
                .matches(
                        aggregationWithOrderBy(
                                ImmutableList.of(ImmutableList.of("key")),
                                ImmutableMap.of(
                                        Optional.of("avg"),
                                        functionCall("avg", ImmutableList.of("input")),
                                        Optional.of("array_agg"),
                                        functionCall("array_agg", ImmutableList.of("input"))),
                                ImmutableMap.of(),
                                Optional.empty(),
                                ImmutableMap.of("avg", ImmutableList.of(), "array_agg", ImmutableList.of("input")),
                                ImmutableMap.of("avg", ImmutableList.of(), "array_agg", ImmutableList.of(ASC_NULLS_FIRST)),
                                SINGLE,
                                values("input", "key", "keyHash", "mask")));
    }

    private AggregationNode buildAggregation(PlanBuilder planBuilder)
    {
        Symbol avg = planBuilder.symbol("avg");
        Symbol arrayAgg = planBuilder.symbol("array_agg");
        Symbol input = planBuilder.symbol("input");
        Symbol key = planBuilder.symbol("key");
        Symbol keyHash = planBuilder.symbol("keyHash");
        Symbol mask = planBuilder.symbol("mask");
        List<Symbol> sourceSymbols = ImmutableList.of(input, key, keyHash, mask);
        return planBuilder.aggregation(aggregationBuilder -> aggregationBuilder
                .addGroupingSet(key)
                .addAggregation(avg, planBuilder.expression("avg(input)"), ImmutableList.of(BIGINT), mask, ImmutableList.of(input), ImmutableList.of(ASC_NULLS_FIRST))
                .addAggregation(arrayAgg, planBuilder.expression("array_agg(input)"), ImmutableList.of(BIGINT), mask, ImmutableList.of(input), ImmutableList.of(ASC_NULLS_FIRST))
                .hashSymbol(keyHash)
                .source(planBuilder.values(sourceSymbols, ImmutableList.of())));
    }
}
