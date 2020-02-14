package com.khartec.waltz.data.measurable_rating_planned_decommission;


import com.khartec.waltz.common.DateTimeUtilities;
import com.khartec.waltz.model.EntityKind;
import com.khartec.waltz.model.EntityReference;
import com.khartec.waltz.model.command.DateFieldChange;
import com.khartec.waltz.model.measurable_rating_planned_decommission.ImmutableMeasurableRatingPlannedDecommission;
import com.khartec.waltz.model.measurable_rating_planned_decommission.MeasurableRatingPlannedDecommission;
import com.khartec.waltz.schema.tables.records.MeasurableRatingPlannedDecommissionRecord;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.RecordMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import java.util.Set;

import static com.khartec.waltz.common.Checks.checkNotNull;
import static com.khartec.waltz.common.DateTimeUtilities.toLocalDateTime;
import static com.khartec.waltz.common.DateTimeUtilities.toSqlDate;
import static com.khartec.waltz.model.EntityReference.mkRef;
import static com.khartec.waltz.schema.tables.MeasurableRatingPlannedDecommission.MEASURABLE_RATING_PLANNED_DECOMMISSION;

@Repository
public class MeasurableRatingPlannedDecommissionDao {

    private final DSLContext dsl;


    @Autowired
    MeasurableRatingPlannedDecommissionDao(DSLContext dsl){
        checkNotNull(dsl, "dsl must not be null");
        this.dsl = dsl;
    }


    public static final RecordMapper<? super Record, MeasurableRatingPlannedDecommission> TO_DOMAIN_MAPPER = record -> {

        MeasurableRatingPlannedDecommissionRecord r = record.into(MEASURABLE_RATING_PLANNED_DECOMMISSION);

        return ImmutableMeasurableRatingPlannedDecommission.builder()
                .id(r.getId())
                .entityReference(mkRef(EntityKind.valueOf(r.getEntityKind()), r.getEntityId()))
                .measurableId(r.getMeasurableId())
                .plannedDecommissionDate(toLocalDateTime(r.getPlannedDecommissionDate()))
                .createdAt(toLocalDateTime(r.getCreatedAt()))
                .createdBy(r.getCreatedBy())
                .lastUpdatedAt(toLocalDateTime(r.getUpdatedAt()))
                .lastUpdatedBy(r.getUpdatedBy())
                .build();
    };


    public MeasurableRatingPlannedDecommission getById(Long id){
        return dsl
                .selectFrom(MEASURABLE_RATING_PLANNED_DECOMMISSION)
                .where(MEASURABLE_RATING_PLANNED_DECOMMISSION.ID.eq(id))
                .fetchOne(TO_DOMAIN_MAPPER);
    }


    public Set<MeasurableRatingPlannedDecommission> fetchByEntityRef(EntityReference ref){
        return dsl
                .selectFrom(MEASURABLE_RATING_PLANNED_DECOMMISSION)
                .where(mkRefCondition(ref))
                .fetchSet(TO_DOMAIN_MAPPER);
    }


    private Condition mkRefCondition(EntityReference ref) {
        return MEASURABLE_RATING_PLANNED_DECOMMISSION.ENTITY_ID.eq(ref.id())
                .and(MEASURABLE_RATING_PLANNED_DECOMMISSION.ENTITY_KIND.eq(ref.kind().name()));
    }


    public boolean save(EntityReference entityReference, long measurableId, DateFieldChange dateChange, String userName) {
        MeasurableRatingPlannedDecommissionRecord existingRecord = dsl
                .selectFrom(MEASURABLE_RATING_PLANNED_DECOMMISSION)
                .where(mkRefCondition(entityReference)
                        .and(MEASURABLE_RATING_PLANNED_DECOMMISSION.MEASURABLE_ID.eq(measurableId)))
                .fetchOne();

        if (existingRecord != null) {
            updateDecommDateOnRecord(existingRecord, dateChange, userName);
            return existingRecord.update() == 1;
        } else {
            MeasurableRatingPlannedDecommissionRecord record = dsl.newRecord(MEASURABLE_RATING_PLANNED_DECOMMISSION);
            updateDecommDateOnRecord(record, dateChange, userName);
            record.setCreatedAt(DateTimeUtilities.nowUtcTimestamp());
            record.setCreatedBy(userName);
            record.setEntityId(entityReference.id());
            record.setEntityKind(entityReference.kind().name());
            record.setMeasurableId(measurableId);
            return record.insert() == 1;
        }
    }


    public boolean remove(Long id){
        return dsl
                .deleteFrom(MEASURABLE_RATING_PLANNED_DECOMMISSION)
                .where(MEASURABLE_RATING_PLANNED_DECOMMISSION.ID.eq(id))
                .execute() == 1;
    }


    private void updateDecommDateOnRecord(MeasurableRatingPlannedDecommissionRecord record,
                                          DateFieldChange dateChange,
                                          String userName) {
        record.setUpdatedAt(DateTimeUtilities.nowUtcTimestamp());
        record.setUpdatedBy(userName);
        record.setPlannedDecommissionDate(toSqlDate(dateChange.newVal()));
    }


    public MeasurableRatingPlannedDecommission getByEntityAndMeasurable(EntityReference entityReference,
                                                                        long measurableId) {
        return dsl
                .selectFrom(MEASURABLE_RATING_PLANNED_DECOMMISSION)
                .where(mkRefCondition(entityReference)
                        .and(MEASURABLE_RATING_PLANNED_DECOMMISSION.MEASURABLE_ID.eq(measurableId)))
                .fetchOne(TO_DOMAIN_MAPPER);
    }
}