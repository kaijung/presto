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

package com.facebook.presto.spi.plan;

import com.facebook.presto.spi.ColumnHandle;
import com.facebook.presto.spi.ConnectorSession;
import com.facebook.presto.spi.relation.RowExpression;
import com.facebook.presto.spi.statistics.TableStatistics;
import com.facebook.presto.spi.type.Type;

import java.util.Map;

/**
 * Estimates the size of the data after applying a predicate using calculations compatible with Cost-Based Optimizer.
 */
public interface FilterStatsCalculatorService
{
    TableStatistics filterStats(TableStatistics tableStatistics, RowExpression predicate, ConnectorSession session, Map<ColumnHandle, String> columnNames, Map<ColumnHandle, Type> columnTypes);
}
