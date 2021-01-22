/*
 * Waltz - Enterprise Architecture
 * Copyright (C) 2016, 2017, 2018, 2019 Waltz open source project
 * See README.md for more information
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific
 *
 */

package com.khartec.waltz.data.cost;

import com.khartec.waltz.data.GenericSelector;
import com.khartec.waltz.data.InlineSelectFieldFactory;
import com.khartec.waltz.model.EntityKind;
import com.khartec.waltz.model.EntityReference;
import com.khartec.waltz.model.cost.EntityCost;
import com.khartec.waltz.model.cost.ImmutableEntityCost;
import com.khartec.waltz.schema.tables.records.CostRecord;
import org.jooq.*;
import org.jooq.impl.DSL;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import java.util.Set;

import static com.khartec.waltz.common.DateTimeUtilities.toLocalDateTime;
import static com.khartec.waltz.common.ListUtilities.newArrayList;
import static com.khartec.waltz.model.EntityReference.mkRef;
import static com.khartec.waltz.schema.Tables.COST;


@Repository
public class CostDao {

    private final DSLContext dsl;

    private static final Field<String> ENTITY_NAME_FIELD = InlineSelectFieldFactory.mkNameField(
            COST.ENTITY_ID,
            COST.ENTITY_KIND,
            newArrayList(EntityKind.APPLICATION))
            .as("entity_name");

    private RecordMapper<Record, EntityCost> TO_COST_MAPPER = r -> {
        CostRecord record = r.into(COST);
        EntityReference ref = mkRef(EntityKind.valueOf(record.getEntityKind()), record.getEntityId(), r.getValue(ENTITY_NAME_FIELD));
        return ImmutableEntityCost.builder()
                .id(record.getId())
                .costKindId(record.getCostKindId())
                .entityReference(ref)
                .amount(record.getAmount())
                .year(record.getYear())
                .lastUpdatedAt(toLocalDateTime(record.getLastUpdatedAt()))
                .lastUpdatedBy(record.getLastUpdatedBy())
                .provenance(record.getProvenance())
                .build();
    };


    @Autowired
    public CostDao(DSLContext dsl) {
        this.dsl = dsl;
    }


    public Set<EntityCost> findByEntityReference(EntityReference ref){
        return dsl
                .select(ENTITY_NAME_FIELD)
                .select(COST.fields())
                .from(COST)
                .where(COST.ENTITY_ID.eq(ref.id())
                        .and(COST.ENTITY_KIND.eq(ref.kind().name())))
                .fetchSet(TO_COST_MAPPER);
    }


    public Set<EntityCost> findBySelectorForYear(GenericSelector genericSelector){

        SelectHavingStep<Record2<Long, Integer>> cost_kind_latest_year = DSL
                .select(COST.COST_KIND_ID, DSL.max(COST.YEAR).as("latest_year"))
                .from(COST)
                .where(COST.ENTITY_ID.in(genericSelector.selector())
                        .and(COST.ENTITY_KIND.eq(genericSelector.kind().name())))
                .groupBy(COST.COST_KIND_ID);

        Condition latest_year_for_kind = COST.COST_KIND_ID.eq(cost_kind_latest_year.field(COST.COST_KIND_ID))
                .and(COST.YEAR.eq(cost_kind_latest_year.field("latest_year", Integer.class)));

        return dsl
                .select(ENTITY_NAME_FIELD)
                .select(COST.fields())
                .from(COST)
                .innerJoin(cost_kind_latest_year).on(dsl.renderInlined(latest_year_for_kind))
                .where(COST.ENTITY_ID.in(genericSelector.selector())
                        .and(COST.ENTITY_KIND.eq(genericSelector.kind().name())))
                .fetchSet(TO_COST_MAPPER);
    }


    public Set<EntityCost> findByCostKindIdAndSelector(long costKindId,
                                                       GenericSelector genericSelector,
                                                       int limit){

        SelectConditionStep<Record1<Integer>> latestYear = DSL
                .select(DSL.max(COST.YEAR).as("latest_year"))
                .from(COST)
                .where(COST.COST_KIND_ID.eq(costKindId))
                .and(COST.ENTITY_ID.in(genericSelector.selector())
                        .and(COST.ENTITY_KIND.eq(genericSelector.kind().name())));

        return dsl
                .select(ENTITY_NAME_FIELD)
                .select(COST.fields())
                .from(COST)
                .where(COST.ENTITY_ID.in(genericSelector.selector())
                        .and(COST.ENTITY_KIND.eq(genericSelector.kind().name())))
                .and(COST.COST_KIND_ID.eq(costKindId))
                .and(COST.YEAR.eq(latestYear))
                .orderBy(COST.AMOUNT.desc())
                .limit(limit)
                .fetchSet(TO_COST_MAPPER);
    }

}