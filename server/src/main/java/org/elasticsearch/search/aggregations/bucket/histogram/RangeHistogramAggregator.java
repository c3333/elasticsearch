/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.search.aggregations.bucket.histogram;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.index.fielddata.SortedBinaryDocValues;
import org.elasticsearch.index.mapper.RangeFieldMapper;
import org.elasticsearch.index.mapper.RangeType;
import org.elasticsearch.search.aggregations.Aggregator;
import org.elasticsearch.search.aggregations.AggregatorFactories;
import org.elasticsearch.search.aggregations.CardinalityUpperBound;
import org.elasticsearch.search.aggregations.BucketOrder;
import org.elasticsearch.search.aggregations.LeafBucketCollector;
import org.elasticsearch.search.aggregations.LeafBucketCollectorBase;
import org.elasticsearch.search.aggregations.support.ValuesSource;
import org.elasticsearch.search.aggregations.support.ValuesSourceConfig;
import org.elasticsearch.search.internal.SearchContext;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public class RangeHistogramAggregator extends AbstractHistogramAggregator {
    private final ValuesSource.Range valuesSource;

    public RangeHistogramAggregator(
        String name,
        AggregatorFactories factories,
        double interval,
        double offset,
        BucketOrder order,
        boolean keyed,
        long minDocCount,
        double minBound,
        double maxBound,
        ValuesSourceConfig valuesSourceConfig,
        SearchContext context,
        Aggregator parent,
        CardinalityUpperBound cardinality,
        Map<String, Object> metadata
    ) throws IOException {
        super(
            name,
            factories,
            interval,
            offset,
            order,
            keyed,
            minDocCount,
            minBound,
            maxBound,
            valuesSourceConfig.format(),
            context,
            parent,
            cardinality,
            metadata
        );
        // TODO: Stop using nulls here
        this.valuesSource = valuesSourceConfig.hasValues() ? (ValuesSource.Range) valuesSourceConfig.getValuesSource() : null;
        if (this.valuesSource.rangeType().isNumeric() == false) {
            throw new IllegalArgumentException(
                "Expected numeric range type but found non-numeric range [" + this.valuesSource.rangeType().name + "]"
            );
        }
    }

    @Override
    protected LeafBucketCollector getLeafCollector(LeafReaderContext ctx, LeafBucketCollector sub) throws IOException {
        if (valuesSource == null) {
            return LeafBucketCollector.NO_OP_COLLECTOR;
        }
        final SortedBinaryDocValues values = valuesSource.bytesValues(ctx);
        final RangeType rangeType = valuesSource.rangeType();
        return new LeafBucketCollectorBase(sub, values) {
            @Override
            public void collect(int doc, long owningBucketOrd) throws IOException {
                if (values.advanceExact(doc)) {
                    // Is it possible for valuesCount to be > 1 here? Multiple ranges are encoded into the same BytesRef in the binary doc
                    // values, so it isn't clear what we'd be iterating over.
                    final int valuesCount = values.docValueCount();
                    assert valuesCount == 1 : "Value count for ranges should always be 1";
                    double previousKey = Double.NEGATIVE_INFINITY;

                    for (int i = 0; i < valuesCount; i++) {
                        BytesRef encodedRanges = values.nextValue();
                        List<RangeFieldMapper.Range> ranges = rangeType.decodeRanges(encodedRanges);
                        double previousFrom = Double.NEGATIVE_INFINITY;
                        for (RangeFieldMapper.Range range : ranges) {
                            final Double from = rangeType.doubleValue(range.getFrom());
                            // The encoding should ensure that this assert is always true.
                            assert from >= previousFrom : "Start of range not >= previous start";
                            final Double to = rangeType.doubleValue(range.getTo());
                            final double startKey = Math.floor((from - offset) / interval);
                            final double endKey = Math.floor((to - offset) / interval);
                            for (double  key = startKey > previousKey ? startKey : previousKey; key <= endKey; key++) {
                                if (key == previousKey) {
                                    continue;
                                }
                                // Bucket collection identical to NumericHistogramAggregator, could be refactored
                                long bucketOrd = bucketOrds.add(owningBucketOrd, Double.doubleToLongBits(key));
                                if (bucketOrd < 0) { // already seen
                                    bucketOrd = -1 - bucketOrd;
                                    collectExistingBucket(sub, doc, bucketOrd);
                                } else {
                                    collectBucket(sub, doc, bucketOrd);
                                }
                            }
                            if (endKey > previousKey) {
                                previousKey = endKey;
                            }
                        }

                    }
                }
            }
        };
    }
}
