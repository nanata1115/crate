/*
 * Licensed to Crate.io GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */

package io.crate.expression.predicate;

import static io.crate.lucene.LuceneQueryBuilder.genericFunctionFilter;
import static io.crate.metadata.functions.TypeVariableConstraint.typeVariable;
import static io.crate.types.TypeSignature.parseTypeSignature;

import java.util.Collections;
import java.util.List;

import javax.annotation.Nullable;

import org.apache.lucene.document.FieldType;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.ConstantScoreQuery;
import org.apache.lucene.search.FieldExistsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.elasticsearch.common.lucene.search.Queries;
import org.elasticsearch.index.mapper.FieldNamesFieldMapper;

import io.crate.data.Input;
import io.crate.expression.symbol.DynamicReference;
import io.crate.expression.symbol.Function;
import io.crate.expression.symbol.Literal;
import io.crate.expression.symbol.Symbol;
import io.crate.lucene.LuceneQueryBuilder.Context;
import io.crate.metadata.NodeContext;
import io.crate.metadata.Reference;
import io.crate.metadata.Scalar;
import io.crate.metadata.TransactionContext;
import io.crate.metadata.functions.BoundSignature;
import io.crate.metadata.functions.Signature;
import io.crate.sql.tree.ColumnPolicy;
import io.crate.types.ArrayType;
import io.crate.types.DataTypes;
import io.crate.types.ObjectType;
import io.crate.types.StorageSupport;

public class IsNullPredicate<T> extends Scalar<Boolean, T> {

    public static final String NAME = "op_isnull";
    public static final Signature SIGNATURE = Signature.scalar(
        NAME,
        parseTypeSignature("E"),
        DataTypes.BOOLEAN.getTypeSignature()
    ).withTypeVariableConstraints(typeVariable("E"));

    public static void register(PredicateModule module) {
        module.register(
            SIGNATURE,
            IsNullPredicate::new
        );
    }

    private final Signature signature;
    private final BoundSignature boundSignature;

    private IsNullPredicate(Signature signature, BoundSignature boundSignature) {
        this.signature = signature;
        this.boundSignature = boundSignature;
    }

    @Override
    public Signature signature() {
        return signature;
    }

    @Override
    public BoundSignature boundSignature() {
        return boundSignature;
    }

    @Override
    public Symbol normalizeSymbol(Function symbol, TransactionContext txnCtx, NodeContext nodeCtx) {
        assert symbol != null : "function must not be null";
        assert symbol.arguments().size() == 1 : "function's number of arguments must be 1";
        Symbol arg = symbol.arguments().get(0);
        if (arg instanceof Input<?> input) {
            return Literal.of(input.value() == null);
        }
        return symbol;
    }

    @Override
    @SafeVarargs
    public final Boolean evaluate(TransactionContext txnCtx, NodeContext nodeCtx, Input<T> ... args) {
        assert args.length == 1 : "number of args must be 1";
        return args[0].value() == null;
    }

    @Override
    public Query toQuery(Function function, Context context) {
        List<Symbol> arguments = function.arguments();
        assert arguments.size() == 1 : "`<expression> IS NULL` function must have one argument";
        if (arguments.get(0) instanceof Reference ref) {
            if (!ref.isNullable()) {
                return Queries.newMatchNoDocsQuery("`x IS NULL` on column that is NOT NULL can't match");
            }
            Query refExistsQuery = refExistsQuery(ref, context, true);
            return refExistsQuery == null ? null : Queries.not(refExistsQuery);
        }
        return null;
    }


    @Nullable
    public static Query refExistsQuery(Reference ref, Context context, boolean countEmptyArrays) {
        String field = ref.column().fqn();
        if (ref.valueType() instanceof ArrayType<?>) {
            if (countEmptyArrays) {
                return new BooleanQuery.Builder()
                    .setMinimumNumberShouldMatch(1)
                    .add(new FieldExistsQuery(field), Occur.SHOULD)
                    .add(Queries.not(isNullFuncToQuery(ref, context)), Occur.SHOULD)
                    .build();
            } else {
                // An empty array has no dimension, array_length([]) = NULL, thus we don't count [] as existing.
                return new FieldExistsQuery(field);
            }
        }
        StorageSupport<?> storageSupport = ref.valueType().storageSupport();
        if (storageSupport == null && ref instanceof DynamicReference) {
            return Queries.newMatchNoDocsQuery("DynamicReference/type without storageSupport does not exist");
        } else if (ref.hasDocValues()) {
            return new FieldExistsQuery(field);
        } else if (ref.columnPolicy() == ColumnPolicy.IGNORED) {
            // Not indexed, need to use source lookup
            return null;
        } else if (storageSupport != null && storageSupport.hasFieldNamesIndex()) {
            FieldType fieldType = context.queryShardContext().getMapperService().getLuceneFieldType(field);
            if (fieldType != null && !fieldType.omitNorms()) {
                return new FieldExistsQuery(field);
            }

            if (ref.valueType() instanceof ObjectType objType) {
                if (objType.innerTypes().isEmpty()) {
                    return null;
                }
                BooleanQuery.Builder booleanQuery = new BooleanQuery.Builder();
                for (var entry : objType.innerTypes().entrySet()) {
                    String childColumn = entry.getKey();
                    Reference childRef = context.getRef(ref.column().append(childColumn));
                    if (childRef == null) {
                        return null;
                    }
                    Query refExistsQuery = refExistsQuery(childRef, context, true);
                    if (refExistsQuery == null) {
                        return null;
                    }
                    booleanQuery.add(refExistsQuery, Occur.SHOULD);
                }
                // Even if a child columns exist, an object can have empty values, we have to run generic function.
                // Example of such object:
                // CREATE TABLE t (obj OBJECT as (x int));
                // INSERT INTO t (obj) VALUES ({});
                return new BooleanQuery.Builder()
                    .setMinimumNumberShouldMatch(1)
                    .add(new ConstantScoreQuery(booleanQuery.build()), Occur.SHOULD)
                    .add(Queries.not(isNullFuncToQuery(ref, context)), Occur.SHOULD)
                    .build();
            }
            if (fieldType == null || fieldType.indexOptions() == IndexOptions.NONE && !fieldType.stored()) {
                return null;
            } else {
                return new ConstantScoreQuery(new TermQuery(new Term(FieldNamesFieldMapper.NAME, field)));
            }
        } else {
            return null;
        }
    }

    static Query isNullFuncToQuery(Symbol arg, Context context) {
        Function isNullFunction = new Function(
            IsNullPredicate.SIGNATURE,
            Collections.singletonList(arg),
            DataTypes.BOOLEAN
        );
        return genericFunctionFilter(isNullFunction, context);
    }

}
