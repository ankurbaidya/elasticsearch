/*
 * Licensed to Elastic Search and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Elastic Search licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
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

package org.elasticsearch.index.mapper.xcontent;

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.Fieldable;
import org.apache.lucene.search.Filter;
import org.apache.lucene.search.NumericRangeFilter;
import org.apache.lucene.search.NumericRangeQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.util.NumericUtils;
import org.elasticsearch.common.Numbers;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.index.analysis.NamedAnalyzer;
import org.elasticsearch.index.analysis.NumericIntegerAnalyzer;
import org.elasticsearch.index.cache.field.data.FieldDataCache;
import org.elasticsearch.index.field.data.FieldDataType;
import org.elasticsearch.index.mapper.MapperParsingException;
import org.elasticsearch.index.mapper.MergeMappingException;
import org.elasticsearch.index.search.NumericRangeFieldDataFilter;

import java.io.IOException;
import java.util.Map;

import static org.elasticsearch.common.xcontent.support.XContentMapValues.*;
import static org.elasticsearch.index.mapper.xcontent.XContentMapperBuilders.*;
import static org.elasticsearch.index.mapper.xcontent.XContentTypeParsers.*;

/**
 * @author kimchy (shay.banon)
 */
public class IntegerFieldMapper extends NumberFieldMapper<Integer> {

    public static final String CONTENT_TYPE = "integer";

    public static class Defaults extends NumberFieldMapper.Defaults {
        public static final Integer NULL_VALUE = null;
    }

    public static class Builder extends NumberFieldMapper.Builder<Builder, IntegerFieldMapper> {

        protected Integer nullValue = Defaults.NULL_VALUE;

        public Builder(String name) {
            super(name);
            builder = this;
        }

        public Builder nullValue(int nullValue) {
            this.nullValue = nullValue;
            return this;
        }

        @Override public IntegerFieldMapper build(BuilderContext context) {
            IntegerFieldMapper fieldMapper = new IntegerFieldMapper(buildNames(context),
                    precisionStep, fuzzyFactor, index, store, boost, omitNorms, omitTermFreqAndPositions, nullValue);
            fieldMapper.includeInAll(includeInAll);
            return fieldMapper;
        }
    }

    public static class TypeParser implements XContentMapper.TypeParser {
        @Override public XContentMapper.Builder parse(String name, Map<String, Object> node, ParserContext parserContext) throws MapperParsingException {
            IntegerFieldMapper.Builder builder = integerField(name);
            parseNumberField(builder, name, node, parserContext);
            for (Map.Entry<String, Object> entry : node.entrySet()) {
                String propName = Strings.toUnderscoreCase(entry.getKey());
                Object propNode = entry.getValue();
                if (propName.equals("null_value")) {
                    builder.nullValue(nodeIntegerValue(propNode));
                }
            }
            return builder;
        }
    }

    private Integer nullValue;

    private String nullValueAsString;

    protected IntegerFieldMapper(Names names, int precisionStep, String fuzzyFactor, Field.Index index, Field.Store store,
                                 float boost, boolean omitNorms, boolean omitTermFreqAndPositions,
                                 Integer nullValue) {
        super(names, precisionStep, fuzzyFactor, index, store, boost, omitNorms, omitTermFreqAndPositions,
                new NamedAnalyzer("_int/" + precisionStep, new NumericIntegerAnalyzer(precisionStep)),
                new NamedAnalyzer("_int/max", new NumericIntegerAnalyzer(Integer.MAX_VALUE)));
        this.nullValue = nullValue;
        this.nullValueAsString = nullValue == null ? null : nullValue.toString();
    }

    @Override protected int maxPrecisionStep() {
        return 32;
    }

    @Override public Integer value(Fieldable field) {
        byte[] value = field.getBinaryValue();
        if (value == null) {
            return null;
        }
        return Numbers.bytesToInt(value);
    }

    @Override public Integer valueFromString(String value) {
        return Integer.parseInt(value);
    }

    @Override public String indexedValue(String value) {
        return NumericUtils.intToPrefixCoded(Integer.parseInt(value));
    }

    @Override public Query fuzzyQuery(String value, String minSim, int prefixLength, int maxExpansions) {
        int iValue = Integer.parseInt(value);
        int iSim;
        try {
            iSim = Integer.parseInt(minSim);
        } catch (NumberFormatException e) {
            iSim = (int) Float.parseFloat(minSim);
        }
        return NumericRangeQuery.newIntRange(names.indexName(), precisionStep,
                iValue - iSim,
                iValue + iSim,
                true, true);
    }

    @Override public Query fuzzyQuery(String value, double minSim, int prefixLength, int maxExpansions) {
        int iValue = Integer.parseInt(value);
        int iSim = (int) (minSim * dFuzzyFactor);
        return NumericRangeQuery.newIntRange(names.indexName(), precisionStep,
                iValue - iSim,
                iValue + iSim,
                true, true);
    }

    @Override public Query rangeQuery(String lowerTerm, String upperTerm, boolean includeLower, boolean includeUpper) {
        return NumericRangeQuery.newIntRange(names.indexName(), precisionStep,
                lowerTerm == null ? null : Integer.parseInt(lowerTerm),
                upperTerm == null ? null : Integer.parseInt(upperTerm),
                includeLower, includeUpper);
    }

    @Override public Filter rangeFilter(String lowerTerm, String upperTerm, boolean includeLower, boolean includeUpper) {
        return NumericRangeFilter.newIntRange(names.indexName(), precisionStep,
                lowerTerm == null ? null : Integer.parseInt(lowerTerm),
                upperTerm == null ? null : Integer.parseInt(upperTerm),
                includeLower, includeUpper);
    }

    @Override public Filter rangeFilter(FieldDataCache fieldDataCache, String lowerTerm, String upperTerm, boolean includeLower, boolean includeUpper) {
        return NumericRangeFieldDataFilter.newIntRange(fieldDataCache, names.indexName(),
                lowerTerm == null ? null : Integer.parseInt(lowerTerm),
                upperTerm == null ? null : Integer.parseInt(upperTerm),
                includeLower, includeUpper);
    }

    @Override protected Fieldable parseCreateField(ParseContext context) throws IOException {
        int value;
        if (context.externalValueSet()) {
            Object externalValue = context.externalValue();
            if (externalValue == null) {
                if (nullValue == null) {
                    return null;
                }
                value = nullValue;
            } else {
                value = ((Number) externalValue).intValue();
            }
            if (context.includeInAll(includeInAll)) {
                context.allEntries().addText(names.fullName(), Integer.toString(value), boost);
            }
        } else {
            if (context.parser().currentToken() == XContentParser.Token.VALUE_NULL) {
                if (nullValue == null) {
                    return null;
                }
                value = nullValue;
                if (nullValueAsString != null && (context.includeInAll(includeInAll))) {
                    context.allEntries().addText(names.fullName(), nullValueAsString, boost);
                }
            } else {
                value = context.parser().intValue();
                if (context.includeInAll(includeInAll)) {
                    context.allEntries().addText(names.fullName(), context.parser().text(), boost);
                }
            }
        }

        return new CustomIntegerNumericField(this, value);
    }

    @Override public FieldDataType fieldDataType() {
        return FieldDataType.DefaultTypes.INT;
    }

    @Override protected String contentType() {
        return CONTENT_TYPE;
    }

    @Override public void merge(XContentMapper mergeWith, MergeContext mergeContext) throws MergeMappingException {
        super.merge(mergeWith, mergeContext);
        if (!this.getClass().equals(mergeWith.getClass())) {
            return;
        }
        if (!mergeContext.mergeFlags().simulate()) {
            this.nullValue = ((IntegerFieldMapper) mergeWith).nullValue;
            this.nullValueAsString = ((IntegerFieldMapper) mergeWith).nullValueAsString;
        }
    }

    @Override protected void doXContentBody(XContentBuilder builder) throws IOException {
        super.doXContentBody(builder);
        if (index != Defaults.INDEX) {
            builder.field("index", index.name().toLowerCase());
        }
        if (store != Defaults.STORE) {
            builder.field("store", store.name().toLowerCase());
        }
        if (termVector != Defaults.TERM_VECTOR) {
            builder.field("term_vector", termVector.name().toLowerCase());
        }
        if (omitNorms != Defaults.OMIT_NORMS) {
            builder.field("omit_norms", omitNorms);
        }
        if (omitTermFreqAndPositions != Defaults.OMIT_TERM_FREQ_AND_POSITIONS) {
            builder.field("omit_term_freq_and_positions", omitTermFreqAndPositions);
        }
        if (precisionStep != Defaults.PRECISION_STEP) {
            builder.field("precision_step", precisionStep);
        }
        if (fuzzyFactor != Defaults.FUZZY_FACTOR) {
            builder.field("fuzzy_factor", fuzzyFactor);
        }
        if (nullValue != null) {
            builder.field("null_value", nullValue);
        }
        if (includeInAll != null) {
            builder.field("include_in_all", includeInAll);
        }
    }

    public static class CustomIntegerNumericField extends CustomNumericField {

        private final int number;

        private final NumberFieldMapper mapper;

        public CustomIntegerNumericField(NumberFieldMapper mapper, int number) {
            super(mapper, mapper.stored() ? Numbers.intToBytes(number) : null);
            this.mapper = mapper;
            this.number = number;
        }

        @Override public TokenStream tokenStreamValue() {
            if (isIndexed) {
                return mapper.popCachedStream().setIntValue(number);
            }
            return null;
        }
    }
}
