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

package org.elasticsearch.index.aliases;

import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.Filter;
import org.apache.lucene.search.FilterClause;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.collect.ImmutableMap;
import org.elasticsearch.common.collect.UnmodifiableIterator;
import org.elasticsearch.common.compress.CompressedString;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.lucene.search.XBooleanFilter;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.index.AbstractIndexComponent;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.query.IndexQueryParserService;
import org.elasticsearch.index.query.xcontent.XContentIndexQueryParser;
import org.elasticsearch.index.settings.IndexSettings;
import org.elasticsearch.indices.AliasFilterParsingException;
import org.elasticsearch.indices.InvalidAliasNameException;

import java.io.IOException;

import static org.elasticsearch.common.collect.MapBuilder.*;

/**
 * @author imotov
 */
public class IndexAliasesService extends AbstractIndexComponent implements Iterable<IndexAlias> {

    private final IndexQueryParserService indexQueryParserService;

    private volatile ImmutableMap<String, IndexAlias> aliases = ImmutableMap.of();

    private final Object mutex = new Object();

    @Inject public IndexAliasesService(Index index, @IndexSettings Settings indexSettings, IndexQueryParserService indexQueryParserService) {
        super(index, indexSettings);
        this.indexQueryParserService = indexQueryParserService;
    }

    public boolean hasAlias(String alias) {
        return aliases.containsKey(alias);
    }

    public IndexAlias alias(String alias) {
        return aliases.get(alias);
    }

    public void add(String alias, @Nullable CompressedString filter) {
        add(new IndexAlias(alias, filter, parse(alias, filter)));
    }

    /**
     * Returns the filter associated with listed filtering aliases.
     *
     * <p>The list of filtering aliases should be obtained by calling MetaData.filteringAliases.
     * Returns <tt>null</tt> if no filtering is required.</p>
     */
    public Filter aliasFilter(String... aliases) {
        if (aliases == null || aliases.length == 0) {
            return null;
        }
        if (aliases.length == 1) {
            IndexAlias indexAlias = alias(aliases[0]);
            if (indexAlias == null) {
                // This shouldn't happen unless alias disappeared after filteringAliases was called.
                throw new InvalidAliasNameException(index, aliases[0], "Unknown alias name was passed to alias Filter");
            }
            return indexAlias.parsedFilter();
        } else {
            // we need to bench here a bit, to see maybe it makes sense to use OrFilter
            XBooleanFilter combined = new XBooleanFilter();
            for (String alias : aliases) {
                IndexAlias indexAlias = alias(alias);
                if (indexAlias == null) {
                    // This shouldn't happen unless alias disappeared after filteringAliases was called.
                    throw new InvalidAliasNameException(index, aliases[0], "Unknown alias name was passed to alias Filter");
                }
                if (indexAlias.parsedFilter() != null) {
                    combined.add(new FilterClause(indexAlias.parsedFilter(), BooleanClause.Occur.SHOULD));
                } else {
                    // The filter might be null only if filter was removed after filteringAliases was called
                    return null;
                }
            }
            if (combined.getShouldFilters().size() == 0) {
                return null;
            }
            if (combined.getShouldFilters().size() == 1) {
                return combined.getShouldFilters().get(0);
            }
            return combined;
        }
    }

    private void add(IndexAlias indexAlias) {
        synchronized (mutex) {
            aliases = newMapBuilder(aliases).put(indexAlias.alias(), indexAlias).immutableMap();
        }
    }

    public void remove(String alias) {
        synchronized (mutex) {
            aliases = newMapBuilder(aliases).remove(alias).immutableMap();
        }
    }

    private Filter parse(String alias, CompressedString filter) {
        if (filter == null) {
            return null;
        }
        XContentIndexQueryParser indexQueryParser = (XContentIndexQueryParser) indexQueryParserService.defaultIndexQueryParser();
        try {
            byte[] filterSource = filter.uncompressed();
            XContentParser parser = XContentFactory.xContent(filterSource).createParser(filterSource);
            try {
                return indexQueryParser.parseInnerFilter(parser);
            } finally {
                parser.close();
            }
        } catch (IOException ex) {
            throw new AliasFilterParsingException(index, alias, "Invalid alias filter", ex);
        }
    }

    @Override public UnmodifiableIterator<IndexAlias> iterator() {
        return aliases.values().iterator();
    }
}
