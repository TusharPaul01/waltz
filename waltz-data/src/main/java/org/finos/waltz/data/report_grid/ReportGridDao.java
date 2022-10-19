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

package org.finos.waltz.data.report_grid;


import org.finos.waltz.common.DateTimeUtilities;
import org.finos.waltz.common.SetUtilities;
import org.finos.waltz.common.hierarchy.FlatNode;
import org.finos.waltz.common.hierarchy.Forest;
import org.finos.waltz.common.hierarchy.Node;
import org.finos.waltz.data.GenericSelector;
import org.finos.waltz.data.InlineSelectFieldFactory;
import org.finos.waltz.model.EntityKind;
import org.finos.waltz.model.EntityReference;
import org.finos.waltz.model.entity_field_reference.EntityFieldReference;
import org.finos.waltz.model.entity_field_reference.ImmutableEntityFieldReference;
import org.finos.waltz.model.report_grid.*;
import org.finos.waltz.model.usage_info.UsageKind;
import org.finos.waltz.schema.Tables;
import org.finos.waltz.schema.tables.ChangeInitiative;
import org.finos.waltz.schema.tables.records.ReportGridColumnDefinitionRecord;
import org.finos.waltz.schema.tables.records.ReportGridDerivedColumnDefinitionRecord;
import org.finos.waltz.schema.tables.records.ReportGridFixedColumnDefinitionRecord;
import org.finos.waltz.schema.tables.records.ReportGridRecord;
import org.jooq.*;
import org.jooq.impl.DSL;
import org.jooq.lambda.tuple.Tuple2;
import org.jooq.lambda.tuple.Tuple3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.*;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;

import static java.lang.String.format;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptySet;
import static java.util.Optional.ofNullable;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.*;
import static org.finos.waltz.common.CollectionUtilities.first;
import static org.finos.waltz.common.CollectionUtilities.isEmpty;
import static org.finos.waltz.common.DateTimeUtilities.toLocalDate;
import static org.finos.waltz.common.DateTimeUtilities.toLocalDateTime;
import static org.finos.waltz.common.ListUtilities.asList;
import static org.finos.waltz.common.ListUtilities.newArrayList;
import static org.finos.waltz.common.MapUtilities.groupBy;
import static org.finos.waltz.common.MapUtilities.indexBy;
import static org.finos.waltz.common.SetUtilities.*;
import static org.finos.waltz.common.StringUtilities.join;
import static org.finos.waltz.common.hierarchy.HierarchyUtilities.toForest;
import static org.finos.waltz.data.JooqUtilities.fieldsWithout;
import static org.finos.waltz.model.EntityReference.mkRef;
import static org.finos.waltz.model.survey.SurveyInstanceStatus.APPROVED;
import static org.finos.waltz.model.survey.SurveyInstanceStatus.COMPLETED;
import static org.finos.waltz.schema.Tables.*;
import static org.finos.waltz.schema.tables.DataType.DATA_TYPE;
import static org.finos.waltz.schema.tables.DataTypeUsage.DATA_TYPE_USAGE;
import static org.finos.waltz.schema.tables.InvolvementKind.INVOLVEMENT_KIND;
import static org.jooq.lambda.tuple.Tuple.tuple;

@Repository
public class ReportGridDao {

    private static final Logger LOG = LoggerFactory.getLogger(ReportGridDao.class);

    private final DSLContext dsl;

    private final org.finos.waltz.schema.tables.Measurable m = MEASURABLE.as("m");
    private final org.finos.waltz.schema.tables.MeasurableRating mr = MEASURABLE_RATING.as("mr");
    private final org.finos.waltz.schema.tables.MeasurableCategory mc = MEASURABLE_CATEGORY.as("mc");
    private final org.finos.waltz.schema.tables.ReportGridFixedColumnDefinition rgfcd = REPORT_GRID_FIXED_COLUMN_DEFINITION.as("rgfcd");
    private final org.finos.waltz.schema.tables.ReportGridColumnDefinition rgcd = REPORT_GRID_COLUMN_DEFINITION.as("rgcd");
    private final org.finos.waltz.schema.tables.ReportGridDerivedColumnDefinition rgdcd = REPORT_GRID_DERIVED_COLUMN_DEFINITION.as("rgdcd");
    private final org.finos.waltz.schema.tables.ReportGrid rg = Tables.REPORT_GRID.as("rg");
    private final org.finos.waltz.schema.tables.RatingSchemeItem rsi = RATING_SCHEME_ITEM.as("rsi");
    private final org.finos.waltz.schema.tables.EntityHierarchy eh = ENTITY_HIERARCHY.as("eh");
    private final org.finos.waltz.schema.tables.AssessmentDefinition ad = ASSESSMENT_DEFINITION.as("ad");
    private final org.finos.waltz.schema.tables.AssessmentRating ar = ASSESSMENT_RATING.as("ar");
    private final org.finos.waltz.schema.tables.CostKind ck = COST_KIND.as("ck");
    private final org.finos.waltz.schema.tables.Cost c = COST.as("c");
    private final org.finos.waltz.schema.tables.Complexity cx = COMPLEXITY.as("cx");
    private final org.finos.waltz.schema.tables.ComplexityKind cxk = COMPLEXITY_KIND.as("cxk");
    private final org.finos.waltz.schema.tables.Involvement inv = INVOLVEMENT.as("inv");
    private final org.finos.waltz.schema.tables.InvolvementKind ik = INVOLVEMENT_KIND.as("ik");
    private final org.finos.waltz.schema.tables.Person p = Tables.PERSON.as("p");
    private final org.finos.waltz.schema.tables.SurveyQuestion sq = SURVEY_QUESTION.as("sq");
    private final org.finos.waltz.schema.tables.ApplicationGroup ag = APPLICATION_GROUP.as("ag");
    private final org.finos.waltz.schema.tables.ApplicationGroupEntry age = APPLICATION_GROUP_ENTRY.as("age");
    private final org.finos.waltz.schema.tables.ApplicationGroupOuEntry agoe = APPLICATION_GROUP_OU_ENTRY.as("agoe");
    private final org.finos.waltz.schema.tables.EntityFieldReference efr = ENTITY_FIELD_REFERENCE.as("efr");
    private final org.finos.waltz.schema.tables.SurveyTemplate st = SURVEY_TEMPLATE.as("st");
    private final org.finos.waltz.schema.tables.Application a = APPLICATION.as("a");
    private final org.finos.waltz.schema.tables.ChangeInitiative ci = CHANGE_INITIATIVE.as("ci");
    private final org.finos.waltz.schema.tables.EntityRelationship er = ENTITY_RELATIONSHIP.as("er");
    private final org.finos.waltz.schema.tables.DataTypeUsage dtu = DATA_TYPE_USAGE.as("dtu");
    private final org.finos.waltz.schema.tables.DataType dt = DATA_TYPE.as("dt");
    private final org.finos.waltz.schema.tables.AttestationInstance att_i = ATTESTATION_INSTANCE.as("atti");
    private final org.finos.waltz.schema.tables.AttestationRun att_r = ATTESTATION_RUN.as("attr");
    private final org.finos.waltz.schema.tables.OrganisationalUnit ou = ORGANISATIONAL_UNIT.as("ou");
    private final org.finos.waltz.schema.tables.TagUsage tu = TAG_USAGE.as("tu");
    private final org.finos.waltz.schema.tables.Tag t = TAG.as("t");

    private final org.finos.waltz.schema.tables.EntityAlias ea = ENTITY_ALIAS.as("ea");
    private final org.finos.waltz.schema.tables.EntityStatisticValue esv = ENTITY_STATISTIC_VALUE.as("esv");
    private final org.finos.waltz.schema.tables.EntityStatisticDefinition esd = ENTITY_STATISTIC_DEFINITION.as("esd");

    private static final Field<String> ENTITY_NAME_FIELD = InlineSelectFieldFactory.mkNameField(
                    SURVEY_QUESTION_RESPONSE.ENTITY_RESPONSE_ID,
                    SURVEY_QUESTION_RESPONSE.ENTITY_RESPONSE_KIND,
                    newArrayList(EntityKind.PERSON, EntityKind.APPLICATION))
            .as("entity_name");


    @Autowired
    public ReportGridDao(DSLContext dsl) {
        this.dsl = dsl;
    }



    public Set<ReportGridDefinition> findAll(){
        return dsl
                .select(rg.fields())
                .from(rg)
                .fetchSet(r -> mkReportGridDefinition(rgcd.REPORT_GRID_ID.eq(r.get(rg.ID)), r.into(REPORT_GRID)));
    }


    public Set<ReportGridDefinition> findForUser(String username){
        return dsl
                .select(rg.fields())
                .from(rg)
                .leftJoin(REPORT_GRID_MEMBER)
                .on(rg.ID.eq(REPORT_GRID_MEMBER.GRID_ID))
                .where(rg.KIND.eq(ReportGridKind.PUBLIC.name()).or(REPORT_GRID_MEMBER.USER_ID.eq(username)))
                .fetchSet(r -> mkReportGridDefinition(rgcd.REPORT_GRID_ID.eq(r.get(rg.ID)), r.into(REPORT_GRID)));
    }


    public Set<ReportGridCell> findCellDataByGridId(long id,
                                                    GenericSelector genericSelector) {
        return findCellDataByGridCondition(rg.ID.eq(id), genericSelector);
    }


    public Set<ReportGridCell> findCellDataByGridExternalId(String externalId,
                                                            GenericSelector genericSelector) {
        return findCellDataByGridCondition(rg.EXTERNAL_ID.eq(externalId), genericSelector);
    }


    public ReportGridDefinition getGridDefinitionById(long id) {
        return getGridDefinitionByCondition(rg.ID.eq(id));
    }


    public ReportGridDefinition getGridDefinitionByExternalId(String externalId) {
        return getGridDefinitionByCondition(rg.EXTERNAL_ID.eq(externalId));
    }

    public void updateColumnDefinitions(long gridId, ReportGridColumnDefinitionsUpdateCommand updatedCmd) {

        dsl.transaction(ctx -> {
            DSLContext tx = ctx.dsl();

            //cascade delete should clear out the fixed and derived column entries
            int clearedColumns = deleteExistingColumns(tx, gridId);

            int[] fixedColumnsUpdated = insertFixedColumnDefinitions(tx, gridId, updatedCmd.fixedColumnDefinitions());
            int[] derivedColumnsUpdated = insertDerivedColumnDefinitions(tx, gridId, updatedCmd.derivedColumnDefinitions());

            LOG.debug(format("Successfully updated columns for grid: %d", gridId));
        });
    }

    private int deleteExistingColumns(DSLContext tx, long gridId) {
        return tx
                .deleteFrom(rgcd)
                .where(rgcd.REPORT_GRID_ID.eq(gridId))
                .execute();
    }

    private int[] insertFixedColumnDefinitions(DSLContext tx,
                                               long gridId,
                                               List<ReportGridFixedColumnDefinition> fixedColumnDefinitions) {

        fixedColumnDefinitions
                .stream()
                .map(c -> {
                    ReportGridColumnDefinitionRecord columnRecord = dsl.newRecord(rgcd);
                    columnRecord.setReportGridId(gridId);
                    columnRecord.setPosition(c.position());
                    return columnRecord;
                })
                .collect(Collectors.collectingAndThen(
                        toSet(),
                        d -> tx.batchInsert(d).execute()));

        Map<Integer, Long> positionToColumnMap = tx
                .select(rgcd.POSITION, rgcd.ID)
                .from(rgcd)
                .where(rgcd.REPORT_GRID_ID.eq(gridId)
                        .and(rgcd.POSITION.in(map(fixedColumnDefinitions, ReportGridFixedColumnDefinition::position))))
                .fetchMap(rgcd.POSITION, rgcd.ID);

        return fixedColumnDefinitions
                .stream()
                .map(fixedCol -> {
                    Long gridColId = positionToColumnMap.get(fixedCol.position()); // This may be ok so long as we fix into db rule for unique grid position index

                    Long fieldReferenceId = fixedCol.entityFieldReference() == null
                            ? null
                            : fixedCol.entityFieldReference().id().orElse(null);

                    ReportGridFixedColumnDefinitionRecord record = tx.newRecord(rgfcd);
                    record.setReportGridId(gridId);
                    record.setGridColumnId(gridColId);
                    record.setColumnEntityId(fixedCol.columnEntityId());
                    record.setColumnEntityKind(fixedCol.columnEntityKind().name());
                    record.setAdditionalColumnOptions(fixedCol.additionalColumnOptions().name());
                    record.setPosition(Long.valueOf(fixedCol.position()).intValue());
                    record.setDisplayName(fixedCol.displayName());
                    record.setColumnQualifierKind(Optional
                            .ofNullable(fixedCol.columnQualifierKind())
                            .map(Enum::name)
                            .orElse(null));
                    record.setColumnQualifierId(fixedCol.columnQualifierId());
                    record.setEntityFieldReferenceId(fieldReferenceId);
                    record.setExternalId(fixedCol.externalId().orElse(null));
                    return record;
                })
                .collect(collectingAndThen(toSet(), d -> tx.batchInsert(d).execute()));
    }


    private int[] insertDerivedColumnDefinitions(DSLContext tx,
                                                 long gridId,
                                                 List<ReportGridDerivedColumnDefinition> derivedColumnDefinitions) {

        derivedColumnDefinitions
                .stream()
                .map(c -> {
                    ReportGridColumnDefinitionRecord columnRecord = dsl.newRecord(rgcd);
                    columnRecord.setReportGridId(gridId);
                    columnRecord.setPosition(c.position());
                    return columnRecord;
                })
                .collect(Collectors.collectingAndThen(
                        toSet(),
                        d -> tx.batchInsert(d).execute()));

        Map<Integer, Long> positionToColumnMap = tx
                .select(rgcd.POSITION, rgcd.ID)
                .from(rgcd)
                .where(rgcd.REPORT_GRID_ID.eq(gridId)
                        .and(rgcd.POSITION.in(map(derivedColumnDefinitions, ReportGridDerivedColumnDefinition::position))))
                .fetchMap(rgcd.POSITION, rgcd.ID);


        return derivedColumnDefinitions
                .stream()
                .map(derivedCol -> {

                    Long colId = positionToColumnMap.get(derivedCol.position());

                    ReportGridDerivedColumnDefinitionRecord record = tx.newRecord(rgdcd);
                    record.setGridColumnId(colId);
                    record.setPosition(Long.valueOf(derivedCol.position()).intValue());
                    record.setDisplayName(derivedCol.displayName());
                    record.setExternalId(derivedCol.externalId().orElse(null));
                    record.setDerivationScript(derivedCol.derivationScript());
                    record.setColumnDescription(derivedCol.columnDescription());
                    record.setReportGridId(gridId);
                    return record;
                })
                .collect(collectingAndThen(toSet(), d -> tx.batchInsert(d).execute()));
    }


    public long create(ReportGridCreateCommand createCommand, String username) {
        ReportGridRecord record = dsl.newRecord(rg);
        record.setName(createCommand.name());
        record.setExternalId(createCommand.toExtId());
        record.setDescription(createCommand.description());
        record.setLastUpdatedAt(DateTimeUtilities.nowUtcTimestamp());
        record.setLastUpdatedBy(username);
        record.setProvenance("waltz");
        record.setSubjectKind(createCommand.subjectKind().name());
        record.setKind(createCommand.kind().name());

        try {
            record.insert();
            return record.getId();
        } catch (DataIntegrityViolationException exception) {
            throw new DataIntegrityViolationException(format(
                    "Grid already exists with the name: %s for user.",
                    createCommand.name()));
        }
    }

    public long update(long id, ReportGridUpdateCommand updateCommand, String username) {
        return dsl
                .update(rg)
                .set(rg.NAME, updateCommand.name())
                .set(rg.DESCRIPTION, updateCommand.description())
                .set(rg.KIND, updateCommand.kind().name())
                .set(rg.LAST_UPDATED_AT, DateTimeUtilities.nowUtcTimestamp())
                .set(rg.LAST_UPDATED_BY, username)
                .where(rg.ID.eq(id))
                .execute();
    }


    public Set<ReportGridDefinition> findForOwner(String username) {
        Condition isOwner = REPORT_GRID_MEMBER.USER_ID.eq(username)
                .and(REPORT_GRID_MEMBER.ROLE.eq(ReportGridMemberRole.OWNER.name()));

        return dsl
                .select(rg.fields())
                .from(rg)
                .innerJoin(REPORT_GRID_MEMBER)
                .on(rg.ID.eq(REPORT_GRID_MEMBER.GRID_ID))
                .where(isOwner)
                .fetchSet(r -> mkReportGridDefinition(rgcd.REPORT_GRID_ID.eq(r.get(rg.ID)), r.into(REPORT_GRID)));
    }


    public boolean remove(long gridId) {
        return dsl
                .deleteFrom(REPORT_GRID)
                .where(REPORT_GRID.ID.eq(gridId))
                .execute() == 1;
    }


    // --- Helpers ---

    private ReportGridDefinition getGridDefinitionByCondition(Condition condition) {
        return dsl
                .select(rg.fields())
                .from(rg)
                .where(condition)
                .fetchOne(r -> mkReportGridDefinition(condition, r.into(REPORT_GRID)));
    }


    private List<ReportGridFixedColumnDefinition> getFixedColumnDefinitions(Condition condition) {

        SelectConditionStep<Record7<Long, String, String, String, String, String, String>> measurableColumns = mkSupplementalColumnDefinitionQuery(
                EntityKind.MEASURABLE,
                m,
                m.ID,
                m.NAME,
                m.DESCRIPTION,
                condition);

        SelectConditionStep<Record7<Long, String, String, String, String, String, String>> assessmentDefinitionColumns = mkSupplementalColumnDefinitionQuery(
                EntityKind.ASSESSMENT_DEFINITION,
                ad,
                ad.ID,
                ad.NAME,
                ad.DESCRIPTION,
                condition);

        SelectConditionStep<Record7<Long, String, String, String, String, String, String>> costKindColumns = mkSupplementalColumnDefinitionQuery(
                EntityKind.COST_KIND,
                ck,
                ck.ID,
                ck.NAME,
                ck.DESCRIPTION,
                condition);

        SelectConditionStep<Record7<Long, String, String, String, String, String, String>> complexityKindColumns = mkSupplementalColumnDefinitionQuery(
                EntityKind.COMPLEXITY_KIND,
                cxk,
                cxk.ID,
                cxk.NAME,
                cxk.DESCRIPTION,
                condition);

        SelectConditionStep<Record7<Long, String, String, String, String, String, String>> involvementKindColumns = mkSupplementalColumnDefinitionQuery(
                EntityKind.INVOLVEMENT_KIND,
                ik,
                ik.ID,
                ik.NAME,
                ik.DESCRIPTION,
                condition);

        SelectConditionStep<Record7<Long, String, String, String, String, String, String>> surveyQuestionColumns = mkSupplementalColumnDefinitionQuery(
                EntityKind.SURVEY_QUESTION,
                sq,
                sq.ID,
                sq.QUESTION_TEXT,
                sq.HELP_TEXT,
                condition);

        SelectConditionStep<Record7<Long, String, String, String, String, String, String>> appGroupColumns = mkSupplementalColumnDefinitionQuery(
                EntityKind.APP_GROUP,
                ag,
                ag.ID,
                ag.NAME,
                ag.DESCRIPTION,
                condition);

        SelectConditionStep<Record7<Long, String, String, String, String, String, String>> surveyMetaColumns = mkSupplementalColumnDefinitionQuery(
                EntityKind.SURVEY_TEMPLATE,
                st,
                st.ID,
                st.NAME,
                st.DESCRIPTION,
                condition);

        SelectConditionStep<Record7<Long, String, String, String, String, String, String>> applicationMetaColumns = mkSupplementalColumnDefinitionQuery(
                EntityKind.APPLICATION,
                a,
                a.ID,
                a.NAME,
                a.DESCRIPTION,
                condition);

        SelectConditionStep<Record7<Long, String, String, String, String, String, String>> changeInitiativeMetaColumns = mkSupplementalColumnDefinitionQuery(
                EntityKind.CHANGE_INITIATIVE,
                ci,
                ci.ID,
                ci.NAME,
                ci.DESCRIPTION,
                condition);

        SelectConditionStep<Record7<Long, String, String, String, String, String, String>> orgUnitMetaColumns = mkSupplementalColumnDefinitionQuery(
                EntityKind.ORG_UNIT,
                ou,
                ou.ID,
                ou.NAME,
                ou.DESCRIPTION,
                condition);

        SelectConditionStep<Record7<Long, String, String, String, String, String, String>> dataTypeColumns = mkSupplementalColumnDefinitionQuery(
                EntityKind.DATA_TYPE,
                dt,
                dt.ID,
                dt.NAME,
                dt.DESCRIPTION,
                condition);

        SelectConditionStep<Record7<Long, String, String, String, String, String, String>> entityStatisticColumns = mkSupplementalColumnDefinitionQuery(
                EntityKind.ENTITY_STATISTIC,
                esd,
                esd.ID,
                esd.NAME,
                esd.DESCRIPTION,
                condition);

        SelectConditionStep<Record7<Long, String, String, String, String, String, String>> tagColumns = mkEntityDescriptorColumnDefinitionQry(
                EntityKind.TAG,
                "Tags",
                "Tags associated to this entity",
                condition);

        SelectConditionStep<Record7<Long, String, String, String, String, String, String>> aliasColumns = mkEntityDescriptorColumnDefinitionQry(
                EntityKind.ENTITY_ALIAS,
                "Aliases",
                "Aliases associated to this entity",
                condition);

        SelectConditionStep<Record7<Long, String, String, String, String, String, String>> attestationColumns = mkAttestationColumnDefinitionQuery(
                condition);

        SelectConditionStep<Record7<Long, String, String, String, String, String, String>> measurableHierarchyColumns = mkMeasurableHierarchyColumnDefinitionQuery(
                condition);

        Table<Record7<Long, String, String, String, String, String, String>> extras = assessmentDefinitionColumns
                .unionAll(measurableColumns)
                .unionAll(costKindColumns)
                .unionAll(complexityKindColumns)
                .unionAll(involvementKindColumns)
                .unionAll(surveyQuestionColumns)
                .unionAll(appGroupColumns)
                .unionAll(surveyMetaColumns)
                .unionAll(applicationMetaColumns)
                .unionAll(changeInitiativeMetaColumns)
                .unionAll(orgUnitMetaColumns)
                .unionAll(dataTypeColumns)
                .unionAll(attestationColumns)
                .unionAll(tagColumns)
                .unionAll(aliasColumns)
                .unionAll(measurableHierarchyColumns)
                .unionAll(entityStatisticColumns)
                .asTable("extras");

        return dsl
                .select(extras.fields())
                .select(fieldsWithout(rgfcd, rgfcd.ID))
                .select(rgcd.fields())
                .from(rg)
                .innerJoin(rgcd).on(rg.ID.eq(rgcd.REPORT_GRID_ID))
                .innerJoin(rgfcd).on(rgfcd.GRID_COLUMN_ID.eq(rgcd.ID))
                .innerJoin(extras).on(extras.field(rgfcd.GRID_COLUMN_ID).eq(rgfcd.GRID_COLUMN_ID))
                .where(condition)
                .orderBy(rgcd.POSITION, DSL.field("name", String.class))
                .fetch(r -> {
                    EntityFieldReference entityFieldReference = ofNullable(r.get(rgfcd.ENTITY_FIELD_REFERENCE_ID))
                            .map(fieldReferenceId -> ImmutableEntityFieldReference.builder()
                                    .id(fieldReferenceId)
                                    .fieldName(r.get(efr.FIELD_NAME))
                                    .entityKind(EntityKind.valueOf(r.get(efr.ENTITY_KIND)))
                                    .displayName(r.get(efr.DISPLAY_NAME))
                                    .description(r.get(efr.DESCRIPTION))
                                    .build())
                            .orElse(null);

                    EntityKind columnQualifierKind = ofNullable(r.get(rgfcd.COLUMN_QUALIFIER_KIND))
                            .map(EntityKind::valueOf)
                            .orElse(null);

                    EntityKind columnEntityKind = EntityKind.valueOf(r.get(rgfcd.COLUMN_ENTITY_KIND));

                    return ImmutableReportGridFixedColumnDefinition.builder()
                            .id(r.get(rgfcd.ID))
                            .columnEntityId(r.get(rgfcd.COLUMN_ENTITY_ID))
                            .columnEntityKind(columnEntityKind)
                            .columnName(r.get("name", String.class))
                            .columnDescription(r.get("desc", String.class))
                            .displayName(r.get(rgfcd.DISPLAY_NAME))
                            .position(r.get(rgcd.POSITION))
                            .additionalColumnOptions(AdditionalColumnOptions.parseColumnOptions(r.get(rgfcd.ADDITIONAL_COLUMN_OPTIONS)))
                            .entityFieldReference(entityFieldReference)
                            .columnQualifierKind(columnQualifierKind)
                            .columnQualifierId(r.get(rgfcd.COLUMN_QUALIFIER_ID))
                            .externalId(Optional.ofNullable(r.get(rgfcd.EXTERNAL_ID)))
                            .gridColumnId(r.get(extras.field(rgfcd.GRID_COLUMN_ID)))
                            .build();
                });
    }


    private List<ReportGridDerivedColumnDefinition> getDerivedColumnDefinitions(Condition condition) {
        return dsl
                .select(rgdcd.fields())
                .select(rgcd.fields())
                .from(rg)
                .innerJoin(rgcd).on(rg.ID.eq(rgcd.REPORT_GRID_ID))
                .innerJoin(rgdcd).on(rgdcd.GRID_COLUMN_ID.eq(rgcd.ID))
                .where(condition)
                .orderBy(rgcd.POSITION, rgdcd.DISPLAY_NAME)
                .fetch(r -> ImmutableReportGridDerivedColumnDefinition.builder()
                        .id(r.get(rgdcd.ID))
                        .displayName(r.get(rgdcd.DISPLAY_NAME))
                        .position(r.get(rgcd.POSITION))
                        .externalId(Optional.ofNullable(r.get(rgdcd.EXTERNAL_ID)))
                        .columnDescription(r.get(rgdcd.COLUMN_DESCRIPTION))
                        .derivationScript(r.get(rgdcd.DERIVATION_SCRIPT))
                        .gridColumnId(r.get(rgdcd.GRID_COLUMN_ID))
                        .build());
    }


    private SelectConditionStep<Record7<Long, String, String, String, String, String, String>> mkAttestationColumnDefinitionQuery(Condition condition) {

        SelectJoinStep<Record4<Long, String, String, String>> dynamic = dsl
                .select(mc.ID,
                        DSL.inline(EntityKind.MEASURABLE_CATEGORY.name()).as("kind"),
                        DSL.concat(mc.NAME, " Attestation"),
                        DSL.concat(mc.NAME, ": Last attestation"))
                .from(mc);

        Row4<Long, String, String, String> lfRow = DSL.row(
                DSL.castNull(DSL.field("id", Long.class)),
                DSL.inline(EntityKind.LOGICAL_DATA_FLOW.name()),
                DSL.inline("Logical Flow Attestation"),
                DSL.inline("Logical Flow Attestation"));

        Row4<Long, String, String, String> pfRow = DSL.row(
                DSL.castNull(DSL.field("id", Long.class)),
                DSL.inline(EntityKind.PHYSICAL_FLOW.name()),
                DSL.inline("Physical Flow Attestation"),
                DSL.inline("Physical Flow Attestation"));

        Table<Record4<Long, String, String, String>> fixed = DSL
                .values(lfRow, pfRow)
                .asTable();

        Table<Record4<Long, String, String, String>> possible = dsl
                .select(fixed.field("id", Long.class),
                        fixed.field("kind", String.class),
                        fixed.field("name", String.class),
                        fixed.field("description", String.class))
                .from(fixed)
                .unionAll(dynamic)
                .asTable("possible", "id", "kind", "name", "description");

        Condition colDefToPossibleJoinCond = rgfcd.COLUMN_QUALIFIER_KIND.eq(possible.field("kind", String.class))
                .and(rgfcd.COLUMN_QUALIFIER_ID.isNull()
                        .or(rgfcd.COLUMN_QUALIFIER_ID.eq(possible.field("id", Long.class))));

        return dsl
                .select(rgfcd.GRID_COLUMN_ID,
                        possible.field("name", String.class),
                        possible.field("description", String.class),
                        efr.ENTITY_KIND,
                        efr.DISPLAY_NAME,
                        efr.DESCRIPTION,
                        efr.FIELD_NAME)
                .from(rgfcd)
                .innerJoin(rgcd).on(rgfcd.GRID_COLUMN_ID.eq(rgcd.ID))
                .innerJoin(rg).on(rg.ID.eq(rgcd.REPORT_GRID_ID))
                .innerJoin(possible).on(colDefToPossibleJoinCond)
                .leftJoin(efr).on(rgfcd.ENTITY_FIELD_REFERENCE_ID.eq(efr.ID))
                .where(condition)
                .and(rgfcd.COLUMN_ENTITY_KIND.eq(EntityKind.ATTESTATION.name()));
    }


    /**
     * 1) col def id,
     * 2) derived name,
     * 3) derived desc,
     * 4) entity field kind,
     * 5) entity field id,
     * 6) entity field desc,
     * 7) entity field name,
     */
    private SelectConditionStep<Record7<Long, String, String, String, String, String, String>> mkSupplementalColumnDefinitionQuery(EntityKind entityKind,
                                                                                                                                   Table<?> t,
                                                                                                                                   TableField<? extends Record, Long> idField,
                                                                                                                                   TableField<? extends Record, String> nameField,
                                                                                                                                   TableField<? extends Record, String> descriptionField,
                                                                                                                                   Condition reportCondition) {
        Field<String> name = DSL.coalesce(nameField, DSL.val(entityKind.prettyName())).as("name");
        Field<String> desc = descriptionField.as("desc");

        return dsl
                .select(rgfcd.GRID_COLUMN_ID,
                        name,
                        desc,
                        efr.ENTITY_KIND,
                        efr.DISPLAY_NAME,
                        efr.DESCRIPTION,
                        efr.FIELD_NAME)
                .from(rgfcd)
                .innerJoin(rgcd).on(rgfcd.GRID_COLUMN_ID.eq(rgcd.ID))
                .innerJoin(rg).on(rg.ID.eq(rgcd.REPORT_GRID_ID))
                .leftJoin(t).on(idField.eq(rgfcd.COLUMN_ENTITY_ID).and(rgfcd.COLUMN_ENTITY_KIND.eq(entityKind.name())))
                .leftJoin(efr).on(rgfcd.ENTITY_FIELD_REFERENCE_ID.eq(efr.ID))
                .where(reportCondition)
                .and(rgfcd.COLUMN_ENTITY_KIND.eq(entityKind.name()));
    }


    private SelectConditionStep<Record7<Long, String, String, String, String, String, String>> mkMeasurableHierarchyColumnDefinitionQuery(Condition reportCondition) {
        Condition hasQualifier = rgfcd.COLUMN_QUALIFIER_ID.isNotNull();
        Field<String> nameField = DSL.when(hasQualifier, DSL.concat(mc.NAME, DSL.val("/"), m.NAME)).otherwise(mc.NAME).as("name");

        return dsl
                .select(rgfcd.GRID_COLUMN_ID,
                        nameField,
                        mc.DESCRIPTION,
                        efr.ENTITY_KIND,
                        efr.DISPLAY_NAME,
                        efr.DESCRIPTION,
                        efr.FIELD_NAME)
                .from(rgfcd)
                .innerJoin(rgcd).on(rgfcd.GRID_COLUMN_ID.eq(rgcd.ID))
                .innerJoin(rg).on(rg.ID.eq(rgcd.REPORT_GRID_ID))
                .innerJoin(mc).on(rgfcd.COLUMN_ENTITY_ID.eq(mc.ID)
                        .and(rgfcd.COLUMN_ENTITY_KIND.eq(EntityKind.MEASURABLE_CATEGORY.name())))
                .leftJoin(m).on(rgfcd.COLUMN_QUALIFIER_ID.eq(m.ID).and(rgfcd.COLUMN_QUALIFIER_KIND.eq(EntityKind.MEASURABLE.name())))
                .leftJoin(efr).on(rgfcd.ENTITY_FIELD_REFERENCE_ID.eq(efr.ID))
                .where(reportCondition);
    }

    private SelectConditionStep<Record7<Long, String, String, String, String, String, String>> mkEntityDescriptorColumnDefinitionQry(EntityKind entityKind,
                                                                                                                                     String columnName,
                                                                                                                                     String columnDescription,
                                                                                                                                     Condition reportCondition) {
        return dsl
                .select(rgfcd.GRID_COLUMN_ID,
                        DSL.val(columnName),
                        DSL.val(columnDescription),
                        efr.ENTITY_KIND,
                        efr.DISPLAY_NAME,
                        efr.DESCRIPTION,
                        efr.FIELD_NAME)
                .from(rgfcd)
                .innerJoin(rgcd).on(rgfcd.GRID_COLUMN_ID.eq(rgcd.ID))
                .innerJoin(rg).on(rg.ID.eq(rgcd.REPORT_GRID_ID))
                .leftJoin(efr).on(rgfcd.ENTITY_FIELD_REFERENCE_ID.eq(efr.ID))
                .where(reportCondition)
                .and(rgfcd.COLUMN_ENTITY_KIND.eq(entityKind.name()));
    }


    private Set<ReportGridCell> findCellDataByGridCondition(Condition gridCondition,
                                                            GenericSelector genericSelector) {

        ReportGridDefinition gridDefn = getGridDefinitionByCondition(gridCondition);

        if (gridDefn == null) {
            return emptySet();

        } else {

            Map<Boolean, Collection<ReportGridFixedColumnDefinition>> gridDefinitionsByContainingFieldRef = groupBy(
                    gridDefn.fixedColumnDefinitions(),
                    d -> d.entityFieldReference() == null);

            Collection<ReportGridFixedColumnDefinition> simpleGridDefs = gridDefinitionsByContainingFieldRef.getOrDefault(true, emptySet());
            Collection<ReportGridFixedColumnDefinition> complexGridDefs = gridDefinitionsByContainingFieldRef.getOrDefault(false, emptySet());

            // SIMPLE GRID DEFS

            Map<EntityKind, Collection<ReportGridFixedColumnDefinition>> colsByKind = groupBy(
                    simpleGridDefs,
                    ReportGridFixedColumnDefinition::columnEntityKind);

            Map<AdditionalColumnOptions, Collection<ReportGridFixedColumnDefinition>> measurableColumnsByRollupKind = groupBy(
                    colsByKind.getOrDefault(EntityKind.MEASURABLE, emptySet()),
                    ReportGridFixedColumnDefinition::additionalColumnOptions);

            Map<Boolean, Collection<ReportGridFixedColumnDefinition>> dataTypeColumnsByIsExact = groupBy(
                    colsByKind.getOrDefault(EntityKind.DATA_TYPE, emptySet()),
                    d -> d.additionalColumnOptions() == AdditionalColumnOptions.NONE);


            // COMPLEX GRID DEFS

            Map<Long, EntityFieldReference> fieldReferencesById = dsl
                    .select(efr.fields())
                    .from(efr)
                    .where(efr.ID.in(map(complexGridDefs, r -> r.entityFieldReference().id().get())))
                    .fetchMap(
                            r -> r.get(efr.ID),
                            r -> ImmutableEntityFieldReference.builder()
                                    .id(r.get(efr.ID))
                                    .entityKind(EntityKind.valueOf(r.get(efr.ENTITY_KIND)))
                                    .fieldName(r.get(efr.FIELD_NAME))
                                    .displayName(r.get(efr.DISPLAY_NAME))
                                    .description(r.get(efr.DESCRIPTION))
                                    .build());

            Map<EntityKind, Set<Tuple2<ReportGridFixedColumnDefinition, EntityFieldReference>>> complexColsByKind = complexGridDefs
                    .stream()
                    .map(d -> tuple(d, fieldReferencesById.get(d.entityFieldReference().id().get())))
                    .collect(groupingBy(t -> t.v2.entityKind(), toSet()));


            return union(
                    fetchAssessmentData(genericSelector, colsByKind.get(EntityKind.ASSESSMENT_DEFINITION)),
                    fetchInvolvementData(genericSelector, colsByKind.get(EntityKind.INVOLVEMENT_KIND)),
                    fetchCostData(genericSelector, colsByKind.get(EntityKind.COST_KIND)),
                    fetchComplexityData(genericSelector, colsByKind.get(EntityKind.COMPLEXITY_KIND)),
                    fetchSummaryMeasurableData(
                            genericSelector,
                            measurableColumnsByRollupKind.getOrDefault(AdditionalColumnOptions.PICK_HIGHEST, emptySet()),
                            measurableColumnsByRollupKind.getOrDefault(AdditionalColumnOptions.PICK_LOWEST, emptySet())),
                    fetchExactMeasurableData(genericSelector, measurableColumnsByRollupKind.get(AdditionalColumnOptions.NONE)),
                    fetchSurveyQuestionResponseData(genericSelector, colsByKind.get(EntityKind.SURVEY_QUESTION)),
                    fetchAppGroupData(genericSelector, colsByKind.get(EntityKind.APP_GROUP)),
                    fetchApplicationFieldReferenceData(genericSelector, complexColsByKind.get(EntityKind.APPLICATION)),
                    fetchExactDataTypeData(genericSelector, dataTypeColumnsByIsExact.get(Boolean.TRUE)),
                    fetchSummaryDataTypeData(genericSelector, dataTypeColumnsByIsExact.get(Boolean.FALSE)),
                    fetchSurveyFieldReferenceData(genericSelector, complexColsByKind.get(EntityKind.SURVEY_INSTANCE)),
                    fetchChangeInitiativeFieldReferenceData(genericSelector, complexColsByKind.get(EntityKind.CHANGE_INITIATIVE)),
                    fetchAttestationData(genericSelector, colsByKind.get(EntityKind.ATTESTATION)),
                    fetchOrgUnitFieldReferenceData(genericSelector, complexColsByKind.get(EntityKind.ORG_UNIT)),
                    fetchTagData(genericSelector, colsByKind.get(EntityKind.TAG)),
                    fetchAliasData(genericSelector, colsByKind.get(EntityKind.ENTITY_ALIAS)),
                    fetchMeasurableHierarchyData(genericSelector, colsByKind.get(EntityKind.MEASURABLE_CATEGORY)),
                    fetchEntityStatisticData(genericSelector, colsByKind.get(EntityKind.ENTITY_STATISTIC)));
        }
    }

    public Set<ReportGridCell> fetchMeasurableHierarchyData(GenericSelector genericSelector,
                                                            Collection<ReportGridFixedColumnDefinition> cols) {
        if (isEmpty(cols)) {
            return emptySet();
        } else {

            Map<Tuple2<Long, Long>, Long> colIdsByCategoryIdAndQualifierId = indexBy(
                    cols,
                    c -> tuple(c.columnEntityId(), c.columnQualifierId()),
                    ReportGridFixedColumnDefinition::gridColumnId);

            SelectConditionStep<Record2<Long, Long>> ratingsInScope = DSL
                    .select(mr.ENTITY_ID, mr.MEASURABLE_ID)
                    .from(mr)
                    .where(mr.ENTITY_ID.in(genericSelector.selector()))
                    .and(mr.ENTITY_KIND.eq(genericSelector.kind().name()));


            return colIdsByCategoryIdAndQualifierId
                    .entrySet()
                    .stream()
                    .flatMap(e -> {

                        Tuple2<Long, Long> categoryAndQualifier = e.getKey();
                        Long colId = e.getValue();

                        Condition qualifierCondition = categoryAndQualifier.v2 == null
                                ? DSL.trueCondition()
                                : eh.ANCESTOR_ID.eq(categoryAndQualifier.v2);

                        Map<Tuple3<Long, Object, Long>, List<Tuple2<String, Long>>> measurablesForEachApp = dsl
                                .selectDistinct(
                                        m.MEASURABLE_CATEGORY_ID,
                                        DSL.val(categoryAndQualifier.v2),
                                        m.ID,
                                        m.NAME,
                                        ratingsInScope.field(mr.ENTITY_ID))
                                .from(m)
                                .innerJoin(ratingsInScope).on(m.ID.eq(ratingsInScope.field(mr.MEASURABLE_ID)))
                                .innerJoin(eh).on(m.ID.eq(eh.ID).and(eh.KIND.eq(EntityKind.MEASURABLE.name())))
                                .where(m.MEASURABLE_CATEGORY_ID.eq(categoryAndQualifier.v1))
                                .and(qualifierCondition)
                                .fetchGroups(
                                        r -> tuple(
                                                r.get(m.MEASURABLE_CATEGORY_ID),
                                                null,
                                                r.get(ratingsInScope.field(mr.ENTITY_ID))),
                                        r -> tuple(r.get(m.NAME), r.get(m.ID)));

                        Set<FlatNode<String, Long>> relevantNodes = fetchRelevantNodes(measurablesForEachApp);
                        Map<Long, FlatNode<String, Long>> relevantNodesById = indexBy(relevantNodes, FlatNode::getId);

                        return measurablesForEachApp
                                .entrySet()
                                .stream()
                                .map(entries -> {

                                    String measurableList = join(map(entries.getValue(), t -> t.v1), "; ");
                                    Set<Long> measurableIdsForCell = map(entries.getValue(), t -> t.v2);

                                    String commentString = mkCommentString(relevantNodesById, measurableIdsForCell);

                                    return ImmutableReportGridCell
                                            .builder()
                                            .columnDefinitionId(colId)
                                            .subjectId(entries.getKey().v3)
                                            .textValue(measurableList)
                                            .comment(commentString)
                                            .build();
                                });
                    })
                    .collect(toSet());
        }
    }


    private Set<FlatNode<String, Long>> fetchRelevantNodes(Map<Tuple3<Long, Object, Long>, List<Tuple2<String, Long>>> measurablesForEachApp) {

        Set<Long> mappedMeasurablesForColumn = measurablesForEachApp.values()
                .stream()
                .flatMap(d -> d
                        .stream()
                        .map(t -> t.v2))
                .collect(toSet());

        return dsl
                .select(MEASURABLE.ID, MEASURABLE.PARENT_ID, MEASURABLE.NAME)
                .from(ENTITY_HIERARCHY)
                .innerJoin(MEASURABLE).on(ENTITY_HIERARCHY.ANCESTOR_ID.eq(MEASURABLE.ID))
                .where(ENTITY_HIERARCHY.ID.in(mappedMeasurablesForColumn)
                        .and(ENTITY_HIERARCHY.KIND.eq(EntityKind.MEASURABLE.name())))
                .fetchSet(r -> new FlatNode<>(r.get(MEASURABLE.ID), ofNullable(r.get(MEASURABLE.PARENT_ID)), r.get(MEASURABLE.NAME)));
    }

    private String mkCommentString(Map<Long, FlatNode<String, Long>> relevantNodesById,
                                   Set<Long> measurableIds) {

        Set<FlatNode<String, Long>> allNodesForCell = measurableIds
                .stream()
                .flatMap(mId -> {
                    Set<FlatNode<String, Long>> nodesPerCell = asSet();

                    FlatNode<String, Long> node = relevantNodesById.get(mId);

                    while (node != null && nodesPerCell.add(node)) {
                        node = node
                                .getParentId()
                                .map(relevantNodesById::get)
                                .orElse(null);
                    }

                    return nodesPerCell.stream();
                })
                .collect(toSet());

        Forest<String, Long> forest = toForest(allNodesForCell);

        List<String> comment = asList("<ul>\n");

        forest.getRootNodes()
                .forEach(root -> {
                    addNodeToComment(comment, root);
                });

        comment.add("</ul>");

        return join(comment, "");

    }


    private void addNodeToComment(List<String> comment, Node<String, Long> node) {
        comment.add(format("<li>%s", node.getData()));
        if (!node.getChildren().isEmpty()) {
            comment.add("<ul>");
            node.getChildren()
                    .forEach(c -> addNodeToComment(comment, c));
            comment.add("</ul>");
        }
        comment.add("</li>\n");
    }


    private Set<ReportGridCell> fetchAttestationData(GenericSelector genericSelector,
                                                     Collection<ReportGridFixedColumnDefinition> cols) {
        if (isEmpty(cols)) {
            return emptySet();
        } else {
            Map<Tuple2<EntityKind, Long>, Long> colIdsByQualifierKindAnId = indexBy(
                    cols,
                    c -> tuple(
                            c.columnQualifierKind(),
                            c.columnQualifierId()),
                    ReportGridFixedColumnDefinition::gridColumnId);

            Condition colConds = DSL.or(map(
                    cols,
                    c -> att_r.ATTESTED_ENTITY_KIND.eq(c.columnQualifierKind().name())
                            .and(c.columnQualifierId() == null
                                    ? DSL.trueCondition()
                                    : att_r.ATTESTED_ENTITY_ID.eq(c.columnQualifierId()))));

            SelectConditionStep<Record7<String, Long, String, Long, Timestamp, String, Integer>> rawAttestationData = dsl
                    .select(
                        att_i.PARENT_ENTITY_KIND.as("ref_k"),
                        att_i.PARENT_ENTITY_ID.as("ref_i"),
                        att_r.ATTESTED_ENTITY_KIND.as("att_k"),
                        att_r.ATTESTED_ENTITY_ID.as("att_i"),
                        att_i.ATTESTED_AT.as("att_at"),
                        att_i.ATTESTED_BY.as("att_by"),
                        DSL.rowNumber().over(DSL
                                .partitionBy(
                                        att_i.PARENT_ENTITY_KIND,
                                        att_i.PARENT_ENTITY_ID,
                                        att_r.ATTESTED_ENTITY_KIND,
                                        att_r.ATTESTED_ENTITY_ID)
                                .orderBy(att_i.ATTESTED_AT.desc())).as("latest"))
                        .from(att_i)
                        .innerJoin(att_r).on(att_i.ATTESTATION_RUN_ID.eq(att_r.ID))
                        .where(att_i.PARENT_ENTITY_KIND.eq(DSL.inline(genericSelector.kind().name())))
                        .and(att_i.PARENT_ENTITY_ID.in(genericSelector.selector()))
                        .and(att_i.ATTESTED_AT.isNotNull())
                        .and(colConds);

            SelectConditionStep<Record> latestAttestationData = dsl
                    .select(rawAttestationData.fields())
                    .from(rawAttestationData.asTable())
                    .where(rawAttestationData.field("latest", Integer.class).eq(1));

            return latestAttestationData
                    .fetchSet(r -> {
                        Long colId = colIdsByQualifierKindAnId.get(
                                tuple(
                                        EntityKind.valueOf(r.get("att_k", String.class)),
                                        r.get("att_i", Long.class)));

                        Timestamp attAt = r.get("att_at", Timestamp.class);

                        Tuple2<String, String> optionCodeAndText = determineOptionForAttestation(attAt);

                        return ImmutableReportGridCell
                                .builder()
                                .columnDefinitionId(colId)
                                .subjectId(r.get("ref_i", Long.class))
                                .dateTimeValue(toLocalDateTime(attAt))
                                .comment(format("Attested by: %s", r.get("att_by", String.class)))
                                .optionCode(optionCodeAndText.v1)
                                .optionText(optionCodeAndText.v2)
                                .build();
                    });
        }
    }

    private Tuple2<String, String> determineOptionForAttestation(Timestamp attAt) {

        LocalDateTime attestationDate = toLocalDateTime(attAt);

        LocalDateTime oneMonthAgo = DateTimeUtilities.nowUtc().minusMonths(1);
        LocalDateTime threeMonthsAgo = DateTimeUtilities.nowUtc().minusMonths(3);
        LocalDateTime sixMonthsAgo = DateTimeUtilities.nowUtc().minusMonths(6);
        LocalDateTime yearAgo = DateTimeUtilities.nowUtc().minusMonths(12);

        if (attestationDate.isAfter(oneMonthAgo)) {
            return tuple("<1M", "<1 Month");
        } else if (attestationDate.isAfter(threeMonthsAgo)) {
            return tuple("<3M", "1-3 Months");
        } else if (attestationDate.isAfter(sixMonthsAgo)) {
            return tuple("<6M", "3-6 Months");
        } else if (attestationDate.isAfter(yearAgo)) {
            return tuple("<1Y", "6-12 Months");
        } else {
            return tuple(">1Y", ">1 Year");
        }
    }


    private Set<ReportGridCell> fetchExactDataTypeData(GenericSelector genericSelector,
                                                       Collection<ReportGridFixedColumnDefinition> cols) {
        if (isEmpty(cols)) {
            return Collections.emptySet();
        } else {
            Map<Long, Long> dataTypeIdToDefIdMap = indexBy(
                    cols,
                    ReportGridFixedColumnDefinition::columnEntityId,
                    ReportGridFixedColumnDefinition::gridColumnId);
            return dsl
                    .select(dtu.ENTITY_ID,
                            dtu.DATA_TYPE_ID,
                            dtu.USAGE_KIND)
                    .from(dtu)
                    .where(dtu.ENTITY_KIND.eq(EntityKind.APPLICATION.name()))
                    .and(dtu.ENTITY_ID.in(genericSelector.selector()))
                    .and(dtu.DATA_TYPE_ID.in(dataTypeIdToDefIdMap.keySet()))
                    .fetchGroups(r -> tuple(
                            r.get(dtu.ENTITY_ID),
                            r.get(dtu.DATA_TYPE_ID)))
                    .entrySet()
                    .stream()
                    .map(entry -> {
                        Set<UsageKind> usageKinds = map(
                                entry.getValue(),
                                d -> UsageKind.valueOf(d.get(dtu.USAGE_KIND)));

                        return mkDataTypeUsageCell(
                                dataTypeIdToDefIdMap.get(entry.getKey().v2),
                                entry.getKey().v1,
                                usageKinds);
                    })
                    .collect(toSet());
        }
    }


    private Set<ReportGridCell> fetchSummaryDataTypeData(GenericSelector genericSelector,
                                                         Collection<ReportGridFixedColumnDefinition> cols) {
        if (isEmpty(cols)) {
            return Collections.emptySet();
        } else {

            Map<Long, Long> dataTypeIdToDefIdMap = indexBy(
                    cols,
                    ReportGridFixedColumnDefinition::columnEntityId,
                    ReportGridFixedColumnDefinition::gridColumnId);

            return dsl
                    .select(dtu.ENTITY_ID,
                            dtu.DATA_TYPE_ID,
                            dtu.USAGE_KIND,
                            eh.ANCESTOR_ID)
                    .from(dtu)
                    .innerJoin(eh).on(eh.ID.eq(dtu.DATA_TYPE_ID)
                            .and(eh.KIND.eq(EntityKind.DATA_TYPE.name())))
                    .where(dtu.ENTITY_KIND.eq(EntityKind.APPLICATION.name()))
                    .and(dtu.ENTITY_ID.in(genericSelector.selector()))
                    .and(eh.ANCESTOR_ID.in(dataTypeIdToDefIdMap.keySet()))
                    .fetchGroups(r -> tuple(
                            r.get(dtu.ENTITY_ID),
                            r.get(eh.ANCESTOR_ID)))
                    .entrySet()
                    .stream()
                    .map(entry -> {
                        Set<UsageKind> usageKinds = map(
                                entry.getValue(),
                                d -> UsageKind.valueOf(d.get(dtu.USAGE_KIND)));

                        return mkDataTypeUsageCell(
                                dataTypeIdToDefIdMap.get(entry.getKey().v2),
                                entry.getKey().v1,
                                usageKinds);
                    })
                    .collect(toSet());
        }
    }


    private ImmutableReportGridCell mkDataTypeUsageCell(Long colDefId,
                                                        Long subjectId,
                                                        Set<UsageKind> usageKinds) {
        UsageKind derivedUsage = deriveUsage(usageKinds);

        return ImmutableReportGridCell
                .builder()
                .subjectId(subjectId)
                .columnDefinitionId(colDefId)
                .textValue(derivedUsage.displayName())
                .optionCode(derivedUsage.name())
                .optionText(derivedUsage.displayName())
                .build();
    }


    private Set<ReportGridCell> fetchAppGroupData(GenericSelector genericSelector,
                                                  Collection<ReportGridFixedColumnDefinition> cols) {
        if (isEmpty(cols)) {
            return emptySet();
        } else {
            Map<Long, Long> groupIdToDefIdMap = indexBy(
                    cols,
                    ReportGridFixedColumnDefinition::columnEntityId,
                    ReportGridFixedColumnDefinition::gridColumnId);

            SelectOrderByStep<Record3<Long, Long, Timestamp>> appGroupInfoSelect = determineAppGroupQuery(
                    genericSelector,
                    groupIdToDefIdMap.keySet());

            return dsl
                    .fetch(appGroupInfoSelect)
                    .stream()
                    .map(r -> {
                        Long subjectId = r.get("subject_id", Long.class);
                        Timestamp created_at = r.get("created_at", Timestamp.class);

                        return ImmutableReportGridCell
                                .builder()
                                .subjectId(subjectId)
                                .columnDefinitionId(groupIdToDefIdMap.get(r.get(ag.ID)))
                                .textValue("Y")
                                .comment(format("Created at: %s", toLocalDate(created_at).toString()))
                                .build();
                    })
                    .collect(toSet());
        }
    }


    private SelectOrderByStep<Record3<Long, Long, Timestamp>> determineAppGroupQuery(GenericSelector selector, Set<Long> requiredAppGroupIds) {

        switch (selector.kind()) {
            case APPLICATION:
                return mkApplicationAppGroupSelect(selector, requiredAppGroupIds);
            case CHANGE_INITIATIVE:
                return mkChangeInitiativeAppGroupSelect(selector, requiredAppGroupIds);
            default:
                throw new UnsupportedOperationException("Cannot return app group selector for kind: " + selector.kind().name());
        }

    }


    private SelectOrderByStep<Record3<Long, Long, Timestamp>> mkChangeInitiativeAppGroupSelect(GenericSelector selector, Set<Long> requiredAppGroupIds) {

        SelectConditionStep<Record3<Long, Long, Timestamp>> groupASelect = dsl
                .select(ci.ID.as("subject_id"),
                        ag.ID,
                        er.LAST_UPDATED_AT.as("created_at"))
                .from(ag)
                .innerJoin(er).on(ag.ID.eq(er.ID_A))
                .innerJoin(ci).on(er.ID_B.eq(ci.ID))
                .where(er.KIND_A.eq(EntityKind.APP_GROUP.name())
                        .and(er.KIND_B.eq(EntityKind.CHANGE_INITIATIVE.name())))
                .and(ci.ID.in(selector.selector()))
                .and(ag.ID.in(requiredAppGroupIds));

        SelectConditionStep<Record3<Long, Long, Timestamp>> groupBSelect = dsl
                .select(ci.ID.as("subject_id"),
                        ag.ID,
                        er.LAST_UPDATED_AT.as("created_at"))
                .from(ag)
                .innerJoin(er).on(ag.ID.eq(er.ID_B))
                .innerJoin(ci).on(er.ID_A.eq(ci.ID))
                .where(er.KIND_B.eq(EntityKind.APP_GROUP.name())
                        .and(er.KIND_A.eq(EntityKind.CHANGE_INITIATIVE.name())))
                .and(ci.ID.in(selector.selector()))
                .and(ag.ID.in(requiredAppGroupIds));


        return groupASelect.union(groupBSelect);
    }


    private SelectOrderByStep<Record3<Long, Long, Timestamp>> mkApplicationAppGroupSelect(GenericSelector selector, Set<Long> requiredAppGroupIds) {

        SelectConditionStep<Record3<Long, Long, Timestamp>> directSelect = DSL
                .select(
                        age.APPLICATION_ID.as("subject_id"),
                        ag.ID,
                        age.CREATED_AT.as("created_at"))
                .from(ag)
                .innerJoin(age).on(ag.ID.eq(age.GROUP_ID))
                .where(age.APPLICATION_ID.in(selector.selector()))
                .and(ag.ID.in(requiredAppGroupIds));

        SelectConditionStep<Record3<Long, Long, Timestamp>> indirectSelect = DSL
                .select(
                        a.ID.as("subject_id"),
                        ag.ID,
                        agoe.CREATED_AT.as("created_at"))
                .from(ag)
                .innerJoin(agoe).on(ag.ID.eq(agoe.GROUP_ID))
                .innerJoin(eh).on(agoe.ORG_UNIT_ID.eq(eh.ANCESTOR_ID)
                        .and(eh.KIND.eq(EntityKind.ORG_UNIT.name())))
                .innerJoin(a).on(eh.ID.eq(a.ORGANISATIONAL_UNIT_ID))
                .where(a.ID.in(selector.selector()))
                .and(ag.ID.in(requiredAppGroupIds));

        return directSelect.union(indirectSelect);
    }


    private Set<ReportGridCell> fetchApplicationFieldReferenceData(GenericSelector selector,
                                                                   Set<Tuple2<ReportGridFixedColumnDefinition, EntityFieldReference>> requiredApplicationColumns) {

        if (isEmpty(requiredApplicationColumns)) {
            return emptySet();
        } else {

            Set<String> fields = map(requiredApplicationColumns, d -> d.v2.fieldName());

            Map<String, ReportGridFixedColumnDefinition> columnDefinitionsByFieldReference = requiredApplicationColumns
                    .stream()
                    .collect(toMap(k -> k.v2.fieldName(), v -> v.v1));

            return dsl
                    .select(APPLICATION.fields())
                    .from(APPLICATION)
                    .where(APPLICATION.ID.in(selector.selector()))
                    .fetch()
                    .stream()
                    .flatMap(appRecord -> fields
                            .stream()
                            .map(fieldName -> {
                                ReportGridFixedColumnDefinition colDefn = columnDefinitionsByFieldReference.get(fieldName);

                                Field<?> field = APPLICATION.field(fieldName);
                                Object rawValue = appRecord.get(field);

                                String textValue = isTimestampField(field)
                                        ? String.valueOf(DateTimeUtilities.toLocalDate((Timestamp) rawValue))
                                        : String.valueOf(rawValue);

                                if (rawValue == null) {
                                    return null;
                                }

                                return ImmutableReportGridCell
                                        .builder()
                                        .subjectId(appRecord.get(APPLICATION.ID))
                                        .columnDefinitionId(colDefn.gridColumnId())
                                        .textValue(textValue)
                                        .build();
                            }))
                    .filter(Objects::nonNull)
                    .collect(toSet());
        }
    }


    private boolean isTimestampField(Field<?> field) {
        DataType<?> dataType = field.getDataType();
        int sqlType = dataType.getSQLType();
        return sqlType == Types.TIMESTAMP;
    }


    private Set<ReportGridCell> fetchChangeInitiativeFieldReferenceData(GenericSelector selector,
                                                                        Set<Tuple2<ReportGridFixedColumnDefinition, EntityFieldReference>> requiredChangeInitiativeColumns) {

        if (isEmpty(requiredChangeInitiativeColumns)) {
            return emptySet();
        } else {

            Set<String> fields = map(requiredChangeInitiativeColumns, d -> d.v2.fieldName());

            Map<String, ReportGridFixedColumnDefinition> columnDefinitionsByFieldReference = requiredChangeInitiativeColumns
                    .stream()
                    .collect(toMap(k -> k.v2.fieldName(), v -> v.v1));

            ChangeInitiative ci = CHANGE_INITIATIVE.as("ci");
            ChangeInitiative ci_parent = CHANGE_INITIATIVE.as("ci_parent");

            return dsl
                    .select(ci.fields())
                    .select(ci_parent.EXTERNAL_ID.as("parent_external_id"))
                    .from(ci)
                    .leftJoin(ci_parent).on(ci.PARENT_ID.eq(ci_parent.ID))
                    .where(ci.ID.in(selector.selector()))
                    .fetch()
                    .stream()
                    .flatMap(ciRecord -> fields
                            .stream()
                            .map(fieldName -> {
                                ReportGridFixedColumnDefinition colDefn = columnDefinitionsByFieldReference.get(fieldName);

                                Object value = ciRecord.get(fieldName);

                                if (value == null) {
                                    return null;
                                }

                                return ImmutableReportGridCell
                                        .builder()
                                        .subjectId(ciRecord.get(CHANGE_INITIATIVE.ID))
                                        .columnDefinitionId(colDefn.gridColumnId())
                                        .textValue(String.valueOf(value))
                                        .build();
                            }))
                    .filter(Objects::nonNull)
                    .collect(toSet());
        }
    }


    private Set<ReportGridCell> fetchSurveyFieldReferenceData(GenericSelector selector,
                                                              Set<Tuple2<ReportGridFixedColumnDefinition, EntityFieldReference>> surveyInstanceInfo) {
        if (isEmpty(surveyInstanceInfo)) {
            return emptySet();
        } else {

            Map<Tuple2<Long, Long>, Long> templateAndFieldRefToDefIdMap = indexBy(
                    surveyInstanceInfo,
                    t -> tuple(t.v1.columnEntityId(), t.v2.id().get()),
                    t -> t.v1.gridColumnId());

            Map<Long, Collection<EntityFieldReference>> fieldReferencesByTemplateId = groupBy(
                    surveyInstanceInfo,
                    r -> r.v1.columnEntityId(),
                    v -> v.v2);

            Set<Long> surveyTemplateIds = fieldReferencesByTemplateId.keySet();


            Field<Long> latestInstance = DSL
                    .firstValue(SURVEY_INSTANCE.ID)
                    .over()
                    .partitionBy(SURVEY_INSTANCE.ENTITY_ID, SURVEY_INSTANCE.ENTITY_KIND, SURVEY_RUN.SURVEY_TEMPLATE_ID)
                    .orderBy(SURVEY_INSTANCE.SUBMITTED_AT.desc().nullsLast())
                    .as("latest_instance");

            Table<Record> surveyInfo = dsl
                    .select(latestInstance)
                    .select(SURVEY_INSTANCE.ID.as("sid"),
                            SURVEY_INSTANCE.STATUS,
                            SURVEY_INSTANCE.APPROVED_AT,
                            SURVEY_INSTANCE.APPROVED_BY,
                            SURVEY_INSTANCE.SUBMITTED_AT,
                            SURVEY_INSTANCE.SUBMITTED_BY,
                            SURVEY_INSTANCE.DUE_DATE,
                            SURVEY_INSTANCE.APPROVAL_DUE_DATE,
                            SURVEY_INSTANCE.ENTITY_ID,
                            SURVEY_INSTANCE.ENTITY_KIND,
                            SURVEY_RUN.ISSUED_ON,
                            SURVEY_INSTANCE.NAME.as("instance_name"),
                            SURVEY_RUN.NAME.as("run_name"))
                    .select(SURVEY_RUN.SURVEY_TEMPLATE_ID)
                    .from(SURVEY_INSTANCE)
                    .innerJoin(SURVEY_RUN).on(SURVEY_INSTANCE.SURVEY_RUN_ID.eq(SURVEY_RUN.ID))
                    .where(SURVEY_INSTANCE.ENTITY_ID.in(selector.selector())
                            .and(SURVEY_INSTANCE.ENTITY_KIND.eq(selector.kind().name())
                                    .and(SURVEY_RUN.SURVEY_TEMPLATE_ID.in(surveyTemplateIds)
                                            .and(SURVEY_INSTANCE.ORIGINAL_INSTANCE_ID.isNull()))))
                    .asTable();


            SelectConditionStep<Record> surveyInfoForLatestInstance = dsl
                    .select(surveyInfo.fields())
                    .from(surveyInfo)
                    .where(surveyInfo.field(latestInstance)
                            .eq(surveyInfo.field("sid", Long.class)));

            return surveyInfoForLatestInstance
                    .fetch()
                    .stream()
                    .flatMap(surveyRecord -> {

                        Long templateId = surveyRecord.get(SURVEY_RUN.SURVEY_TEMPLATE_ID);

                        return fieldReferencesByTemplateId
                                .getOrDefault(templateId, emptySet())
                                .stream()
                                .map(fieldRef -> {

                                    Field<?> field = surveyRecord.field(fieldRef.fieldName());
                                    Object rawValue = surveyRecord.get(fieldRef.fieldName());

                                    String textValue = isTimestampField(field)
                                            ? String.valueOf(DateTimeUtilities.toLocalDate((Timestamp) rawValue))
                                            : String.valueOf(rawValue);

                                    if (rawValue == null) {
                                        return null;
                                    }

                                    return ImmutableReportGridCell
                                            .builder()
                                            .subjectId(surveyRecord.get(SURVEY_INSTANCE.ENTITY_ID))
                                            .columnDefinitionId(templateAndFieldRefToDefIdMap.get(tuple(templateId, fieldRef.id().get()))) // FIX
                                            .textValue(textValue)
                                            .build();
                                });
                    })
                    .filter(Objects::nonNull)
                    .collect(toSet());
        }
    }


    private Set<ReportGridCell> fetchOrgUnitFieldReferenceData(GenericSelector selector,
                                                               Set<Tuple2<ReportGridFixedColumnDefinition, EntityFieldReference>> requiredOrgUnitColumns) {

        if (isEmpty(requiredOrgUnitColumns)) {
            return emptySet();
        } else {

            Set<String> fields = map(requiredOrgUnitColumns, d -> d.v2.fieldName());

            Map<String, ReportGridFixedColumnDefinition> columnDefinitionsByFieldReference = requiredOrgUnitColumns
                    .stream()
                    .collect(toMap(k -> k.v2.fieldName(), v -> v.v1));

            SelectConditionStep<Record> qry = getOrgUnitSelectQuery(selector);

            return qry
                    .fetch()
                    .stream()
                    .flatMap(orgUnitRecord -> fields
                            .stream()
                            .map(fieldName -> {
                                ReportGridFixedColumnDefinition colDefn = columnDefinitionsByFieldReference.get(fieldName);

                                Field<?> field = ORGANISATIONAL_UNIT.field(fieldName);
                                Object rawValue = orgUnitRecord.get(field);

                                if (rawValue == null) {
                                    return null;
                                }

                                return ImmutableReportGridCell
                                        .builder()
                                        .subjectId(orgUnitRecord.get("entityId", Long.class))
                                        .columnDefinitionId(colDefn.gridColumnId())
                                        .textValue(String.valueOf(rawValue))
                                        .build();
                            }))
                    .filter(Objects::nonNull)
                    .collect(toSet());
        }
    }


    private SelectConditionStep<Record> getOrgUnitSelectQuery(GenericSelector selector) {

        SelectConditionStep<Record> appOrgUnitQuery = dsl
                .select(ORGANISATIONAL_UNIT.fields())
                .select(APPLICATION.ID.as("entityId"))
                .from(ORGANISATIONAL_UNIT)
                .innerJoin(APPLICATION)
                .on(ORGANISATIONAL_UNIT.ID.eq(APPLICATION.ORGANISATIONAL_UNIT_ID))
                .where(APPLICATION.ID.in(selector.selector()));

        SelectConditionStep<Record> changeInitiativeOrgUnitQuery = dsl
                .select(ORGANISATIONAL_UNIT.fields())
                .select(CHANGE_INITIATIVE.ID.as("entityId"))
                .from(ORGANISATIONAL_UNIT)
                .innerJoin(CHANGE_INITIATIVE)
                .on(ORGANISATIONAL_UNIT.ID.eq(CHANGE_INITIATIVE.ORGANISATIONAL_UNIT_ID))
                .where(CHANGE_INITIATIVE.ID.in(selector.selector()));

        return selector.kind() == EntityKind.APPLICATION
                ? appOrgUnitQuery
                : changeInitiativeOrgUnitQuery;
    }


    private Set<ReportGridCell> fetchInvolvementData(GenericSelector selector,
                                                     Collection<ReportGridFixedColumnDefinition> cols) {
        if (isEmpty(cols)) {
            return emptySet();
        } else {
            Map<Long, Long> involvementIdToDefIdMap = indexBy(
                    cols,
                    ReportGridFixedColumnDefinition::columnEntityId,
                    ReportGridFixedColumnDefinition::gridColumnId);

            return fromCollection(dsl
                    .select(
                            inv.ENTITY_ID,
                            inv.KIND_ID,
                            p.EMAIL)
                    .from(inv)
                    .innerJoin(p).on(p.EMPLOYEE_ID.eq(inv.EMPLOYEE_ID))
                    .where(inv.ENTITY_KIND.eq(selector.kind().name()))
                    .and(inv.ENTITY_ID.in(selector.selector()))
                    .and(inv.KIND_ID.in(involvementIdToDefIdMap.keySet()))
                    .and(p.IS_REMOVED.isFalse())
                    .fetchSet(r -> ImmutableReportGridCell
                            .builder()
                            .subjectId(r.get(inv.ENTITY_ID))
                            .columnDefinitionId(involvementIdToDefIdMap.get(r.get(inv.KIND_ID)))
                            .textValue(r.get(p.EMAIL))
                            .build())
                    // we now convert to a map so we can merge text values of cells with the same coordinates (appId, entId)
                    .stream()
                    .collect(toMap(
                            c -> tuple(c.subjectId(), c.columnDefinitionId()),
                            identity(),
                            (a, b) -> ImmutableReportGridCell
                                    .copyOf(a)
                                    .withTextValue(a.textValue() + "; " + b.textValue())))
                    // and then we simply return the values of that temporary map.
                    .values());
        }
    }


    private Set<ReportGridCell> fetchTagData(GenericSelector selector,
                                             Collection<ReportGridFixedColumnDefinition> cols) {
        if (isEmpty(cols)) {
            return emptySet();
        } else {

            //Should only be one tags column max
            Optional<Long> tagsColumn = cols
                    .stream()
                    .map(ReportGridFixedColumnDefinition::gridColumnId)
                    .findFirst();

            return tagsColumn
                    .map(columnId -> SetUtilities.<ReportGridCell>fromCollection(dsl
                            .select(tu.ENTITY_ID,
                                    t.NAME)
                            .from(tu)
                            .innerJoin(t).on(t.ID.eq(tu.TAG_ID).and(t.TARGET_KIND.eq(tu.ENTITY_KIND)))
                            .where(tu.ENTITY_KIND.eq(selector.kind().name()))
                            .and(tu.ENTITY_ID.in(selector.selector()))
                            .fetchSet(r -> ImmutableReportGridCell
                                    .builder()
                                    .subjectId(r.get(tu.ENTITY_ID))
                                    .columnDefinitionId(columnId)
                                    .textValue(r.get(t.NAME))
                                    .build())
                            // we now convert to a map so we can merge text values of cells with the same coordinates (appId, entId)
                            .stream()
                            .collect(toMap(
                                    c1 -> tuple(c1.subjectId(), c1.columnDefinitionId()),
                                    identity(),
                                    (a1, b) -> ImmutableReportGridCell
                                            .copyOf(a1)
                                            .withTextValue(a1.textValue() + "; " + b.textValue())))
                            // and then we simply return the values of that temporary map.
                            .values()))
                    .orElse(emptySet());
        }
    }

    private Set<ReportGridCell> fetchAliasData(GenericSelector selector,
                                               Collection<ReportGridFixedColumnDefinition> cols) {
        if (isEmpty(cols)) {
            return emptySet();
        } else {

            //Should only be one alias column max
            Optional<Long> aliasColumn = cols
                    .stream()
                    .map(ReportGridFixedColumnDefinition::gridColumnId)
                    .findFirst();

            return aliasColumn
                    .map(columnId -> SetUtilities.<ReportGridCell>fromCollection(dsl
                            .select(ea.ID,
                                    ea.ALIAS)
                            .from(ea)
                            .where(ea.KIND.eq(selector.kind().name()))
                            .and(ea.ID.in(selector.selector()))
                            .fetchSet(r -> ImmutableReportGridCell
                                    .builder()
                                    .subjectId(r.get(ea.ID))
                                    .columnDefinitionId(columnId)
                                    .textValue(r.get(ea.ALIAS))
                                    .build())
                            // we now convert to a map so we can merge text values of cells with the same coordinates (appId, entId)
                            .stream()
                            .collect(toMap(
                                    c1 -> tuple(c1.subjectId(), c1.columnDefinitionId()),
                                    identity(),
                                    (a1, b) -> ImmutableReportGridCell
                                            .copyOf(a1)
                                            .withTextValue(a1.textValue() + "; " + b.textValue())))
                            // and then we simply return the values of that temporary map.
                            .values()))
                    .orElse(emptySet());
        }
    }

    private Set<ReportGridCell> fetchCostData(GenericSelector selector,
                                              Collection<ReportGridFixedColumnDefinition> cols) {

        if (isEmpty(cols)) {
            return emptySet();
        } else {

            SelectHavingStep<Record2<Long, Integer>> costKindLastestYear = dsl
                    .select(COST.COST_KIND_ID, DSL.max(COST.YEAR).as("latest_year"))
                    .from(COST)
                    .where(dsl.renderInlined(COST.ENTITY_ID.in(selector.selector())
                            .and(COST.ENTITY_KIND.eq(selector.kind().name()))))
                    .groupBy(COST.COST_KIND_ID);

            Condition latestYearForKind = c.COST_KIND_ID.eq(costKindLastestYear.field(COST.COST_KIND_ID))
                    .and(c.YEAR.eq(costKindLastestYear.field("latest_year", Integer.class)));

            Map<Long, Long> costKindIdToDefIdMap = indexBy(
                    cols,
                    ReportGridFixedColumnDefinition::columnEntityId,
                    ReportGridFixedColumnDefinition::gridColumnId);

            return dsl
                    .select(c.ENTITY_ID,
                            c.COST_KIND_ID,
                            c.AMOUNT)
                    .from(c)
                    .innerJoin(costKindLastestYear).on(latestYearForKind)
                    .where(dsl.renderInlined(c.COST_KIND_ID.in(costKindIdToDefIdMap.keySet())
                            .and(c.ENTITY_KIND.eq(selector.kind().name()))
                            .and(c.ENTITY_ID.in(selector.selector()))))
                    .fetchSet(r -> ImmutableReportGridCell.builder()
                            .subjectId(r.get(c.ENTITY_ID))
                            .columnDefinitionId(costKindIdToDefIdMap.get(r.get(c.COST_KIND_ID)))
                            .numberValue(r.get(c.AMOUNT))
                            .build());
        }
    }


    private Set<ReportGridCell> fetchComplexityData(GenericSelector selector,
                                                    Collection<ReportGridFixedColumnDefinition> cols) {

        if (isEmpty(cols)) {
            return emptySet();
        } else {

            Map<Long, Long> complexityKindIdToDefIdMap = indexBy(
                    cols,
                    ReportGridFixedColumnDefinition::columnEntityId,
                    ReportGridFixedColumnDefinition::gridColumnId);

            return dsl
                    .select(cx.ENTITY_ID,
                            cx.COMPLEXITY_KIND_ID,
                            cx.SCORE)
                    .from(cx)
                    .where(dsl.renderInlined(cx.COMPLEXITY_KIND_ID.in(complexityKindIdToDefIdMap.keySet())
                            .and(cx.ENTITY_KIND.eq(selector.kind().name()))
                            .and(cx.ENTITY_ID.in(selector.selector()))))
                    .fetchSet(r -> ImmutableReportGridCell.builder()
                            .subjectId(r.get(cx.ENTITY_ID))
                            .columnDefinitionId(complexityKindIdToDefIdMap.get(r.get(cx.COMPLEXITY_KIND_ID)))
                            .numberValue(r.get(cx.SCORE))
                            .build());
        }
    }


    private Set<ReportGridCell> fetchSummaryMeasurableData(GenericSelector selector,
                                                           Collection<ReportGridFixedColumnDefinition> highCols,
                                                           Collection<ReportGridFixedColumnDefinition> lowCols) {

        if (isEmpty(highCols) && isEmpty(lowCols)) {
            return emptySet();
        }

        Map<Long, Long> highIdToDefIdMap = indexBy(
                highCols,
                ReportGridFixedColumnDefinition::columnEntityId,
                ReportGridFixedColumnDefinition::gridColumnId);

        Map<Long, Long> lowIdToDefIdMap = indexBy(
                lowCols,
                ReportGridFixedColumnDefinition::columnEntityId,
                ReportGridFixedColumnDefinition::gridColumnId);

        Table<Record5<Long, String, Long, Integer, String>> ratingSchemeItems = DSL
                .select(mc.ID.as("mcId"),
                        rsi.CODE.as("rsiCode"),
                        rsi.ID.as("rsiId"),
                        rsi.POSITION.as("rsiPos"),
                        rsi.NAME.as("rsiName"))
                .from(mc)
                .innerJoin(rsi)
                .on(rsi.SCHEME_ID.eq(mc.RATING_SCHEME_ID))
                .asTable("ratingSchemeItems");

        SelectConditionStep<Record5<Long, Long, Long, Integer, String>> ratings = DSL
                .select(
                        m.ID,
                        mr.ENTITY_ID,
                        ratingSchemeItems.field("rsiId", Long.class),
                        ratingSchemeItems.field("rsiPos", Integer.class),
                        ratingSchemeItems.field("rsiName", String.class))
                .from(mr)
                .innerJoin(eh)
                .on(eh.ID.eq(mr.MEASURABLE_ID))
                .and(eh.KIND.eq(EntityKind.MEASURABLE.name()))
                .innerJoin(m)
                .on(eh.ANCESTOR_ID.eq(m.ID))
                .innerJoin(ratingSchemeItems)
                .on(m.MEASURABLE_CATEGORY_ID.eq(ratingSchemeItems.field("mcId", Long.class)))
                .and(mr.RATING.eq(ratingSchemeItems.field("rsiCode", String.class)))
                .where(mr.ENTITY_KIND.eq(selector.kind().name())
                        .and(mr.ENTITY_ID.in(selector.selector()))
                        .and(m.ID.in(union(
                                highIdToDefIdMap.keySet(),
                                lowIdToDefIdMap.keySet()))));

        return dsl
                .resultQuery(dsl.renderInlined(ratings))
                .fetchGroups(
                        r -> tuple(
                                mkRef(selector.kind(), r.get(mr.ENTITY_ID)),
                                r.get(m.ID)),
                        r -> tuple(
                                r.get("rsiId", Long.class),
                                r.get("rsiPos", Integer.class),
                                r.get("rsiName", String.class)))
                .entrySet()
                .stream()
                .map(e -> {

                    Tuple2<EntityReference, Long> entityAndMeasurable = e.getKey();
                    Long measurableId = entityAndMeasurable.v2();
                    long entityId = entityAndMeasurable.v1().id();
                    List<Tuple3<Long, Integer, String>> ratingsForEntityAndMeasurable = e.getValue();

                    ToIntFunction<Tuple3<Long, Integer, String>> compareByPositionAsc = t -> t.v2;
                    ToIntFunction<Tuple3<Long, Integer, String>> compareByPositionDesc = t -> t.v2 * -1;
                    Function<? super Tuple3<Long, Integer, String>, String> compareByName = t -> t.v3;

                    Comparator<Tuple3<Long, Integer, String>> cmp = Comparator
                            .comparingInt(highIdToDefIdMap.keySet().contains(measurableId)
                                    ? compareByPositionAsc
                                    : compareByPositionDesc)
                            .thenComparing(compareByName);

                    return ratingsForEntityAndMeasurable
                            .stream()
                            .min(cmp)
                            .map(t -> ImmutableReportGridCell
                                    .builder()
                                    .subjectId(entityId)
                                    .columnDefinitionId(highIdToDefIdMap.getOrDefault(measurableId, lowIdToDefIdMap.get(measurableId)))
                                    .ratingIdValue(t.v1)
                                    .optionCode(Long.toString(t.v1))
                                    .optionText(t.v3)
                                    .build())
                            .orElse(null);
                })
                .filter(Objects::nonNull)
                .collect(toSet());
    }


    private Set<ReportGridCell> fetchExactMeasurableData(GenericSelector selector,
                                                         Collection<ReportGridFixedColumnDefinition> cols) {

        if (isEmpty(cols)) {
            return emptySet();
        } else {
            Map<Long, Long> measurableIdToDefIdMap = indexBy(
                    cols,
                    ReportGridFixedColumnDefinition::columnEntityId,
                    ReportGridFixedColumnDefinition::gridColumnId);

            SelectConditionStep<Record5<Long, Long, Long, String, String>> qry = dsl
                    .select(mr.ENTITY_ID,
                            mr.MEASURABLE_ID,
                            rsi.ID,
                            rsi.NAME,
                            mr.DESCRIPTION)
                    .from(mr)
                    .innerJoin(m).on(m.ID.eq(mr.MEASURABLE_ID))
                    .innerJoin(mc).on(mc.ID.eq(m.MEASURABLE_CATEGORY_ID))
                    .innerJoin(rsi).on(rsi.CODE.eq(mr.RATING)).and(rsi.SCHEME_ID.eq(mc.RATING_SCHEME_ID))
                    .where(mr.MEASURABLE_ID.in(measurableIdToDefIdMap.keySet()))
                    .and(mr.ENTITY_ID.in(selector.selector()))
                    .and(mr.ENTITY_KIND.eq(selector.kind().name()));

            return dsl
                    .resultQuery(dsl.renderInlined(qry))
                    .fetchSet(r -> ImmutableReportGridCell.builder()
                            .subjectId(r.get(mr.ENTITY_ID))
                            .columnDefinitionId(measurableIdToDefIdMap.get(r.get(mr.MEASURABLE_ID)))
                            .ratingIdValue(r.get(rsi.ID))
                            .comment(r.get(mr.DESCRIPTION))
                            .optionText(r.get(rsi.NAME))
                            .optionCode(Long.toString(r.get(rsi.ID)))
                        .build());
        }
    }


    private Set<ReportGridCell> fetchAssessmentData(GenericSelector selector,
                                                    Collection<ReportGridFixedColumnDefinition> cols) {
        if (isEmpty(cols)) {
            return emptySet();
        } else {
            Map<Long, Long> assessmentIdToDefIdMap = indexBy(
                    cols,
                    ReportGridFixedColumnDefinition::columnEntityId,
                    ReportGridFixedColumnDefinition::gridColumnId);

            return dsl
                    .select(ar.ENTITY_ID,
                            ar.ASSESSMENT_DEFINITION_ID,
                            ar.RATING_ID,
                            rsi.NAME,
                            ar.DESCRIPTION)
                    .from(ar)
                    .innerJoin(rsi).on(ar.RATING_ID.eq(rsi.ID))
                    .where(ar.ASSESSMENT_DEFINITION_ID.in(assessmentIdToDefIdMap.keySet())
                            .and(ar.ENTITY_KIND.eq(selector.kind().name()))
                            .and(ar.ENTITY_ID.in(selector.selector())))
                    .fetchSet(r -> ImmutableReportGridCell.builder()
                            .subjectId(r.get(ar.ENTITY_ID))
                            .columnDefinitionId(assessmentIdToDefIdMap.get(r.get(ar.ASSESSMENT_DEFINITION_ID)))
                            .ratingIdValue(r.get(ar.RATING_ID))
                            .comment(r.get(ar.DESCRIPTION))
                            .optionCode(Long.toString(r.get(ar.RATING_ID)))
                            .optionText(r.get(rsi.NAME))
                            .build());
        }
    }


    private Set<ReportGridCell> fetchEntityStatisticData(GenericSelector selector,
                                                         Collection<ReportGridFixedColumnDefinition> cols) {
        if (isEmpty(cols)) {
            return emptySet();
        } else {
            Map<Long, Long> statisticDefinitionIdToColIdMap = indexBy(
                    cols,
                    ReportGridFixedColumnDefinition::columnEntityId,
                    ReportGridFixedColumnDefinition::gridColumnId);

            return dsl
                    .select(esv.ENTITY_ID,
                            esv.STATISTIC_ID,
                            esv.OUTCOME,
                            esv.REASON)
                    .from(esv)
                    .where(esv.STATISTIC_ID.in(statisticDefinitionIdToColIdMap.keySet())
                            .and(esv.CURRENT.eq(true))
                            .and(esv.ENTITY_KIND.eq(selector.kind().name()))
                            .and(esv.ENTITY_ID.in(selector.selector())))
                    .fetchSet(r -> ImmutableReportGridCell.builder()
                            .subjectId(r.get(esv.ENTITY_ID))
                            .columnDefinitionId(statisticDefinitionIdToColIdMap.get(r.get(esv.STATISTIC_ID)))
                            .textValue(r.get(esv.OUTCOME))
                            .comment(r.get(esv.REASON))
                            .optionCode(r.get(esv.OUTCOME))
                            .optionText(r.get(esv.OUTCOME))
                            .build());
        }
    }


    private Set<ReportGridCell> fetchSurveyQuestionResponseData(GenericSelector selector,
                                                                Collection<ReportGridFixedColumnDefinition> cols) {
        if (isEmpty(cols)) {
            return emptySet();
        } else {

            Map<Long, Long> questionIdToDefIdMap = indexBy(
                    cols,
                    ReportGridFixedColumnDefinition::columnEntityId,
                    ReportGridFixedColumnDefinition::gridColumnId);

            Field<Long> latestInstance = DSL
                    .firstValue(SURVEY_INSTANCE.ID)
                    .over()
                    .partitionBy(SURVEY_INSTANCE.ENTITY_ID, SURVEY_INSTANCE.ENTITY_KIND, SURVEY_QUESTION.ID)
                    .orderBy(SURVEY_INSTANCE.SUBMITTED_AT.desc().nullsLast())
                    .as("latest_instance");

            Table<Record> responsesWithQuestionTypeAndEntity = dsl
                    .select(latestInstance)
                    .select(SURVEY_INSTANCE.ID.as("sid"),
                            SURVEY_INSTANCE.ENTITY_ID,
                            SURVEY_INSTANCE.ENTITY_KIND,
                            SURVEY_QUESTION.ID)
                    .select(SURVEY_QUESTION.FIELD_TYPE)
                    .select(ENTITY_NAME_FIELD)
                    .select(SURVEY_QUESTION_RESPONSE.COMMENT)
                    .select(DSL.coalesce(
                            SURVEY_QUESTION_RESPONSE.STRING_RESPONSE,
                            DSL.when(SURVEY_QUESTION_RESPONSE.BOOLEAN_RESPONSE.isNull(), DSL.castNull(String.class))
                                    .when(SURVEY_QUESTION_RESPONSE.BOOLEAN_RESPONSE.isTrue(), DSL.val("true"))
                                    .otherwise(DSL.val("false")),
                            DSL.cast(SURVEY_QUESTION_RESPONSE.NUMBER_RESPONSE, String.class),
                            DSL.cast(SURVEY_QUESTION_RESPONSE.DATE_RESPONSE, String.class),
                            DSL.cast(SURVEY_QUESTION_RESPONSE.LIST_RESPONSE_CONCAT, String.class)).as("response")) // for entity responses need to join entity name field
                    .from(SURVEY_QUESTION_RESPONSE)
                    .innerJoin(SURVEY_INSTANCE)
                    .on(SURVEY_QUESTION_RESPONSE.SURVEY_INSTANCE_ID.eq(SURVEY_INSTANCE.ID))
                    .innerJoin(SURVEY_QUESTION)
                    .on(SURVEY_QUESTION.ID.eq(SURVEY_QUESTION_RESPONSE.QUESTION_ID))
                    .where(SURVEY_INSTANCE.STATUS.in(APPROVED.name(), COMPLETED.name())
                            .and(SURVEY_QUESTION.ID.in(questionIdToDefIdMap.keySet()))
                            .and(SURVEY_INSTANCE.ENTITY_ID.in(selector.selector()))
                            .and(SURVEY_INSTANCE.ENTITY_KIND.eq(selector.kind().name())))
                    .asTable();


            Map<Tuple2<Long, Long>, List<String>> responsesByInstanceQuestionKey = dsl
                    .select(SURVEY_QUESTION_LIST_RESPONSE.SURVEY_INSTANCE_ID,
                            SURVEY_QUESTION_LIST_RESPONSE.QUESTION_ID,
                            SURVEY_QUESTION_LIST_RESPONSE.RESPONSE)
                    .from(SURVEY_QUESTION_LIST_RESPONSE)
                    .innerJoin(SURVEY_INSTANCE).on(SURVEY_QUESTION_LIST_RESPONSE.SURVEY_INSTANCE_ID.eq(SURVEY_INSTANCE.ID))
                    .where(SURVEY_QUESTION_LIST_RESPONSE.QUESTION_ID.in(questionIdToDefIdMap.keySet()))
                    .and(SURVEY_INSTANCE.ENTITY_ID.in(selector.selector()))
                    .and(SURVEY_INSTANCE.ENTITY_KIND.eq(selector.kind().name()))
                    .fetchGroups(
                            k -> tuple(k.get(SURVEY_QUESTION_LIST_RESPONSE.SURVEY_INSTANCE_ID), k.get(SURVEY_QUESTION_LIST_RESPONSE.QUESTION_ID)),
                            v -> v.get(SURVEY_QUESTION_LIST_RESPONSE.RESPONSE));

            SelectConditionStep<Record> qry = dsl
                    .select(responsesWithQuestionTypeAndEntity.fields())
                    .from(responsesWithQuestionTypeAndEntity)
                    .where(responsesWithQuestionTypeAndEntity.field(latestInstance)
                            .eq(responsesWithQuestionTypeAndEntity.field("sid", Long.class)));

            return qry
                    .fetchSet(r -> {
                        String fieldType = r.get(SURVEY_QUESTION.FIELD_TYPE);

                        Long instanceId = r.get("sid", Long.class);
                        Long questionId = r.get(SURVEY_QUESTION.ID, Long.class);
                        String entityName = r.get(ENTITY_NAME_FIELD);
                        String response = r.get("response", String.class);

                        List<String> listResponses = responsesByInstanceQuestionKey.getOrDefault(tuple(instanceId, questionId), emptyList());

                        return ImmutableReportGridCell.builder()
                                .subjectId(r.get(SURVEY_INSTANCE.ENTITY_ID))
                                .columnDefinitionId(questionIdToDefIdMap.get(questionId))
                                .textValue(determineDisplayText(fieldType, entityName, response, listResponses))
                                .comment(r.get(SURVEY_QUESTION_RESPONSE.COMMENT))
                                .build();
                    });
        }
    }


    private String determineDisplayText(String fieldType,
                                        String entityName,
                                        String response,
                                        List<String> listResponses) {
        switch (fieldType) {
            case "PERSON":
            case "APPLICATION":
                return entityName;
            case "MEASURABLE_MULTI_SELECT":
                return join(listResponses, "; ");
            default:
                return response;
        }
    }


    private ImmutableReportGridDefinition mkReportGridDefinition(Condition condition,
                                                                 ReportGridRecord r) {
        return ImmutableReportGridDefinition
                .builder()
                .id(r.get(rg.ID))
                .name(r.get(rg.NAME))
                .description(r.get(rg.DESCRIPTION))
                .externalId(ofNullable(r.get(rg.EXTERNAL_ID)))
                .provenance(r.get(rg.PROVENANCE))
                .lastUpdatedAt(toLocalDateTime(r.get(rg.LAST_UPDATED_AT)))
                .lastUpdatedBy(r.get(rg.LAST_UPDATED_BY))
                .fixedColumnDefinitions(getFixedColumnDefinitions(condition))
                .derivedColumnDefinitions(getDerivedColumnDefinitions(condition))
                .subjectKind(EntityKind.valueOf(r.get(rg.SUBJECT_KIND)))
                .kind(ReportGridKind.valueOf(r.get(rg.KIND)))
                .build();
    }




    private UsageKind deriveUsage(Set<UsageKind> usageKinds) {
        if (usageKinds.contains(UsageKind.MODIFIER)) {
            return UsageKind.MODIFIER;
        } else if (usageKinds.contains(UsageKind.DISTRIBUTOR)) {
            return UsageKind.DISTRIBUTOR;
        } else if (usageKinds.contains(UsageKind.CONSUMER) && usageKinds.contains(UsageKind.ORIGINATOR)) {
            return UsageKind.DISTRIBUTOR;
        } else {
            // should be only one left (either CONSUMER or ORIGINATOR)
            return first(usageKinds);
        }
    }

}

